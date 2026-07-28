package com.guildworkman.api.escrow.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A single request to submit and confirm one escrow-contract operation over
 * Soroban RPC.
 *
 * <p>The caller supplies an already-signed transaction envelope
 * ({@link #signedTransactionXdr}) — this service does not build or sign
 * transactions itself, it only relays them to Soroban RPC and tracks their
 * outcome. That keeps idempotent submission, retry/backoff, and status
 * polling entirely inside the JVM, without needing to hand-roll Soroban's
 * XDR wire format.
 *
 * <p>{@link #idempotencyKey} is unique so retried client requests (e.g. a
 * network retry on the REST call) never create a second row for the same
 * logical operation; see {@code EscrowOrchestrationInserter}.
 */
@Entity
@Table(name = "escrow_orchestration_requests",
        uniqueConstraints = @UniqueConstraint(name = "uk_escrow_orch_idempotency_key", columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "idx_escrow_orch_status", columnList = "status,next_attempt_at"),
                @Index(name = "idx_escrow_orch_reconciliation", columnList = "reconciliation_status"),
                @Index(name = "idx_escrow_orch_contract_ref", columnList = "contract_id,operation_ref")
        })
@Getter
@Setter
@NoArgsConstructor
public class EscrowOrchestrationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 32, updatable = false)
    private EscrowOperationType operationType;

    @Column(name = "contract_id", nullable = false, length = 128, updatable = false)
    private String contractId;

    /**
     * Domain identifier the operation acts on (appointment id or milestone
     * escrow id, as a string). Used to correlate this request with ingested
     * on-chain events during reconciliation.
     */
    @Column(name = "operation_ref", nullable = false, length = 128, updatable = false)
    private String operationRef;

    /** Base64-encoded, already-signed {@code TransactionEnvelope} XDR. */
    @Lob
    @Column(name = "signed_transaction_xdr", nullable = false, updatable = false)
    private String signedTransactionXdr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrchestrationStatus status = OrchestrationStatus.PENDING;

    @Column(name = "soroban_tx_hash", length = 64)
    private String sorobanTxHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 20)
    private ReconciliationStatus reconciliationStatus = ReconciliationStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant submittedAt;

    private Instant confirmedAt;

    private Instant reconciledAt;

    @Version
    private long version;
}
