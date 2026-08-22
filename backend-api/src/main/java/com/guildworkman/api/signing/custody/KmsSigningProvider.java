package com.guildworkman.api.signing.custody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guildworkman.api.signing.SigningProperties;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.stellar.sdk.KeyPair;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production custody: this process never sees a private key. Signing is
 * delegated to an external KMS/HSM gateway over HTTP, and only the 32-byte
 * transaction hash crosses the wire.
 *
 * <p>The gateway contract is deliberately small — two endpoints, no vendor
 * types — so it can front AWS KMS, GCP KMS, Vault Transit, an HSM, or a
 * bespoke signer without this class changing:
 *
 * <pre>
 * GET  {url}/keys/{keyRef}   -> {"publicKey": "G..."}
 * POST {url}/sign            &lt;- {"keyRef": "...", "message": "&lt;base64 32-byte hash&gt;", "algorithm": "ed25519"}
 *                            -> {"publicKey": "G...", "signature": "&lt;base64 64-byte signature&gt;"}
 * </pre>
 *
 * <p>Every signature is verified against the key reference's known public key
 * before it's returned. A gateway that is misconfigured (signing with the
 * wrong key, or a stale key after a rotation) therefore fails here, loudly,
 * rather than producing an envelope the network rejects with an opaque
 * {@code txBAD_AUTH} some seconds later.
 *
 * <p>Public keys are cached per key reference for the process's lifetime.
 * They are public data; a rotation of the underlying key material behind a
 * reference requires a restart, which is the safer default — silently picking
 * up a new public key mid-flight would invalidate in-flight transactions
 * signed against the old one.
 */
@Component
@ConditionalOnProperty(name = "stellar.signing.provider", havingValue = "kms")
public class KmsSigningProvider implements SigningProvider {

    public static final String PROVIDER_ID = "kms";

