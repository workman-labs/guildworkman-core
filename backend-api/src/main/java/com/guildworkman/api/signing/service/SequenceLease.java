package com.guildworkman.api.signing.service;

import java.time.Instant;

/**
 * An exclusive hold on one channel account, together with the sequence number
 * allocated from it. Held from the moment a submission is about to be built
 * until that submission reaches a terminal state.
 *
 * @param channelAccountId the pool row backing the lease
 * @param accountId        the Stellar account strkey ({@code G…}) to build the transaction on
 * @param keyRef           alias the {@code SigningProvider} resolves to that account's signing key
 * @param sequenceNumber   the sequence number this transaction must carry; allocated to this lease alone
 * @param expiresAt        when a sweeper may reclaim the lease if the holder never comes back
 */
public record SequenceLease(Long channelAccountId, String accountId, String keyRef, long sequenceNumber,
                            Instant expiresAt) {
}
