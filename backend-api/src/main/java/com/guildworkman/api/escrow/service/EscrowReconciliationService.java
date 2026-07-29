package com.guildworkman.api.escrow.service;

import com.guildworkman.api.chain.model.ChainEventStatus;
import com.guildworkman.api.chain.model.OnChainEvent;
import com.guildworkman.api.chain.repository.OnChainEventRepository;
import com.guildworkman.api.escrow.model.EscrowOrchestrationRequest;
import com.guildworkman.api.escrow.model.OrchestrationStatus;
import com.guildworkman.api.escrow.model.ReconciliationStatus;
import com.guildworkman.api.escrow.repository.EscrowOrchestrationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Reconciles off-chain orchestration state against on-chain reality.
 *
 * <p>Rather than issuing our own Soroban RPC ledger-entry reads (which would
 * require encoding {@code LedgerKey}/{@code ScVal} XDR by hand — the very
 * thing {@link com.guildworkman.api.escrow.rpc.SorobanRpcClient} deliberately
 * avoids), reconciliation is done against the on-chain event stream already
 * ingested by {@code com.guildworkman.api.chain} (see issue #22's
 * transactional-outbox ingestion pipeline). A {@link OrchestrationStatus#CONFIRMED}
 * request is considered corroborated once a {@link ChainEventStatus#PROCESSED}
 * event for the same contract, tagged with this request's {@code operationRef}
 * as one of its topics, has been ingested.
 *
 * <p>By convention, the indexer feeding {@code /api/v1/chain/events} is
 * expected to include the affected appointment/escrow id as an event topic
 * — topics already model semantic Soroban event tags (see
 * {@code IngestChainEventRequest}).
 */
@Service
@RequiredArgsConstructor
public class EscrowReconciliationService {

    private static final int BATCH_SIZE = 20;

    private final EscrowOrchestrationRequestRepository orchestrationRequests;
    private final OnChainEventRepository onChainEvents;
    private final EscrowReconciliationProperties properties;

    @Scheduled(fixedDelayString = "${escrow.reconciliation.poll-delay-ms:5000}")
    @Transactional
    public void reconcilePending() {
        List<EscrowOrchestrationRequest> candidates = orchestrationRequests.findByStatusAndReconciliationStatus(
                OrchestrationStatus.CONFIRMED, ReconciliationStatus.PENDING, PageRequest.of(0, BATCH_SIZE));
        candidates.forEach(this::reconcileOne);
    }

    void reconcileOne(EscrowOrchestrationRequest entity) {
        // OnChainEvent.topics is a JSON-encoded string column (see
        // ChainEventInserter), not a normalized list, so matching a specific
        // topic value means matching its literal JSON-quoted form as a
        // substring — e.g. operationRef "42" must appear as `"42"` inside
        // something like `["42","Completed"]`. This is a LIKE %..% query
        // (OnChainEventRepository#findByContractIdAndTopicsContaining); the
        // quotes keep it from misfiring on a numeric prefix ("4" won't match
        // "42"). SubmitOrchestrationRequest rejects a literal '"' in
        // operationRef so it can't break out of this quoting.
        String topicFragment = "\"" + entity.getOperationRef() + "\"";
        List<OnChainEvent> matches = onChainEvents.findByContractIdAndTopicsContaining(
                entity.getContractId(), topicFragment);

        // PROCESSED (not just "ingested") because a PENDING/PROCESSING event
        // might still fail its own retries and never actually reflect this
        // operation having taken effect — see ChainEventService.
        boolean corroborated = matches.stream().anyMatch(e -> e.getStatus() == ChainEventStatus.PROCESSED);
        if (corroborated) {
            entity.setReconciliationStatus(ReconciliationStatus.MATCHED);
            entity.setReconciledAt(Instant.now());
            orchestrationRequests.save(entity);
            return;
        }

        Instant deadline = entity.getConfirmedAt() != null
                ? entity.getConfirmedAt().plus(properties.getWindow())
                : Instant.now();
        if (Instant.now().isAfter(deadline)) {
            // Terminal: MISMATCHED is not automatically retried by this sweep
            // (findByStatusAndReconciliationStatus only selects PENDING). See
            // docs/ESCROW_ORCHESTRATION.md "Operations" for how to force a
            // recheck once the indexer catches up.
            entity.setReconciliationStatus(ReconciliationStatus.MISMATCHED);
            entity.setReconciledAt(Instant.now());
            orchestrationRequests.save(entity);
        }
        // else: still within the grace window — leave PENDING for the next tick.
    }
}
