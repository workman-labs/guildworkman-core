package com.guildworkman.api.signing.custody;

import com.guildworkman.api.signing.SigningProperties;
import org.junit.jupiter.api.Test;
import org.stellar.sdk.KeyPair;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSigningProviderTest {

    private static LocalSigningProvider providerFor(String keyRef, String seed) {
        SigningProperties properties = new SigningProperties();
        properties.getLocal().getKeys().put(keyRef, seed);
        LocalSigningProvider provider = new LocalSigningProvider(properties);
        provider.loadKeys();
        return provider;
    }

    /** Exactly 32 bytes — the only length {@link SigningMessages} lets through. */
    private static final byte[] TRANSACTION_HASH =
            "a thirty-two byte transaction ha".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsWithTheConfiguredKeyAndTheSignatureVerifies() {
        KeyPair keyPair = KeyPair.random();
        LocalSigningProvider provider = providerFor("channel1", String.valueOf(keyPair.getSecretSeed()));

        byte[] signature = provider.sign("channel1", TRANSACTION_HASH);

        assertThat(provider.providerId()).isEqualTo("local");
        assertThat(provider.publicKey("channel1")).isEqualTo(keyPair.getAccountId());
        assertThat(KeyPair.fromAccountId(keyPair.getAccountId()).verify(TRANSACTION_HASH, signature)).isTrue();
    }

    /**
     * An Ed25519 key that will sign whatever it is handed is a signing oracle.
     * Constraining the input to a transaction hash doesn't prove the bytes are
     * <em>our</em> hash, but it removes the case where a whole envelope, a
     * concatenation, or an attacker-chosen payload reaches the key at all.
     */
    @Test
    void refusesToSignAnythingThatIsNotATransactionHash() {
        LocalSigningProvider provider = providerFor("channel1", String.valueOf(KeyPair.random().getSecretSeed()));

        assertThatThrownBy(() -> provider.sign("channel1", new byte[64]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte transaction hash")
                .hasMessageContaining("64 bytes");
        assertThatThrownBy(() -> provider.sign("channel1", new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.sign("channel1", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.sign("channel1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsWhichReferencesItCanResolve() {
        LocalSigningProvider provider = providerFor("channel1", String.valueOf(KeyPair.random().getSecretSeed()));

        assertThat(provider.supports("channel1")).isTrue();
        assertThat(provider.supports("channel2")).isFalse();
        assertThat(provider.supports(null)).isFalse();
        assertThat(provider.keyRefs()).containsExactly("channel1");
    }

    @Test
    void unknownReferenceIsRejectedRatherThanSignedWithSomethingElse() {
        LocalSigningProvider provider = providerFor("channel1", String.valueOf(KeyPair.random().getSecretSeed()));

        assertThatThrownBy(() -> provider.sign("nope", new byte[32]))
                .isInstanceOf(UnknownKeyReferenceException.class)
                .hasMessageContaining("nope");
    }

    /**
     * The whole point of the {@link SigningProvider} abstraction is that a seed
     * can't be read back out of it. This asserts the class shape, not just the
     * behaviour: a getter added later would fail here.
     */
    @Test
    void exposesNoMethodThatCouldReturnKeyMaterial() {
        assertThat(SigningProvider.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder("providerId", "supports", "publicKey", "sign");
    }

    @Test
    void neverRendersASeedInItsOwnToString() {
        String seed = String.valueOf(KeyPair.random().getSecretSeed());
        LocalSigningProvider provider = providerFor("channel1", seed);

        assertThat(provider.toString()).contains("channel1").doesNotContain(seed);
        assertThat(SecretRedactor.containsSecret(provider.toString())).isFalse();
    }

    /**
     * strkey parsers habitually quote the input they choked on. Startup must
     * fail — but the message that reaches the log has to name the reference
     * only, and the cause has to be dropped along with it.
     */
    @Test
    void aBadSeedFailsStartupWithoutEchoingTheSeed() {
        String almostASeed = "SBADSEEDSBADSEEDSBADSEEDSBADSEEDSBADSEEDSBADSEEDSBADSEED";
        SigningProperties properties = new SigningProperties();
        properties.getLocal().getKeys().put("channel1", almostASeed);
        LocalSigningProvider provider = new LocalSigningProvider(properties);

        assertThatThrownBy(provider::loadKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel1")
                .hasMessageNotContaining(almostASeed)
                .hasNoCause();
    }

    @Test
    void anEmptySeedFailsStartup() {
        SigningProperties properties = new SigningProperties();
        properties.getLocal().getKeys().put("channel1", "  ");
        LocalSigningProvider provider = new LocalSigningProvider(properties);

        assertThatThrownBy(provider::loadKeys)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel1");
    }

    /**
     * The one that matters most in practice. A seed can be kept out of API
     * responses and out of the database and still end up in log aggregation,
     * where it is retained, indexed and readable by everyone with a dashboard.
     *
     * <p>So this drives the whole lifecycle — load, resolve, sign, render —
     * with the root logger capturing at TRACE, and asserts the seed appears
     * nowhere in any of it. The public key is expected to appear: startup logs
     * it on purpose, and it's public by definition.
     */
    @Test
    void nothingInTheLifecycleEverLogsTheSeed() {
        KeyPair keyPair = KeyPair.random();
        String seed = String.valueOf(keyPair.getSecretSeed());

        try (LogCapture logs = LogCapture.start()) {
            LocalSigningProvider provider = providerFor("channel1", seed);
            provider.publicKey("channel1");
            provider.sign("channel1", TRANSACTION_HASH);
            provider.supports("channel1");
            org.slf4j.LoggerFactory.getLogger(LocalSigningProviderTest.class).info("provider={}", provider);

            assertThat(logs.output())
                    .contains(keyPair.getAccountId())
                    .doesNotContain(seed);
            assertThat(SecretRedactor.containsSecret(logs.output())).isFalse();
        }
    }

    /** The same guarantee on the failure path, where an exception message is the likeliest leak. */
    @Test
    void aRejectedSeedIsNotLoggedEitherByUsOrByTheStrkeyParser() {
        String almostASeed = "SBADSEEDSBADSEEDSBADSEEDSBADSEEDSBADSEEDSBADSEEDSBADSEED";
        SigningProperties properties = new SigningProperties();
        properties.getLocal().getKeys().put("channel1", almostASeed);

        try (LogCapture logs = LogCapture.start()) {
            LocalSigningProvider provider = new LocalSigningProvider(properties);
            assertThatThrownBy(provider::loadKeys).isInstanceOf(IllegalStateException.class);

            assertThat(logs.output()).doesNotContain(almostASeed);
        }
    }

    /** Seeds live in the nested {@code Local} holder precisely so this stays true. */
    @Test
    void propertiesDoNotRenderSeeds() {
        String seed = String.valueOf(KeyPair.random().getSecretSeed());
        SigningProperties properties = new SigningProperties();
        properties.getLocal().getKeys().put("channel1", seed);
        properties.getKms().setApiKey("super-secret-api-key");

        assertThat(properties.getLocal().toString()).contains("channel1").doesNotContain(seed);
        assertThat(properties.getKms().toString()).doesNotContain("super-secret-api-key");
    }
}
