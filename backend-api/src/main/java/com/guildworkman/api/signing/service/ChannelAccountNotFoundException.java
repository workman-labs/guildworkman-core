package com.guildworkman.api.signing.service;

/** No channel account with the given id. */
public class ChannelAccountNotFoundException extends RuntimeException {

    public ChannelAccountNotFoundException(Long id) {
        super("Channel account " + id + " not found");
    }
}
