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

    @Test
    void signsWithTheConfiguredKeyAndTheSignatureVerifies() {
        KeyPair keyPair = KeyPair.random();
        LocalSigningProvider provider = providerFor("channel1", String.valueOf(keyPair.getSecretSeed()));
        byte[] message = "a 32-byte-ish transaction hash".getBytes(StandardCharsets.UTF_8);

        byte[] signature = provider.sign("channel1", message);

        assertThat(provider.providerId()).isEqualTo("local");
        assertThat(provider.publicKey("channel1")).isEqualTo(keyPair.getAccountId());
        assertThat(KeyPair.fromAccountId(keyPair.getAccountId()).verify(message, signature)).isTrue();
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
