package com.guildworkman.api.exceptions;

/** Thrown when registering an email that already has an account (HTTP 409). */
public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String message) {
        super(message);
    }
}
