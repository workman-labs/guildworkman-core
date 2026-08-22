package com.guildworkman.api.signing.custody;

/**
 * The single shape a signing request is ever allowed to take.
 *
 * <p>{@link SigningProvider#sign} is documented as taking a Stellar
 * transaction hash, and every caller in this application passes exactly that.
 * This class turns the documentation into a check, because the difference
 * matters: an Ed25519 signing oracle that accepts arbitrary bytes will sign
 * anything put in front of it, including a hash the caller obtained from
 * somewhere else entirely. Constraining the input to 32 bytes doesn't prove
 * the bytes <em>are</em> our transaction hash, but it removes the class of
 * mistake where a raw envelope, a concatenation, or an attacker-chosen blob
 * reaches a key.
 *
 * <p>Deliberately an {@link IllegalArgumentException} rather than a
 * {@link SigningProviderException}: a wrong-sized message is a programming
 * error in this codebase, not a custody outage, and laundering it into a
 * retryable failure would hide it behind five identical retries.
 */
final class SigningMessages {

    /** SHA-256 of the signature base — see {@code AbstractTransaction.hash()}. */
    static final int TRANSACTION_HASH_LENGTH = 32;

    private SigningMessages() {
    }

    static void requireTransactionHash(String keyRef, byte[] message) {
        if (message == null || message.length != TRANSACTION_HASH_LENGTH) {
            throw new IllegalArgumentException("Refusing to sign for keyRef='" + keyRef + "': a signing request must "
                    + "be a " + TRANSACTION_HASH_LENGTH + "-byte transaction hash, got "
                    + (message == null ? "null" : message.length + " bytes"));
        }
    }
}
