package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.custody.SigningProvider;
import com.guildworkman.api.signing.custody.SigningProviderException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.stellar.sdk.AbstractTransaction;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.xdr.DecoratedSignature;
import org.stellar.sdk.xdr.Signature;

/**
 * Applies signatures from the active {@link SigningProvider} to a transaction.
 *
 * <p>This is the only place in the application that attaches a signature, and
 * it works entirely in public values: it hands the provider the transaction
 * hash and gets back 64 bytes. No private key ever reaches this class — which
 * is what lets the same code path serve an in-process development key and a
 * remote HSM without knowing which it's talking to.
 *
 * <p>Every signature is verified against the key reference's public key
 * before it is attached. A bad signature discovered here costs nothing; the
 * same signature discovered by the network costs a round trip and comes back
 * as an opaque {@code txBAD_AUTH} that looks like a dozen other problems.
 */
@Component
@RequiredArgsConstructor
public class TransactionSigner {

    private static final Logger log = LoggerFactory.getLogger(TransactionSigner.class);

    private final SigningProvider signingProvider;

    public String providerId() {
        return signingProvider.providerId();
    }

    /** @return the public account ({@code G…}) behind a key reference. */
    public String publicKey(String keyRef) {
        return signingProvider.publicKey(keyRef);
    }

    /**
     * Signs {@code transaction} with {@code keyRef} and attaches the result.
     *
     * @throws SigningProviderException if the custody backend fails, or returns a signature that doesn't verify
     */
    public void sign(AbstractTransaction transaction, String keyRef) {
        byte[] message = transaction.hash();
        byte[] signature = signingProvider.sign(keyRef, message);

        KeyPair publicKeyPair = KeyPair.fromAccountId(signingProvider.publicKey(keyRef));
        if (signature == null || !publicKeyPair.verify(message, signature)) {
            throw new SigningProviderException("Signature produced for keyRef='" + keyRef
                    + "' does not verify against " + publicKeyPair.getAccountId());
        }
        transaction.addSignature(new DecoratedSignature(publicKeyPair.getSignatureHint(), new Signature(signature)));
        log.debug("Transaction signed keyRef={} signer={} provider={}",
                keyRef, publicKeyPair.getAccountId(), signingProvider.providerId());
    }
}
