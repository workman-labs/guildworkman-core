package com.guildworkman.api.signing.service;

/**
 * Every channel account in the pool is currently leased (or none is usable).
 * Not an error the caller did anything to cause: the submission stays
 * {@code PENDING} and is retried, so the practical effect of a busy pool is
 * queueing, not failure. A pool that is permanently exhausted wants more
 * accounts registered — see {@code docs/STELLAR_SIGNING.md} ("Operations").
 */
public class NoChannelAccountAvailableException extends RuntimeException {

    public NoChannelAccountAvailableException(String message) {
        super(message);
    }
}
