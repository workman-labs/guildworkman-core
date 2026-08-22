package com.guildworkman.api.signing.custody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.signing.SigningProperties;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stellar.sdk.KeyPair;

import java.io.IOException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The KMS provider against a stubbed gateway. What's under test is the
 * contract this service depends on holding: the hash goes out, a signature
 * comes back, and anything that isn't a signature by the expected key is
 * refused here rather than by the network.
 */
class KmsSigningProviderTest {

    private static final byte[] MESSAGE = "transaction-hash-stand-in-32byte".getBytes();

    private MockWebServer server;
    private SigningProperties properties;
    private KmsSigningProvider provider;
    private KeyPair signingKey;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        signingKey = KeyPair.random();
        properties = new SigningProperties();
        properties.setProvider("kms");
        properties.getKms().setUrl(server.url("/stellar").toString());
        properties.getKms().setApiKey("gateway-token");

        provider = new KmsSigningProvider(new OkHttpClient(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void enqueueKeyLookup(KeyPair keyPair) {
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{\"publicKey\":\"" + keyPair.getAccountId() + "\"}"));
    }

    private void enqueueSignature(KeyPair signer, KeyPair reportedAs) {
        String signature = Base64.getEncoder().encodeToString(signer.sign(MESSAGE));
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{\"publicKey\":\"" + reportedAs.getAccountId() + "\",\"signature\":\"" + signature + "\"}"));
    }

    @Test
    void resolvesAndCachesAPublicKey() throws InterruptedException {
        enqueueKeyLookup(signingKey);

        assertThat(provider.publicKey("channel1")).isEqualTo(signingKey.getAccountId());
        assertThat(provider.publicKey("channel1")).isEqualTo(signingKey.getAccountId());

        RecordedRequest lookup = server.takeRequest();
        assertThat(lookup.getPath()).isEqualTo("/stellar/keys/channel1");
        assertThat(lookup.getHeader("Authorization")).isEqualTo("Bearer gateway-token");
        // Cached: the second call never reached the gateway.
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void signsThroughTheGatewayAndSendsOnlyTheMessage() throws InterruptedException {
        enqueueKeyLookup(signingKey);
        enqueueSignature(signingKey, signingKey);

        byte[] signature = provider.sign("channel1", MESSAGE);

        assertThat(KeyPair.fromAccountId(signingKey.getAccountId()).verify(MESSAGE, signature)).isTrue();

        server.takeRequest(); // the key lookup
        RecordedRequest signRequest = server.takeRequest();
        assertThat(signRequest.getPath()).isEqualTo("/stellar/sign");
        assertThat(signRequest.getMethod()).isEqualTo("POST");
        String body = signRequest.getBody().readUtf8();
        assertThat(body)
                .contains("\"keyRef\":\"channel1\"")
                .contains("\"algorithm\":\"ed25519\"")
                .contains(Base64.getEncoder().encodeToString(MESSAGE));
        assertThat(SecretRedactor.containsSecret(body)).isFalse();
    }

    /**
     * A gateway signing with the wrong key (a botched rotation, a mixed-up
     * reference) is caught here. Letting it through would surface seconds
     * later as an opaque {@code txBAD_AUTH} from the network, having already
     * spent a sequence number to find out.
     */
    @Test
    void rejectsASignatureFromAnUnexpectedKey() {
        KeyPair other = KeyPair.random();
        enqueueKeyLookup(signingKey);
        enqueueSignature(other, other);

        assertThatThrownBy(() -> provider.sign("channel1", MESSAGE))
                .isInstanceOf(SigningProviderException.class)
                .hasMessageContaining("unexpected key");
    }

    /** A gateway that lies about which key it used is still caught by verification. */
    @Test
    void rejectsASignatureThatDoesNotVerify() {
        KeyPair other = KeyPair.random();
        enqueueKeyLookup(signingKey);
        enqueueSignature(other, signingKey);

        assertThatThrownBy(() -> provider.sign("channel1", MESSAGE))
                .isInstanceOf(SigningProviderException.class)
                .hasMessageContaining("failed verification");
    }

    @Test
    void aMissingKeyIsAnUnknownReferenceNotAnOutage() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"error\":\"no such key\"}"));

        assertThatThrownBy(() -> provider.publicKey("channel1"))
                .isInstanceOf(UnknownKeyReferenceException.class)
                .hasMessageContaining("channel1");
    }

    @Test
    void supportsDistinguishesAnUnknownKeyFromAGatewayFailure() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody(""));
        assertThat(provider.supports("channel1")).isFalse();

        server.enqueue(new MockResponse().setResponseCode(503).setBody("gateway down"));
        assertThatThrownBy(() -> provider.supports("channel2"))
                .isInstanceOf(SigningProviderException.class);
    }

    @Test
    void aGatewayErrorBodyIsTruncatedBeforeItReachesTheMessage() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("x".repeat(5000)));

        assertThatThrownBy(() -> provider.publicKey("channel1"))
                .isInstanceOf(SigningProviderException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("(truncated)")
                .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(1000));
    }

    @Test
    void aNonBase64SignatureIsRejected() {
        enqueueKeyLookup(signingKey);
        server.enqueue(new MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("{\"signature\":\"not base64 at all!!\"}"));

        assertThatThrownBy(() -> provider.sign("channel1", MESSAGE))
                .isInstanceOf(SigningProviderException.class)
                .hasMessageContaining("non-base64");
    }

    @Test
    void neverRendersTheApiKey() {
        assertThat(provider.toString()).doesNotContain("gateway-token");
    }

    /** Misconfiguration should stop the context starting, not fail on the first signature. */
    @Test
    void refusesToStartWithoutAGatewayUrl() {
        SigningProperties unconfigured = new SigningProperties();
        unconfigured.setProvider("kms");

        assertThatThrownBy(() -> new KmsSigningProvider(new OkHttpClient(), new ObjectMapper(), unconfigured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stellar.signing.kms.url");
    }
}
