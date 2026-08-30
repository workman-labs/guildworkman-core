package com.guildworkman.api.discovery.reputation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * {@link ReputationContractClient} backed by an HTTP read-model / indexer that
 * projects the reputation contract's {@code Rating} aggregate:
 *
 * <pre>{@code GET {read-model-url}/api/v1/reputation/ratings/{workerId}
 *   200 -> {"workerId":42,"ratingCount":12,"averageRating":4.6}
 *   404 -> worker not rated yet  (Optional.empty)
 *   other / timeout / unparseable -> ReputationReadException}</pre>
 *
 * <p>Reading the aggregate straight off the chain would mean encoding a
 * contract-data {@code LedgerKey} and decoding {@code ScVal} XDR by hand — the
 * very thing {@code SorobanRpcClient} is scoped to avoid and that
 * {@code EscrowReconciliationService} chose an already-ingested projection over.
 * Worker discovery makes the same call: consume a projection, not raw XDR.
 *
 * <p>Uses the shared {@link OkHttpClient} bean (see {@code AppConfig}) with a
 * per-call timeout so a stuck read-model can't pin the scheduler thread.
 */
@Component
public class HttpReputationContractClient implements ReputationContractClient {

    private static final Logger log = LoggerFactory.getLogger(HttpReputationContractClient.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReputationProperties properties;

    public HttpReputationContractClient(OkHttpClient httpClient, ObjectMapper objectMapper,
                                        ReputationProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        Duration timeout = properties.getRequestTimeout();
        this.httpClient = httpClient.newBuilder()
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
    }

    @Override
    public Optional<RatingAggregate> fetchRating(long workerId) {
        HttpUrl base = HttpUrl.parse(properties.getReadModelUrl());
        if (base == null) {
            throw new ReputationReadException("guildworkman.discovery.reputation.read-model-url is not a valid URL: "
                    + properties.getReadModelUrl());
        }
        HttpUrl url = base.newBuilder()
                .addPathSegments("api/v1/reputation/ratings")
                .addPathSegment(Long.toString(workerId))
                .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() == 404) {
                return Optional.empty();
            }
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new ReputationReadException("reputation read-model HTTP " + response.code()
                        + " for worker " + workerId);
            }
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.hasNonNull("ratingCount") || !root.hasNonNull("averageRating")) {
                throw new ReputationReadException("reputation read-model response for worker " + workerId
                        + " missing ratingCount/averageRating");
            }
            return Optional.of(new RatingAggregate(root.get("ratingCount").asInt(),
                    root.get("averageRating").asDouble()));
        } catch (IOException ex) {
            log.warn("reputation read-model call failed for worker {}: {}", workerId, ex.getMessage());
            throw new ReputationReadException("reputation read-model call failed for worker " + workerId, ex);
        }
    }
}
