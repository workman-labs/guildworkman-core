package com.guildworkman.api.escrow.service;

import com.guildworkman.api.chain.model.ChainEventStatus;
import com.guildworkman.api.chain.model.OnChainEvent;
import com.guildworkman.api.chain.repository.OnChainEventRepository;
import com.guildworkman.api.escrow.model.EscrowOperationType;
import com.guildworkman.api.escrow.model.EscrowOrchestrationRequest;
import com.guildworkman.api.escrow.model.OrchestrationStatus;
import com.guildworkman.api.escrow.model.ReconciliationStatus;
import com.guildworkman.api.escrow.repository.EscrowOrchestrationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EscrowReconciliationServiceTest {

    private EscrowOrchestrationRequestRepository orchestrationRequests;
    private OnChainEventRepository onChainEvents;
    private EscrowReconciliationProperties properties;
    private EscrowReconciliationService service;

    @BeforeEach
    void setUp() {
        orchestrationRequests = mock(EscrowOrchestrationRequestRepository.class);
        onChainEvents = mock(OnChainEventRepository.class);
        properties = new EscrowReconciliationProperties();
        properties.setWindow(Duration.ofMinutes(10));
        service = new EscrowReconciliationService(orchestrationRequests, onChainEvents, properties);
    }

    private static EscrowOrchestrationRequest confirmed(String contractId, String operationRef, Instant confirmedAt) {
        EscrowOrchestrationRequest e = new EscrowOrchestrationRequest();
        e.setId(1L);
        e.setContractId(contractId);
        e.setOperationRef(operationRef);
        e.setOperationType(EscrowOperationType.CONFIRM_COMPLETION);
        e.setStatus(OrchestrationStatus.CONFIRMED);
        e.setReconciliationStatus(ReconciliationStatus.PENDING);
        e.setConfirmedAt(confirmedAt);
        return e;
    }

    private static OnChainEvent event(ChainEventStatus status) {
        OnChainEvent e = new OnChainEvent();
        e.setId(1L);
        e.setStatus(status);
        return e;
    }

    @Test
    void marksMatchedWhenProcessedOnChainEventFound() {
        var request = confirmed("CABC", "42", Instant.now());
        when(onChainEvents.findByContractIdAndTopicsContaining(eq("CABC"), eq("\"42\"")))
                .thenReturn(List.of(event(ChainEventStatus.PROCESSED)));

        service.reconcileOne(request);

        assertThat(request.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MATCHED);
        assertThat(request.getReconciledAt()).isNotNull();
        verify(orchestrationRequests).save(request);
    }

    @Test
    void staysPendingWhenMatchingEventNotYetProcessed() {
        var request = confirmed("CABC", "42", Instant.now());
        when(onChainEvents.findByContractIdAndTopicsContaining(any(), any()))
                .thenReturn(List.of(event(ChainEventStatus.PENDING)));

        service.reconcileOne(request);

        assertThat(request.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING);
    }

    @Test
    void staysPendingWithinGraceWindowWhenNoEventFound() {
        var request = confirmed("CABC", "42", Instant.now());
        when(onChainEvents.findByContractIdAndTopicsContaining(any(), any())).thenReturn(List.of());

        service.reconcileOne(request);

        assertThat(request.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING);
    }

    @Test
    void marksMismatchedAfterGraceWindowElapsesWithNoEvent() {
        var request = confirmed("CABC", "42", Instant.now().minus(Duration.ofMinutes(11)));
        when(onChainEvents.findByContractIdAndTopicsContaining(any(), any())).thenReturn(List.of());

        service.reconcileOne(request);

        assertThat(request.getReconciliationStatus()).isEqualTo(ReconciliationStatus.MISMATCHED);
        assertThat(request.getReconciledAt()).isNotNull();
        verify(orchestrationRequests).save(request);
    }
}
