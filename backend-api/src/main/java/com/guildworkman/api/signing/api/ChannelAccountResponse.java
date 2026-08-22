package com.guildworkman.api.signing.api;

import com.guildworkman.api.signing.model.ChannelAccount;

import java.time.Instant;

/**
 * Public view of a pool member. {@code accountId} is a public key and
 * {@code keyRef} an alias — there is nothing secret in this shape, which is
 * what makes a pool-health endpoint safe to expose to operators at all.
 */
public record ChannelAccountResponse(
        Long id,
        String accountId,
        String keyRef,
        String status,
        long nextSequence,
        Long leasedBySubmissionId,
        Instant leasedAt,
        Instant leaseExpiresAt,
        Instant lastSyncedAt,
        Instant createdAt) {

    public static ChannelAccountResponse from(ChannelAccount account) {
        return new ChannelAccountResponse(
                account.getId(),
                account.getAccountId(),
                account.getKeyRef(),
                account.getStatus().name(),
                account.getNextSequence(),
                account.getLeasedBySubmissionId(),
                account.getLeasedAt(),
                account.getLeaseExpiresAt(),
                account.getLastSyncedAt(),
                account.getCreatedAt());
    }
}
