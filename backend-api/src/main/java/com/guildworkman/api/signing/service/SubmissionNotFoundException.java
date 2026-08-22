package com.guildworkman.api.signing.service;

/** No transaction submission with the given id. */
public class SubmissionNotFoundException extends RuntimeException {

    public SubmissionNotFoundException(Long id) {
        super("Transaction submission " + id + " not found");
    }
}
