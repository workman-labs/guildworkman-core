package com.guildworkman.api.escrow;

import com.guildworkman.api.chain.model.ChainEventStatus;
import com.guildworkman.api.chain.model.OnChainEvent;
import com.guildworkman.api.chain.repository.OnChainEventRepository;
import com.guildworkman.api.escrow.api.SubmitOrchestrationRequest;
import com.guildworkman.api.escrow.model.EscrowOperationType;
import com.guildworkman.api.escrow.model.EscrowOrchestrationRequest;
import com.guildworkman.api.escrow.model.OrchestrationStatus;
import com.guildworkman.api.escrow.model.ReconciliationStatus;
import com.guildworkman.api.escrow.repository.EscrowOrchestrationRequestRepository;
import com.guildworkman.api.escrow.rpc.GetTransactionResult;
import com.guildworkman.api.escrow.rpc.SendTransactionResult;
import com.guildworkman.api.escrow.rpc.SorobanRpcClient;
import com.guildworkman.api.escrow.rpc.SorobanRpcException;
import com.guildworkman.api.escrow.service.EscrowOrchestrationService;
import com.guildworkman.api.escrow.service.EscrowReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "escrow.orchestration.submit-poll-delay-ms=3600000",
        "escrow.orchestration.confirm-poll-delay-ms=3600000",
        "escrow.reconciliation.poll-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000",
        "spring.task.scheduling.enabled=false"
})
class EscrowOrchestrationIntegrationTest {

    @Autowired
    private EscrowOrchestrationRequestRepository orchestrationRequests;

    @Autowired
    private OnChainEventRepository onChainEvents;

    @Autowired
    private EscrowOrchestrationService orchestrationService;

    @Autowired
    private EscrowReconciliationService reconciliationService;

    @MockBean
    private SorobanRpcClient sorobanRpcClient;

    @BeforeEach
    void cleanSlate() {
        reset(sorobanRpcClient);
        orchestrationRequests.deleteAll();
        onChainEvents.deleteAll();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static SubmitOrchestrationRequest submitRequest(String idemKey, String operationRef) {
        return new SubmitOrchestrationRequest(idemKey, EscrowOperationType.CONFIRM_COMPLETION, "CTEST", operationRef, "AAAA==");
    }

    // --- submit() idempotency -----------------------------------------------

    @Test
    void submitCreatesAPendingRequest() {
        var outcome = orchestrationService.submit(submitRequest(key("k1"), "1"));
        assertThat(outcome.replayed()).isFalse();

        var saved = orchestrationRequests.findById(outcome.request().getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrchestrationStatus.PENDING);
        assertThat(saved.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING);
    }

    @Test
    void submitIsIdempotentForDuplicateKey() {
        String idemKey = key("dup");
        var first = orchestrationService.submit(submitRequest(idemKey, "1"));
        var second = orchestrationService.submit(submitRequest(idemKey, "1"));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.request().getId()).isEqualTo(first.request().getId());
        assertThat(orchestrationRequests.count()).isEqualTo(1);
    }

