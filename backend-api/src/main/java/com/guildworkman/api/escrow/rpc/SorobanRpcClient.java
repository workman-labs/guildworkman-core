package com.guildworkman.api.escrow.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Thin JSON-RPC 2.0 client for the Soroban RPC methods this service needs:
 * submitting a signed transaction and polling its outcome. Transaction
 * envelopes and results are treated as opaque base64 XDR strings — this
 * client never encodes or decodes XDR itself, it only relays what the
 * caller (which builds and signs transactions client-side) hands it.
 *
 * <p>Every call gets its own JSON-RPC {@code id} (a random UUID), reused as
 * a correlation id in logs and exception messages so a single round-trip can
 * be traced end to end. Response/error bodies are truncated before they're
 * logged or embedded in an exception message — a Soroban error payload can
 * echo back the (opaque, but potentially large) request XDR, and this is the
 * only place in the request path where that payload is turned into a plain
 * string that might land in application logs.
 *
 * @see <a href="https://developers.stellar.org/docs/data/rpc/api-reference/methods">Soroban RPC methods</a>
 */
@Component
public class SorobanRpcClient {

    private static final Logger log = LoggerFactory.getLogger(SorobanRpcClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Caps how much of a response/error body is ever logged or placed in an exception message. */
    private static final int MAX_LOGGED_BODY_LENGTH = 500;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SorobanRpcProperties properties;

    public SorobanRpcClient(OkHttpClient httpClient, ObjectMapper objectMapper, SorobanRpcProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        // properties.requestTimeout governs this Soroban-specific client only;
        // the injected OkHttpClient bean is shared app-wide (see AppConfig) and
        // is left with its own defaults for other callers (e.g. PaymentServiceImpl).
        Duration timeout = properties.getRequestTimeout();
        this.httpClient = httpClient.newBuilder()
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
    }

    public SendTransactionResult sendTransaction(String signedTransactionXdr) {
        JsonNode result = call("sendTransaction", params -> params.put("transaction", signedTransactionXdr));
        return new SendTransactionResult(
                textOrNull(result, "hash"),
                textOrNull(result, "status"),
                textOrNull(result, "errorResultXdr"));
    }

    public GetTransactionResult getTransaction(String hash) {
        JsonNode result = call("getTransaction", params -> params.put("hash", hash));
        Long ledger = result.hasNonNull("ledger") ? result.get("ledger").asLong() : null;
        return new GetTransactionResult(
                textOrNull(result, "status"),
                textOrNull(result, "resultXdr"),
                ledger);
    }

    private JsonNode call(String method, Consumer<ObjectNode> paramsBuilder) {
        String requestId = UUID.randomUUID().toString();

        ObjectNode params = objectMapper.createObjectNode();
        paramsBuilder.accept(params);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", requestId);
        body.put("method", method);
        body.set("params", params);

        Request request = new Request.Builder()
                .url(properties.getUrl())
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        log.debug("Soroban RPC request rpcId={} method={}", requestId, method);

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.body() == null) {
                throw new SorobanRpcException("Soroban RPC rpcId=" + requestId + " method=" + method
                        + " returned an empty response");
            }
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new SorobanRpcException("Soroban RPC rpcId=" + requestId + " method=" + method
                        + " HTTP " + response.code() + ": " + truncate(responseBody));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("error")) {
                throw new SorobanRpcException("Soroban RPC rpcId=" + requestId + " method=" + method
                        + " error: " + truncate(root.get("error").toString()));
            }
            if (!root.has("result")) {
                throw new SorobanRpcException("Soroban RPC rpcId=" + requestId + " method=" + method
                        + " response missing 'result'");
            }
            log.debug("Soroban RPC response rpcId={} method={} status={}", requestId, method,
                    textOrNull(root.get("result"), "status"));
            return root.get("result");
        } catch (IOException ex) {
            // includes connect/read/write timeouts, which use the same callTimeout
            // budget above rather than OkHttp's per-phase defaults, so a stuck
            // Soroban RPC endpoint can't pin the calling thread indefinitely.
            String message = "Soroban RPC rpcId=" + requestId + " method=" + method + " call failed: " + ex.getMessage();
            log.warn(message);
            throw new SorobanRpcException(message, ex);
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_LOGGED_BODY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOGGED_BODY_LENGTH) + "…(truncated, " + value.length() + " chars total)";
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
