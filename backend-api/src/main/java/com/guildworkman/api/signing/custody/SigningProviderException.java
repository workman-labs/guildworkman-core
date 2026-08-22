package com.guildworkman.api.signing.custody;

/**
 * Raised when a custody backend could not produce a signature — a KMS that is
 * unreachable, a rejected key policy, a malformed signature coming back.
 *
 * <p>Messages are constructed from the key <em>reference</em> and the
 * backend's own error text only; see {@link SecretRedactor} for the
 * belt-and-braces scrub applied to anything derived from an external response
 * before it reaches a log line or a persisted {@code lastError}.
 */
public class SigningProviderException extends RuntimeException {

    public SigningProviderException(String message) {
        super(SecretRedactor.redact(message));
    }

    public SigningProviderException(String message, Throwable cause) {
        super(SecretRedactor.redact(message), cause);
    }
}