    @Test
    void concurrentSubmitsWithSameIdempotencyKeyCreateOnlyOneRow() throws Exception {
        String idemKey = key("race");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Long>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> orchestrationService.submit(submitRequest(idemKey, "1")).request().getId());
        }

        List<Future<Long>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
        List<Long> ids = new java.util.ArrayList<>();
        for (Future<Long> f : futures) {
            ids.add(f.get(5, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(ids.stream().distinct().count()).isEqualTo(1);
        assertThat(orchestrationRequests.count()).isEqualTo(1);
    }

    // --- full submit -> confirm lifecycle -----------------------------------

    @Test
    void fullLifecycleFromSubmitToConfirmed() {
        var resp = orchestrationService.submit(submitRequest(key("lifecycle"), "7")).request();
        when(sorobanRpcClient.sendTransaction(any())).thenReturn(new SendTransactionResult("hash-1", "PENDING", null));

        orchestrationService.submitPending();
        EscrowOrchestrationRequest afterSubmit = orchestrationRequests.findById(resp.getId()).orElseThrow();
        assertThat(afterSubmit.getStatus()).isEqualTo(OrchestrationStatus.SUBMITTED);
        assertThat(afterSubmit.getSorobanTxHash()).isEqualTo("hash-1");

        when(sorobanRpcClient.getTransaction("hash-1")).thenReturn(new GetTransactionResult("SUCCESS", "xdr", 100L));
        orchestrationService.pollSubmitted();

        EscrowOrchestrationRequest confirmed = orchestrationRequests.findById(resp.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(OrchestrationStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();
    }

    // Mirrors EscrowOrchestrationRetryProperties' default maxAttempts (this
    // test doesn't override escrow.orchestration.retry.max-attempts).
    private static final int MAX_ATTEMPTS = 5;

    @Test
    void rpcFailureAppliesBackoffThenDeadLetter() {
        var resp = orchestrationService.submit(submitRequest(key("dl"), "9")).request();
        // Force nextAttemptAt into the past and attempts near the ceiling so the
        // very next failure trips DEAD_LETTER without looping through backoff delays.
        EscrowOrchestrationRequest entity = orchestrationRequests.findById(resp.getId()).orElseThrow();
        entity.setAttempts(MAX_ATTEMPTS - 1);
        entity.setNextAttemptAt(Instant.now().minusSeconds(1));
        orchestrationRequests.saveAndFlush(entity);

        when(sorobanRpcClient.sendTransaction(any())).thenThrow(new SorobanRpcException("boom"));
        orchestrationService.submitPending();

        EscrowOrchestrationRequest reloaded = orchestrationRequests.findById(resp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrchestrationStatus.DEAD_LETTER);
        assertThat(reloaded.getAttempts()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void pessimisticLockingPreventsDoubleSubmission() throws Exception {
        var resp = orchestrationService.submit(submitRequest(key("concurrent"), "3")).request();
        when(sorobanRpcClient.sendTransaction(any())).thenReturn(new SendTransactionResult("hash-x", "PENDING", null));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                orchestrationService.submitPending();
                return null;
            });
        }
        List<Future<Void>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
        for (Future<Void> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        EscrowOrchestrationRequest reloaded = orchestrationRequests.findById(resp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrchestrationStatus.SUBMITTED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
    }

    // --- reconciliation -------------------------------------------------------

    private EscrowOrchestrationRequest saveConfirmed(String operationRef, Instant confirmedAt) {
        EscrowOrchestrationRequest e = new EscrowOrchestrationRequest();
        e.setIdempotencyKey(key("recon"));
        e.setOperationType(EscrowOperationType.CONFIRM_COMPLETION);
        e.setContractId("CTEST");
        e.setOperationRef(operationRef);
        e.setSignedTransactionXdr("AAAA==");
        e.setStatus(OrchestrationStatus.CONFIRMED);
        e.setReconciliationStatus(ReconciliationStatus.PENDING);
        e.setConfirmedAt(confirmedAt);
        e.setNextAttemptAt(Instant.now());
        return orchestrationRequests.saveAndFlush(e);
    }

    private void saveChainEvent(String operationRef, ChainEventStatus status) {
        OnChainEvent event = new OnChainEvent();
        event.setEventKey(key("evt"));
        event.setContractId("CTEST");
        event.setLedger(1);
        event.setEventIndex(0);
        event.setTopics("[\"" + operationRef + "\"]");
        event.setPayload("{}");
        event.setStatus(status);
        event.setNextAttemptAt(Instant.now());
        onChainEvents.saveAndFlush(event);
    }

    @Test
    void reconciliationMatchesWhenProcessedEventExists() {
        var request = saveConfirmed("55", Instant.now());
        saveChainEvent("55", ChainEventStatus.PROCESSED);

        reconciliationService.reconcilePending();

        EscrowOrchestrationRequest reloaded = orchestrationRequests.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
    }

    @Test
    void reconciliationFlagsMismatchAfterWindowElapsesWithNoEvent() {
        var request = saveConfirmed("66", Instant.now().minusSeconds(20 * 60));

        reconciliationService.reconcilePending();

        EscrowOrchestrationRequest reloaded = orchestrationRequests.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MISMATCHED);
    }

    @Test
    void reconciliationLeavesPendingWithinGraceWindow() {
        var request = saveConfirmed("77", Instant.now());

        reconciliationService.reconcilePending();

        EscrowOrchestrationRequest reloaded = orchestrationRequests.findById(request.getId()).orElseThrow();
        assertThat(reloaded.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING);
    }

    @Test
    void requeuedMismatchIsPickedUpByTheNextReconciliationSweep() {
        var request = saveConfirmed("88", Instant.now().minusSeconds(20 * 60));
        reconciliationService.reconcilePending();
        assertThat(orchestrationRequests.findById(request.getId()).orElseThrow().getReconciliationStatus())
                .isEqualTo(ReconciliationStatus.MISMATCHED);

        orchestrationService.requeueReconciliation(request.getId());
        assertThat(orchestrationRequests.findById(request.getId()).orElseThrow().getReconciliationStatus())
                .isEqualTo(ReconciliationStatus.PENDING);

        // Now the corroborating event shows up before the sweep runs again.
        saveChainEvent("88", ChainEventStatus.PROCESSED);
        reconciliationService.reconcilePending();

        assertThat(orchestrationRequests.findById(request.getId()).orElseThrow().getReconciliationStatus())
                .isEqualTo(ReconciliationStatus.MATCHED);
    }
}
