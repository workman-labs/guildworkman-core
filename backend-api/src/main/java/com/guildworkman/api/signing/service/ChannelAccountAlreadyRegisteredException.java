package com.guildworkman.api.signing.service;

/**
 * The account (or the key reference) is already in the pool. Registering it
 * twice would put two rows in charge of one account's sequence number, which
 * is precisely the collision the pool exists to prevent.
 */
public class ChannelAccountAlreadyRegisteredException extends RuntimeException {

    public ChannelAccountAlreadyRegisteredException(String message) {
        super(message);
    }
}
