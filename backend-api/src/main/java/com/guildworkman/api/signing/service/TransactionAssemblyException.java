package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.custody.SecretRedactor;

/**
 * The caller's envelope could not be turned into a submittable transaction —
 * unparseable XDR, a fee-bump envelope where a plain transaction was
 * expected, or no operations to execute. Always the request's fault, never
 * the network's, so it is terminal rather than retried.
 */
public class TransactionAssemblyException extends RuntimeException {

    public TransactionAssemblyException(String message) {
        super(SecretRedactor.redact(message));
    }

    public TransactionAssemblyException(String message, Throwable cause) {
        super(SecretRedactor.redact(message), cause);
    }
}
