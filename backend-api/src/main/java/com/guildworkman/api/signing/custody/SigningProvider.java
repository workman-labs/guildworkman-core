package com.guildworkman.api.signing.custody;

/**
 * Key custody boundary: the only way the rest of the application ever gets a
 * signature. Implementations hold (or reach) Ed25519 key material and hand
 * back raw signatures for a 32-byte message; <b>no implementation ever
 * returns, logs, or serializes a secret seed</b>, and nothing above this
 * interface can ask for one — there is deliberately no {@code secretSeed()}
 * method to call.
 *
 * <p>Callers address keys by a <em>key reference</em> — a short logical alias
 * such as {@code channel-1} or {@code escrow-admin}. A key reference is not
 * secret: it is safe to store in the database, return in an API response and
 * write to a log. Which concrete key it resolves to is the provider's
 * business, and changes with the deployment (a local seed in development, a
 * KMS/HSM key id in production) without any caller changing.
 *
 * <p>The message passed to {@link #sign} is Stellar's transaction hash
 * (SHA-256 of the signature base, i.e. {@code AbstractTransaction.hash()}),
 * which is what makes a remote KMS implementation possible at all: only 32
 * bytes cross the wire, never the transaction and never the key.
 *
 * @see LocalSigningProvider  development/test custody, seeds from configuration
 * @see KmsSigningProvider    production custody, signing delegated to an external KMS/HSM
 */
public interface SigningProvider {

    /**
     * Stable identifier of the custody backend ({@code local}, {@code kms}),
     * recorded on every submission so an operator can tell which custody
     * backend produced a signature after the fact.
     */
    String providerId();

    /** @return true if this provider can resolve {@code keyRef}. */
    boolean supports(String keyRef);

    /**
     * @return the Ed25519 public key for {@code keyRef} as a Stellar account
     * strkey ({@code G...}). Public by definition — safe to log and return.
     * @throws UnknownKeyReferenceException if {@code keyRef} is not configured
     */
    String publicKey(String keyRef);

    /**
     * Signs {@code message} (a 32-byte transaction hash) with the key behind
     * {@code keyRef}.
     *
     * @return the raw 64-byte Ed25519 signature
     * @throws UnknownKeyReferenceException if {@code keyRef} is not configured
     * @throws SigningProviderException     if the custody backend could not produce a signature
     */
    byte[] sign(String keyRef, byte[] message);
}
