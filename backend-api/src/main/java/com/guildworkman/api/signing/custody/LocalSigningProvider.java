package com.guildworkman.api.signing.custody;

import com.guildworkman.api.signing.SigningProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.stellar.sdk.KeyPair;

/**
 * Development and test custody: Ed25519 seeds come from configuration
 * ({@code stellar.signing.local.keys.<ref>}, supplied through environment
 * variables) and signing happens in this JVM.
 *
 * <p><b>Not for production.</b> A seed in an environment variable is readable
 * by anything that can read the process environment; production deployments
 * set {@code stellar.signing.provider=kms} and get
 * {@link KmsSigningProvider} instead, where the key never enters this
 * process at all. The two are interchangeable behind {@link SigningProvider}
 * precisely so that swap is a configuration change, not a code change.
 *
 * <p>Seeds are parsed once at startup into {@link KeyPair} instances and the
 * plaintext strings are never held by this class, never logged (startup logs
 * the key <em>references</em> and the derived public keys, both of which are
 * public information) and never handed back out — {@link SigningProvider} has
 * no method that could return one. An unparseable seed fails startup with a
 * message that names only the reference, so a typo'd key can't get echoed
 * into a log by the exception itself.
 */
@Component
@ConditionalOnProperty(name = "stellar.signing.provider", havingValue = "local", matchIfMissing = true)
public class LocalSigningProvider implements SigningProvider {

    public static final String PROVIDER_ID = "local";

    private static final Logger log = LoggerFactory.getLogger(LocalSigningProvider.class);

    private final SigningProperties properties;
    private final Map<String, KeyPair> keyPairs = new LinkedHashMap<>();

    public LocalSigningProvider(SigningProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void loadKeys() {
        properties.getLocal().getKeys().forEach((keyRef, seed) -> {
            if (seed == null || seed.isBlank()) {
                throw new IllegalStateException("Empty secret seed configured for keyRef='" + keyRef + "'");
            }
            try {
                keyPairs.put(keyRef, KeyPair.fromSecretSeed(seed.trim()));
            } catch (RuntimeException ex) {
                // Deliberately drops the cause: strkey parsers are prone to
                // quoting the offending input in their message.
                throw new IllegalStateException(
                        "Invalid Stellar secret seed configured for keyRef='" + keyRef + "'");
            }
        });
        if (keyPairs.isEmpty()) {
            log.warn("Local signing provider active with no keys configured — "
                    + "set stellar.signing.local.keys.<ref> to sign anything");
        } else {
            keyPairs.forEach((keyRef, keyPair) ->
                    log.info("Local signing key loaded keyRef={} publicKey={}", keyRef, keyPair.getAccountId()));
        }
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supports(String keyRef) {
        return keyRef != null && keyPairs.containsKey(keyRef);
    }

    @Override
    public String publicKey(String keyRef) {
        return require(keyRef).getAccountId();
    }

    @Override
    public byte[] sign(String keyRef, byte[] message) {
        SigningMessages.requireTransactionHash(keyRef, message);
        return require(keyRef).sign(message);
    }

    /** @return the key references this provider can resolve (aliases, not key material). */
    public Set<String> keyRefs() {
        return Set.copyOf(keyPairs.keySet());
    }

    private KeyPair require(String keyRef) {
        KeyPair keyPair = keyPairs.get(keyRef);
        if (keyPair == null) {
            throw new UnknownKeyReferenceException(keyRef, PROVIDER_ID);
        }
        return keyPair;
    }

    /** Never renders key material, whatever a caller interpolates this into. */
    @Override
    public String toString() {
        return "LocalSigningProvider(keyRefs=" + keyPairs.keySet() + ")";
    }
}
