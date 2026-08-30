package com.guildworkman.api.discovery.reputation;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpReputationContractClientTest {

    private MockWebServer server;
    private HttpReputationContractClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        ReputationProperties properties = new ReputationProperties();
        properties.setReadModelUrl(server.url("/").toString());
        properties.setRequestTimeout(Duration.ofMillis(500));
        client = new HttpReputationContractClient(new OkHttpClient(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void parsesTheRatingAggregateAndHitsTheExpectedPath() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"workerId\":42,\"ratingCount\":12,\"averageRating\":4.6}"));

        Optional<RatingAggregate> result = client.fetchRating(42);

        assertThat(result).contains(new RatingAggregate(12, 4.6));
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/reputation/ratings/42");
        assertThat(recorded.getMethod()).isEqualTo("GET");
    }

    @Test
    void notFoundMeansUnratedNotFailure() {
        server.enqueue(new MockResponse().setResponseCode(404));
        assertThat(client.fetchRating(7)).isEmpty();
    }

    @Test
    void serverErrorIsAReadException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertThatThrownBy(() -> client.fetchRating(7))
                .isInstanceOf(ReputationReadException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void malformedBodyIsAReadException() {
        server.enqueue(new MockResponse().setBody("{\"workerId\":7}"));
        assertThatThrownBy(() -> client.fetchRating(7))
                .isInstanceOf(ReputationReadException.class);
    }

    @Test
    void timeoutIsAReadException() {
        server.enqueue(new MockResponse()
                .setBody("{\"ratingCount\":1,\"averageRating\":5}")
                .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS));
        assertThatThrownBy(() -> client.fetchRating(7))
                .isInstanceOf(ReputationReadException.class);
    }
}
