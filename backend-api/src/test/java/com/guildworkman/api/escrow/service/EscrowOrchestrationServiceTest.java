package com.guildworkman.api.escrow.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EscrowOrchestrationServiceTest {

    private EscrowOrchestrationRequestRepository repository;
    private EscrowOrchestrationInserter inserter;
    private SorobanRpcClient sorobanRpcClient;
    private EscrowOrchestrationRetryProperties retryProperties;
    private EscrowOrchestrationService service;

    @BeforeEach
    void setUp() {
        repository = mock(EscrowOrchestrationRequestRepository.class);
        inserter = mock(EscrowOrchestrationInserter.class);
        sorobanRpcClient = mock(SorobanRpcClient.class);
        retryProperties = new EscrowOrchestrationRetryProperties();
        service = new EscrowOrchestrationService(repository, inserter, sorobanRpcClient, retryProperties);
    }

    private static SubmitOrchestrationRequest request(String key) {
        return new SubmitOrchestrationRequest(key, EscrowOperationType.CONFIRM_COMPLETION, "CABC", "42", "AAAA==");
    }

    private static EscrowOrchestrationRequest entity(Long id, String key, OrchestrationStatus status) {
        EscrowOrchestrationRequest e = new EscrowOrchestrationRequest();
        e.setId(id);
        e.setIdempotencyKey(key);
        e.setOperationType(EscrowOperationType.CONFIRM_COMPLETION);
        e.setContractId("CABC");
        e.setOperationRef("42");
        e.setSignedTransactionXdr("AAAA==");
        e.setStatus(status);
        e.setReconciliationStatus(ReconciliationStatus.PENDING);
        return e;
    }

    // --- submit() idempotency ------------------------------------------------

    @Test
    void submitInsertsNewRequestForNewIdempotencyKey() {
        var req = request("idem-1");
        when(repository.findByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        var saved = entity(1L, "idem-1", OrchestrationStatus.PENDING);
        when(inserter.insert(req)).thenReturn(saved);

        var result = service.submit(req);

        assertThat(result.request().getId()).isEqualTo(1L);
        assertThat(result.replayed()).isFalse();
        verify(inserter).insert(req);
    }

    @Test
    void submitReturnsExistingRequestOnDuplicateIdempotencyKey() {
        var req = request("idem-dup");
        var existing = entity(1L, "idem-dup", OrchestrationStatus.SUBMITTED);
        when(repository.findByIdempotencyKey("idem-dup")).thenReturn(Optional.of(existing));

        var result = service.submit(req);

        assertThat(result.request().getId()).isEqualTo(1L);
        assertThat(result.replayed()).isTrue();
        verify(inserter, never()).insert(any());
    }

    @Test
    void submitHandlesDataIntegrityViolationWithFallbackLookup() {
        var req = request("race");
        when(repository.findByIdempotencyKey("race")).thenReturn(Optional.empty());
        when(inserter.insert(req)).thenThrow(new DataIntegrityViolationException("dup key"));
        var winner = entity(9L, "race", OrchestrationStatus.PENDING);
        when(repository.findByIdempotencyKey("race")).thenReturn(Optional.empty(), Optional.of(winner));

        var result = service.submit(req);

        assertThat(result.request().getId()).isEqualTo(9L);
        assertThat(result.replayed()).isTrue();
    }

    @Test
    void getThrowsWhenNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(404L))
                .isInstanceOf(EscrowOrchestrationNotFoundException.class);
    }

    // --- requeueReconciliation() ---------------------------------------------

    @Test
    void requeueReconciliationResetsAMismatchedRequestToPending() {
        var e = entity(1L, "k", OrchestrationStatus.CONFIRMED);
        e.setReconciliationStatus(ReconciliationStatus.MISMATCHED);
        e.setReconciledAt(Instant.now());
        when(repository.findById(1L)).thenReturn(Optional.of(e));

        var result = service.requeueReconciliation(1L);

        assertThat(result.getReconciliationStatus()).isEqualTo(ReconciliationStatus.PENDING);
        assertThat(result.getReconciledAt()).isNull();
        verify(repository).save(e);
    }

    @Test
    void requeueReconciliationRejectsANonMismatchedRequest() {
        var e = entity(1L, "k", OrchestrationStatus.CONFIRMED);
        e.setReconciliationStatus(ReconciliationStatus.MATCHED);
        when(repository.findById(1L)).thenReturn(Optional.of(e));

        assertThatThrownBy(() -> service.requeueReconciliation(1L))
                .isInstanceOf(ReconciliationRequeueNotAllowedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void requeueReconciliationThrowsWhenNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requeueReconciliation(404L))
                .isInstanceOf(EscrowOrchestrationNotFoundException.class);
    }

    // --- submitOne() -----------------------------------------------------------

    @Test
    void submitOneTransitionsToSubmittedOnAcceptedResult() {
        var e = entity(1L, "k", OrchestrationStatus.PENDING);
        when(sorobanRpcClient.sendTransaction("AAAA==")).thenReturn(new SendTransactionResult("hash123", "PENDING", null));

        service.submitOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.SUBMITTED);
        assertThat(e.getSorobanTxHash()).isEqualTo("hash123");
        assertThat(e.getSubmittedAt()).isNotNull();
        verify(repository).save(e);
    }

    @Test
    void submitOneTreatsDuplicateAsAccepted() {
        var e = entity(1L, "k", OrchestrationStatus.PENDING);
        when(sorobanRpcClient.sendTransaction(any())).thenReturn(new SendTransactionResult("hash123", "DUPLICATE", null));

        service.submitOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.SUBMITTED);
    }

    @Test
    void submitOneFailsTerminallyOnRpcErrorStatus() {
        var e = entity(1L, "k", OrchestrationStatus.PENDING);
        when(sorobanRpcClient.sendTransaction(any())).thenReturn(new SendTransactionResult(null, "ERROR", "bad-xdr"));

        service.submitOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(e.getLastError()).contains("bad-xdr");
    }

    @Test
    void submitOneRetriesOnTryAgainLater() {
        var e = entity(1L, "k", OrchestrationStatus.PENDING);
        e.setAttempts(0);
        when(sorobanRpcClient.sendTransaction(any())).thenReturn(new SendTransactionResult(null, "TRY_AGAIN_LATER", null));

        service.submitOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void submitOneMovesToDeadLetterAfterMaxAttempts() {
        var e = entity(1L, "k", OrchestrationStatus.PENDING);
        e.setAttempts(retryProperties.getMaxAttempts() - 1);
        when(sorobanRpcClient.sendTransaction(any())).thenThrow(new SorobanRpcException("timeout"));

        service.submitOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.DEAD_LETTER);
        assertThat(e.getAttempts()).isEqualTo(retryProperties.getMaxAttempts());
    }

    @Test
    void submitOneRetriesOnRpcException() {
        var e = entity(1L, "k", OrchestrationStatus.PENDING);
        e.setAttempts(0);
        when(sorobanRpcClient.sendTransaction(any())).thenThrow(new SorobanRpcException("network error"));

        service.submitOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.PENDING);
        assertThat(e.getLastError()).isEqualTo("network error");
    }

    // --- pollOne() ---------------------------------------------------------

    @Test
    void pollOneConfirmsOnSuccess() {
        var e = entity(1L, "k", OrchestrationStatus.SUBMITTED);
        e.setSorobanTxHash("hash123");
        when(sorobanRpcClient.getTransaction("hash123")).thenReturn(new GetTransactionResult("SUCCESS", "resultXdr", 100L));

        service.pollOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.CONFIRMED);
        assertThat(e.getConfirmedAt()).isNotNull();
    }

    @Test
    void pollOneFailsTerminallyOnChainFailure() {
        var e = entity(1L, "k", OrchestrationStatus.SUBMITTED);
        e.setSorobanTxHash("hash123");
        when(sorobanRpcClient.getTransaction("hash123")).thenReturn(new GetTransactionResult("FAILED", "resultXdr", 100L));

        service.pollOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.FAILED);
    }

    @Test
    void pollOneKeepsPollingOnNotFound() {
        var e = entity(1L, "k", OrchestrationStatus.SUBMITTED);
        e.setSorobanTxHash("hash123");
        e.setAttempts(0);
        when(sorobanRpcClient.getTransaction("hash123")).thenReturn(new GetTransactionResult("NOT_FOUND", null, null));

        service.pollOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.SUBMITTED);
        assertThat(e.getAttempts()).isEqualTo(1);
    }

    @Test
    void pollOneMovesToDeadLetterAfterMaxAttemptsNotFound() {
        var e = entity(1L, "k", OrchestrationStatus.SUBMITTED);
        e.setSorobanTxHash("hash123");
        e.setAttempts(retryProperties.getMaxAttempts() - 1);
        when(sorobanRpcClient.getTransaction("hash123")).thenReturn(new GetTransactionResult("NOT_FOUND", null, null));

        service.pollOne(e);

        assertThat(e.getStatus()).isEqualTo(OrchestrationStatus.DEAD_LETTER);
    }
}
