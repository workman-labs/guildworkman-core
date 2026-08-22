package com.guildworkman.api.signing.custody;

/**
 * The requested key reference isn't configured for the active
 * {@link SigningProvider}. Carries the reference (an alias, never key
 * material) so an operator can see which alias is missing from the
 * deployment's configuration.
 */
public class UnknownKeyReferenceException extends RuntimeException {

    private final String keyRef;

    public UnknownKeyReferenceException(String keyRef, String providerId) {
        super("No key configured for keyRef='" + keyRef + "' in signing provider '" + providerId + "'");
        this.keyRef = keyRef;
    }

    public String getKeyRef() {
        return keyRef;
    }
}
