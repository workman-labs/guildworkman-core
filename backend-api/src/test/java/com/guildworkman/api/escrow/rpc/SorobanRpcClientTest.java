package com.guildworkman.api.escrow.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SorobanRpcClientTest {

    private MockWebServer server;
    private SorobanRpcClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        SorobanRpcProperties properties = new SorobanRpcProperties();
        properties.setUrl(server.url("/").toString());

        client = new SorobanRpcClient(new OkHttpClient(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendTransactionParsesAcceptedResult() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"status\":\"PENDING\",\"hash\":\"abc123\"}}")
                .addHeader("Content-Type", "application/json"));

        SendTransactionResult result = client.sendTransaction("AAAA==");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.hash()).isEqualTo("abc123");
        assertThat(result.isAccepted()).isTrue();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getBody().readUtf8()).contains("\"method\":\"sendTransaction\"").contains("AAAA==");
    }

    @Test
    void getTransactionParsesSuccessResult() {
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"status\":\"SUCCESS\",\"resultXdr\":\"xyz\",\"ledger\":100}}")
                .addHeader("Content-Type", "application/json"));

        GetTransactionResult result = client.getTransaction("hash123");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.ledger()).isEqualTo(100L);
    }

    @Test
    void throwsOnJsonRpcErrorField() {
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32602,\"message\":\"invalid params\"}}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> client.sendTransaction("AAAA=="))
                .isInstanceOf(SorobanRpcException.class)
                .hasMessageContaining("invalid params");
    }

    @Test
    void throwsOnHttpError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> client.getTransaction("hash123"))
                .isInstanceOf(SorobanRpcException.class)
                .hasMessageContaining("500");
    }

    // --- request-XDR log/exception safety -----------------------------------

    @Test
    void exceptionMessageNeverContainsTheFullSignedXdrOnHttpError() {
        String largeXdr = "X".repeat(5000);
        server.enqueue(new MockResponse().setResponseCode(500).setBody("unrelated server error, no echo"));

        assertThatThrownBy(() -> client.sendTransaction(largeXdr))
                .isInstanceOf(SorobanRpcException.class)
                .hasMessageNotContaining(largeXdr);
    }

    @Test
    void exceptionMessageNeverContainsTheFullSignedXdrOnJsonRpcError() {
        String largeXdr = "Y".repeat(5000);
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32602,\"message\":\"invalid params\"}}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> client.sendTransaction(largeXdr))
                .isInstanceOf(SorobanRpcException.class)
                .hasMessageNotContaining(largeXdr);
    }

    @Test
    void aServerResponseThatEchoesTheRequestIsTruncatedInTheExceptionMessage() {
        String largeXdr = "Z".repeat(5000);
        // Simulates a pathological RPC error response that echoes the
        // offending request body back — the response is what gets truncated,
        // not omitted, since it's still useful for debugging real RPC errors.
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request, you sent: " + largeXdr));

        assertThatThrownBy(() -> client.sendTransaction(largeXdr))
                .isInstanceOf(SorobanRpcException.class)
                .hasMessageNotContaining(largeXdr)
                .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(largeXdr.length()));
    }
}
