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
import static org.assertj.core.api.Assertions.assertThatCode;
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

    /**
     * The gateway is a signing oracle reachable over the network, so the size
     * check happens <em>before</em> the key lookup: an oversized payload must
     * not even produce a request, let alone reach a key.
     */
    @Test
    void refusesToSendAnythingLargerThanATransactionHash() {
        assertThatThrownBy(() -> provider.sign("channel1", new byte[64]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte transaction hash")
                .hasMessageContaining("64 bytes");
        assertThatThrownBy(() -> provider.sign("channel1", new byte[33]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.sign("channel1", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(server.getRequestCount())
                .withFailMessage("an oversized payload reached the gateway")
                .isZero();
    }

    @Test
    void neverRendersTheApiKey() {
        assertThat(provider.toString()).doesNotContain("gateway-token");
    }

    /**
     * The API key travels on every request and the signature comes back on
     * every response. Neither belongs in a log — the credential for obvious
     * reasons, the response body because a gateway is free to echo the request
     * into an error.
     */
    @Test
    void neitherTheApiKeyNorTheGatewayBodyIsLogged() {
        try (LogCapture logs = LogCapture.start()) {
            enqueueKeyLookup(signingKey);
            enqueueSignature(signingKey, signingKey);
            provider.sign("channel1", MESSAGE);

            server.enqueue(new MockResponse().setResponseCode(500).setBody("gateway-token leaked in an error body"));
            assertThatThrownBy(() -> provider.publicKey("channel2")).isInstanceOf(SigningProviderException.class);

            org.slf4j.LoggerFactory.getLogger(KmsSigningProviderTest.class).info("provider={}", provider);

            assertThat(logs.output()).doesNotContain("gateway-token");
        }
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

    /**
     * Cleartext to a signing gateway leaks the bearer credential and, worse,
     * lets anything on the path substitute the signature that comes back.
     * Loopback is exempt (this test's own MockWebServer, and the sidecar-proxy
     * deployment shape) because there is no network path to observe.
     */
    @Test
    void refusesToStartAgainstAPlaintextGateway() {
        SigningProperties insecure = new SigningProperties();
        insecure.setProvider("kms");
        insecure.getKms().setUrl("http://kms.internal/stellar");
        insecure.getKms().setApiKey("gateway-token");

        assertThatThrownBy(() -> new KmsSigningProvider(new OkHttpClient(), new ObjectMapper(), insecure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use https");

        insecure.getKms().setUrl("https://kms.internal/stellar");
        assertThatCode(() -> new KmsSigningProvider(new OkHttpClient(), new ObjectMapper(), insecure))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesToStartAgainstAnUnauthenticatedGateway() {
        SigningProperties noCredential = new SigningProperties();
        noCredential.setProvider("kms");
        noCredential.getKms().setUrl("https://kms.internal/stellar");

        assertThatThrownBy(() -> new KmsSigningProvider(new OkHttpClient(), new ObjectMapper(), noCredential))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stellar.signing.kms.api-key");
    }

    @Test
    void refusesToStartAgainstAnUnparseableUrl() {
        SigningProperties broken = new SigningProperties();
        broken.setProvider("kms");
        broken.getKms().setUrl("not a url");
        broken.getKms().setApiKey("gateway-token");

        assertThatThrownBy(() -> new KmsSigningProvider(new OkHttpClient(), new ObjectMapper(), broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid http(s) URL");
    }
}
