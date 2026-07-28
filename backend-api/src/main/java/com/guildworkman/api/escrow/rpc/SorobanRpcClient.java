package com.guildworkman.api.escrow.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Thin JSON-RPC 2.0 client for the Soroban RPC methods this service needs:
 * submitting a signed transaction and polling its outcome. Transaction
 * envelopes and results are treated as opaque base64 XDR strings — this
 * client never encodes or decodes XDR itself, it only relays what the
 * caller (which builds and signs transactions client-side) hands it.
 *
 * @see <a href="https://developers.stellar.org/docs/data/rpc/api-reference/methods">Soroban RPC methods</a>
 */
@Component
@RequiredArgsConstructor
public class SorobanRpcClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SorobanRpcProperties properties;

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

    private JsonNode call(String method, java.util.function.Consumer<ObjectNode> paramsBuilder) {
        ObjectNode params = objectMapper.createObjectNode();
        paramsBuilder.accept(params);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", method);
        body.set("params", params);

        Request request = new Request.Builder()
                .url(properties.getUrl())
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.body() == null) {
                throw new SorobanRpcException("Soroban RPC returned an empty response for method=" + method);
            }
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new SorobanRpcException("Soroban RPC HTTP " + response.code() + " for method=" + method + ": " + responseBody);
            }

            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("error")) {
                throw new SorobanRpcException("Soroban RPC error for method=" + method + ": " + root.get("error"));
            }
            if (!root.has("result")) {
                throw new SorobanRpcException("Soroban RPC response missing 'result' for method=" + method);
            }
            return root.get("result");
        } catch (IOException ex) {
            throw new SorobanRpcException("Soroban RPC call failed for method=" + method, ex);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