    private static final Logger log = LoggerFactory.getLogger(KmsSigningProvider.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Caps how much of a gateway response is ever logged or put in an exception message. */
    private static final int MAX_LOGGED_BODY_LENGTH = 300;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SigningProperties properties;
    private final Map<String, String> publicKeyCache = new ConcurrentHashMap<>();

    public KmsSigningProvider(OkHttpClient httpClient, ObjectMapper objectMapper, SigningProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        requireSecureGateway(properties.getKms());
        Duration timeout = properties.getKms().getRequestTimeout();
        // Same treatment as SorobanRpcClient: the shared app-wide OkHttpClient
        // bean keeps its own defaults, this copy gets the KMS timeout budget so
        // an unresponsive gateway can't pin a submission worker thread.
        this.httpClient = httpClient.newBuilder()
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
    }

    /**
     * Refuses to start against a gateway this service can't talk to safely.
     *
     * <p>Signing requests carry a bearer credential and come back with
     * signatures; in cleartext both are readable and, worse, modifiable by
     * anything on the path — a substituted response is a substituted
     * signature. TLS is therefore a startup requirement, not a deployment
     * convention, and a missing API key is refused for the same reason: an
     * unauthenticated signing gateway is one anybody who can reach it can use.
     *
     * <p>Loopback is the one exemption, for tests and for a sidecar-terminated
     * mTLS proxy on the same host, where there is no network path to observe.
     */
    private static void requireSecureGateway(SigningProperties.Kms kms) {
        if (kms.getUrl() == null || kms.getUrl().isBlank()) {
            throw new IllegalStateException(
                    "stellar.signing.provider=kms requires stellar.signing.kms.url to be set");
        }
        HttpUrl url = HttpUrl.parse(kms.getUrl());
        if (url == null) {
            throw new IllegalStateException(
                    "stellar.signing.kms.url is not a valid http(s) URL");
        }
        if (!url.isHttps() && !isLoopback(url.host())) {
            throw new IllegalStateException("stellar.signing.kms.url must use https (got '" + url.scheme()
                    + "' to host '" + url.host() + "'); a signing gateway reached in cleartext exposes the API key "
                    + "and lets a network attacker substitute signatures");
        }
        if (kms.getApiKey() == null || kms.getApiKey().isBlank()) {
            throw new IllegalStateException("stellar.signing.provider=kms requires stellar.signing.kms.api-key "
                    + "to be set; an unauthenticated signing gateway will sign for anyone who can reach it");
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Resolves the reference against the gateway. Unlike the local provider —
     * which knows its whole key set up front — this is a network call, so a
     * gateway outage reports as a {@link SigningProviderException} rather than
     * being mistaken for "no such key".
     */
    @Override
    public boolean supports(String keyRef) {
        if (keyRef == null || keyRef.isBlank()) {
            return false;
        }
        try {
            publicKey(keyRef);
            return true;
        } catch (UnknownKeyReferenceException ex) {
            return false;
        }
    }

    @Override
    public String publicKey(String keyRef) {
        String cached = publicKeyCache.get(keyRef);
        if (cached != null) {
            return cached;
        }
        Request request = new Request.Builder()
                .url(baseUrl() + "/keys/" + keyRef)
                .header("Authorization", "Bearer " + properties.getKms().getApiKey())
                .get()
                .build();
        JsonNode body = execute(request, keyRef, "keys");
        String publicKey = text(body, "publicKey");
        if (publicKey == null || publicKey.isBlank()) {
            throw new SigningProviderException(
                    "KMS returned no publicKey for keyRef='" + keyRef + "'");
        }
        publicKeyCache.put(keyRef, publicKey);
        log.info("KMS key resolved keyRef={} publicKey={}", keyRef, publicKey);
        return publicKey;
    }

    @Override
    public byte[] sign(String keyRef, byte[] message) {
        // Checked before the key is even resolved: an oversized payload never
        // reaches the gateway, so it can never reach a key.
        SigningMessages.requireTransactionHash(keyRef, message);
        String expectedPublicKey = publicKey(keyRef);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("keyRef", keyRef);
        payload.put("message", Base64.getEncoder().encodeToString(message));
        payload.put("algorithm", "ed25519");

        Request request = new Request.Builder()
                .url(baseUrl() + "/sign")
                .header("Authorization", "Bearer " + properties.getKms().getApiKey())
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        JsonNode body = execute(request, keyRef, "sign");
        String encodedSignature = text(body, "signature");
        if (encodedSignature == null || encodedSignature.isBlank()) {
            throw new SigningProviderException("KMS returned no signature for keyRef='" + keyRef + "'");
        }

        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException ex) {
            throw new SigningProviderException("KMS returned a non-base64 signature for keyRef='" + keyRef + "'");
        }

        String signingPublicKey = text(body, "publicKey");
        if (signingPublicKey != null && !signingPublicKey.equals(expectedPublicKey)) {
            throw new SigningProviderException("KMS signed keyRef='" + keyRef + "' with an unexpected key: "
                    + "expected " + expectedPublicKey + ", got " + signingPublicKey);
        }
        if (!KeyPair.fromAccountId(expectedPublicKey).verify(message, signature)) {
            throw new SigningProviderException(
                    "KMS signature for keyRef='" + keyRef + "' failed verification against " + expectedPublicKey);
        }
        return signature;
    }

    private String baseUrl() {
        String url = properties.getKms().getUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private JsonNode execute(Request request, String keyRef, String operation) {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (response.code() == 404) {
                throw new UnknownKeyReferenceException(keyRef, PROVIDER_ID);
            }
            if (!response.isSuccessful()) {
                throw new SigningProviderException("KMS " + operation + " for keyRef='" + keyRef + "' failed: HTTP "
                        + response.code() + " " + truncate(responseBody));
            }
            return objectMapper.readTree(responseBody);
        } catch (IOException ex) {
            throw new SigningProviderException(
                    "KMS " + operation + " for keyRef='" + keyRef + "' failed: " + ex.getMessage(), ex);
        }
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_LOGGED_BODY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOGGED_BODY_LENGTH) + "…(truncated)";
    }

    /** The API key stays out of every rendering of this object. */
    @Override
    public String toString() {
        return "KmsSigningProvider(url=" + baseUrl() + ", cachedKeyRefs=" + publicKeyCache.keySet() + ")";
    }
}
