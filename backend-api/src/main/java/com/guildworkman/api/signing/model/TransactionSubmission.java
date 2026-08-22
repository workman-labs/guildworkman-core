package com.guildworkman.api.signing.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The durable record of one transaction this service signs and submits — the
 * write-ahead log that lets a restart resume instead of double-submitting.
 *
 * <p>The caller supplies {@link #unsignedTransactionXdr}: an envelope carrying
 * the operations it wants executed. Source account, sequence number, fee and
 * time bounds on that envelope are placeholders — this service replaces all
 * four when it builds the real transaction against a leased channel account,
 * which is what makes rebuilding possible when a sequence goes stale or time
 * bounds lapse. (Contrast {@code EscrowOrchestrationRequest}, which relays an
 * envelope the caller has already signed and therefore can never rebuild.)
 *
 * <p><b>Ordering rule for restart safety.</b> {@link #signedEnvelopeXdr} and
 * {@link #transactionHash} are committed while the row is
 * {@link SubmissionStatus#SIGNED}, strictly before {@code sendTransaction} is
 * called. Recovery therefore never has to guess: any row that is
 * {@code SIGNED} or {@code BROADCAST} has a known hash, and the recovery path
 * asks the network about that hash before it considers sending anything. Even
 * a resend is harmless — the same envelope hashes to the same transaction, so
 * Soroban RPC answers {@code DUPLICATE} rather than executing it twice.
 *
 * <p>{@link #transactionHash} tracks the envelope currently in the mempool,
 * which after a fee bump is the <em>outer</em> fee-bump transaction;
 * {@link #innerTransactionHash} keeps the original so a transaction that
 * landed on its own, just as we bumped it, is still recognised.
 *
 * <p><b>What must not escape this row.</b> No key material is stored here —
 * {@link #keyRef} is an alias and {@link #sourceAccount} a public key — but
 * {@link #signedEnvelopeXdr} carries signatures, and it plus
 * {@link #unsignedTransactionXdr} and {@link #resultXdr} carry the full
 * contents of what the caller asked to execute. None of that belongs in a log
 * line or an HTTP response, so all three are {@link JsonIgnore}d and
 * {@link #toString()} renders identifiers only. The API answers from
 * {@code TransactionSubmissionResponse}, which never reads them at all; these
 * are the second lock, for the day someone returns an entity directly.
 */
@Entity
@Table(name = "stellar_transaction_submissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_stellar_submission_idempotency_key", columnNames = "idempotency_key"),
        indexes = {
                @Index(name = "idx_stellar_submission_status", columnList = "status,next_attempt_at"),
                @Index(name = "idx_stellar_submission_hash", columnList = "transaction_hash"),
                @Index(name = "idx_stellar_submission_reference", columnList = "reference")
        })
@Getter
@Setter
@NoArgsConstructor
public class TransactionSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Caller-generated key; resubmitting it returns the original submission instead of signing a second transaction. */
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    /** Caller's own correlation handle (appointment id, escrow id, …). Opaque here. */
    @Column(length = 128)
    private String reference;

    /**
     * Base64 {@code TransactionEnvelope} XDR supplying the operations to execute. Never signed as-is.
     *
     * <p>{@code text}, deliberately, rather than {@code @Lob}. Hibernate maps
     * {@code @Lob String} onto a PostgreSQL {@code oid} — a pointer into
     * {@code pg_largeobject} — which brings two problems this table cannot
     * live with. Large objects are <b>not</b> removed when the row referencing
     * them is deleted, so a service submitting transactions continuously would
     * leak one per submission, unbounded and unreclaimable by {@code VACUUM}.
     * And reading one requires an open transaction, so any read outside one
     * fails at runtime with "Unable to access lob stream". PostgreSQL
     * {@code text} is unbounded and none of that applies.
     */
    @JsonIgnore
    @Column(name = "unsigned_transaction_xdr", nullable = false, updatable = false, columnDefinition = "text")
    private String unsignedTransactionXdr;

    /**
     * Comma-separated key references that must co-sign in addition to the
     * channel account (e.g. a contract-admin key). Aliases only — see
     * {@code SigningProvider}.
     */
    @Column(name = "extra_signer_key_refs", length = 256, updatable = false)
    private String extraSignerKeyRefs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubmissionStatus status = SubmissionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", nullable = false, length = 24)
    private SubmissionFailureReason failureReason = SubmissionFailureReason.NONE;

    /** Which custody backend produced the signature ({@code local}/{@code kms}). */
    @Column(name = "signing_provider", length = 32)
    private String signingProvider;

    @Column(name = "channel_account_id")
    private Long channelAccountId;

    /** Channel account this transaction is built on, denormalised so the record survives pool changes. */
    @Column(name = "source_account", length = 56)
    private String sourceAccount;

    /** Key reference the channel account was signed with — the fee-bump signer too, unless one is configured. */
    @Column(name = "key_ref", length = 64)
    private String keyRef;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    /** Total fee of the envelope currently in flight, in stroops. */
    @Column(name = "fee_stroops")
    private Long feeStroops;

    @Column(name = "fee_bump_count", nullable = false)
    private int feeBumpCount;

    /**
     * Signed envelope currently in flight — after a fee bump, the fee-bump
     * envelope. Carries signatures. {@code text} for the reasons on
     * {@link #unsignedTransactionXdr}.
     */
    @JsonIgnore
    @Column(name = "signed_envelope_xdr", columnDefinition = "text")
    private String signedEnvelopeXdr;

    /** Hash of {@link #signedEnvelopeXdr}: what {@code getTransaction} is polled with. */
    @Column(name = "transaction_hash", length = 64)
    private String transactionHash;

    /** Hash of the inner transaction, unchanged across fee bumps. */
    @Column(name = "inner_transaction_hash", length = 64)
    private String innerTransactionHash;

    @Column(name = "ledger_sequence")
    private Long ledgerSequence;

    /**
     * The signed envelope's {@code maxTime} time bound. Past this instant the
     * network can never include it, whatever the mempool says — which is what
     * lets the poller decide a transaction is dead instead of waiting for a
     * verdict that will never come.
     */
    @Column(name = "valid_until")
    private Instant validUntil;

    /** Base64 {@code TransactionResult} XDR of a terminal outcome, for post-hoc diagnosis. Echoes transaction contents. */
    @JsonIgnore
    @Column(name = "result_xdr", length = 2000)
    private String resultXdr;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    /** Always passed through {@code SecretRedactor} before it's set. */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "broadcast_at")
    private Instant broadcastAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Version
    private long version;

    /**
     * Identifiers and state only. Written out by hand rather than generated so
     * that a field added later has to be added here deliberately — a Lombok
     * {@code @ToString} would have picked up the envelope columns silently,
     * and a submission entity interpolated into a log line is exactly how
     * signatures end up in log aggregation.
     */
    @Override
    public String toString() {
        return "TransactionSubmission(id=" + id
                + ", idempotencyKey=" + idempotencyKey
                + ", reference=" + reference
                + ", status=" + status
                + ", failureReason=" + failureReason
                + ", sourceAccount=" + sourceAccount
                + ", sequenceNumber=" + sequenceNumber
                + ", feeStroops=" + feeStroops
                + ", feeBumpCount=" + feeBumpCount
                + ", transactionHash=" + transactionHash
                + ", attempts=" + attempts + ")";
    }
}
