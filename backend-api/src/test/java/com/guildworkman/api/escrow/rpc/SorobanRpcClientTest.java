package com.guildworkman.api.escrow.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.signing.StellarTestFixtures;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stellar.sdk.KeyPair;

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

    // --- simulateTransaction -------------------------------------------------

    @Test
    void simulateTransactionParsesASuccessfulSimulation() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"minResourceFee\":\"12345\","
                        + "\"transactionData\":\"AAAAsoroban==\"}}")
                .addHeader("Content-Type", "application/json"));

        SimulateTransactionResult result = client.simulateTransaction("AAAA==");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.minResourceFee()).isEqualTo(12345L);
        assertThat(result.transactionData()).isEqualTo("AAAAsoroban==");
        assertThat(result.requiresRestore()).isFalse();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getBody().readUtf8()).contains("\"method\":\"simulateTransaction\"");
    }

    /** A simulation error is a normal result, not an RPC failure — the caller decides what it means. */
    @Test
    void simulateTransactionReportsAnErrorWithoutThrowing() {
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"error\":\"HostError: contract panicked\"}}")
                .addHeader("Content-Type", "application/json"));

        SimulateTransactionResult result = client.simulateTransaction("AAAA==");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("contract panicked");
    }

    /** Archived state needs restoring first; the invocation as submitted cannot run. */
    @Test
    void simulateTransactionFlagsARestorePreamble() {
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"transactionData\":\"AAAA\","
                        + "\"restorePreamble\":{\"minResourceFee\":\"100\",\"transactionData\":\"BBBB\"}}}")
                .addHeader("Content-Type", "application/json"));

        SimulateTransactionResult result = client.simulateTransaction("AAAA==");

        assertThat(result.requiresRestore()).isTrue();
        assertThat(result.isSuccess()).isFalse();
    }

    // --- getAccountSequence --------------------------------------------------

    @Test
    void getAccountSequenceDecodesTheAccountLedgerEntry() throws InterruptedException {
        String accountId = KeyPair.random().getAccountId();
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"entries\":[{\"xdr\":\""
                        + StellarTestFixtures.accountLedgerEntryXdr(accountId, 987_654_321L) + "\"}]}}")
                .addHeader("Content-Type", "application/json"));

        assertThat(client.getAccountSequence(accountId)).isEqualTo(987_654_321L);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getBody().readUtf8())
                .contains("\"method\":\"getLedgerEntries\"")
                .contains("\"keys\":[");
    }

    /** soroban-rpc has shipped this field under both names; both must work. */
    @Test
    void getAccountSequenceAcceptsTheDataXdrFieldName() {
        String accountId = KeyPair.random().getAccountId();
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"entries\":[{\"dataXdr\":\""
                        + StellarTestFixtures.accountLedgerEntryXdr(accountId, 42L) + "\"}]}}")
                .addHeader("Content-Type", "application/json"));

        assertThat(client.getAccountSequence(accountId)).isEqualTo(42L);
    }

    /** An unfunded account has no ledger entry — null, so the caller can say so plainly. */
    @Test
    void getAccountSequenceReturnsNullForAnAccountThatIsNotOnChain() {
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"entries\":[]}}")
                .addHeader("Content-Type", "application/json"));

        assertThat(client.getAccountSequence(KeyPair.random().getAccountId())).isNull();
    }

    @Test
    void getAccountSequenceRejectsAnEntryWithoutXdr() {
        server.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"entries\":[{\"lastModifiedLedgerSeq\":7}]}}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> client.getAccountSequence(KeyPair.random().getAccountId()))
                .isInstanceOf(SorobanRpcException.class)
                .hasMessageContaining("without XDR");
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
