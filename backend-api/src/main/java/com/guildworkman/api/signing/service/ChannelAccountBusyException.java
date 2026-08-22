package com.guildworkman.api.signing.service;

/**
 * The account is leased to an in-flight submission, so the requested change
 * would strand that transaction's sequence number. Retry once the holding
 * submission reaches a terminal state.
 */
public class ChannelAccountBusyException extends RuntimeException {

    public ChannelAccountBusyException(String message) {
        super(message);
    }
}
