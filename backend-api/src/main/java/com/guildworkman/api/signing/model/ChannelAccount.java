package com.guildworkman.api.signing.model;

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
 * One Stellar account in the channel-account pool, plus the lease that makes
 * concurrent submission safe.
 *
 * <p><b>Why channel accounts at all.</b> Stellar requires a transaction's
 * sequence number to be exactly the source account's current sequence plus
 * one. Submitting from a single account therefore serializes every
 * transaction the backend sends, and one transaction stuck in the mempool
 * blocks every transaction behind it. Submitting from a single account
 * <em>in parallel</em> is worse: the second transaction to reach a validator
 * with the same number is rejected {@code txBAD_SEQ}. A pool of accounts —
 * each leased to at most one in-flight transaction — buys real parallelism
 * (pool size) while keeping each account's sequence strictly serial.
 *
 * <p><b>The invariant this row exists to hold:</b> a {@code LEASED} account
 * has exactly one submission allowed to use {@link #nextSequence}, and the
 * lease is only released once that submission reaches a terminal state.
 * {@code ChannelAccountLeaseService} takes the lease with
 * {@code SELECT … FOR UPDATE SKIP LOCKED}, so N concurrent allocators take N
 * different accounts instead of queueing on one.
 *
 * <p>{@link #nextSequence} is incremented at lease time, not at confirmation
 * time, so two submissions can never be handed the same number even if the
 * first one is still in flight. The correction for a transaction that never
 * lands is {@link ChannelAccountStatus#NEEDS_RESYNC} — see
 * {@code ChannelAccountLeaseService#release}.
 *
 * <p>No key material lives here: {@link #keyRef} is an alias resolved by the
 * active {@code SigningProvider}, and {@link #accountId} is a public key.
 */
@Entity
@Table(name = "stellar_channel_accounts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_stellar_channel_account_id", columnNames = "account_id"),
                @UniqueConstraint(name = "uk_stellar_channel_key_ref", columnNames = "key_ref")
        },
        indexes = @Index(name = "idx_stellar_channel_status", columnList = "status"))
@Getter
@Setter
@NoArgsConstructor
public class ChannelAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stellar account strkey ({@code G…}) — the transaction source account. Public information. */
    @Column(name = "account_id", nullable = false, length = 56, updatable = false)
    private String accountId;

    /** Alias the {@code SigningProvider} resolves to this account's signing key. Never a secret. */
    @Column(name = "key_ref", nullable = false, length = 64)
    private String keyRef;

    /** Sequence number the next transaction built on this account will carry. */
    @Column(name = "next_sequence", nullable = false)
    private long nextSequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelAccountStatus status = ChannelAccountStatus.NEEDS_RESYNC;

    /** Submission currently holding the lease, or {@code null} when free. */
    @Column(name = "leased_by_submission_id")
    private Long leasedBySubmissionId;

    @Column(name = "leased_at")
    private Instant leasedAt;

    /**
     * When the sweeper may reclaim a lease whose holder died. Set from
     * {@code stellar.signing.lease-ttl} at lease time.
     */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    public boolean isLeasable() {
        return status == ChannelAccountStatus.AVAILABLE || status == ChannelAccountStatus.NEEDS_RESYNC;
    }

    /**
     * Every field here is public information, but this is written out by hand
     * anyway so that the property holds by construction rather than by
     * inspection — the same rule {@code TransactionSubmission} follows.
     */
    @Override
    public String toString() {
        return "ChannelAccount(id=" + id
                + ", accountId=" + accountId
                + ", keyRef=" + keyRef
                + ", status=" + status
                + ", nextSequence=" + nextSequence
                + ", leasedBySubmissionId=" + leasedBySubmissionId + ")";
    }
}
