package com.guildworkman.api.discovery;

import com.guildworkman.api.data.constants.Category;
import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.data.repository.SkilledWorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the geo-radius search is index-backed: the bounding-box predicate the
 * discovery query leads with is served by {@code idx_skilled_workers_geo}, not a
 * sequential scan of {@code skilled_workers}.
 *
 * <p>{@code enable_seqscan} is turned off for the transaction before the
 * {@code EXPLAIN} so the planner is forced to reveal whether an index scan is
 * even <i>possible</i> for the predicate — which is the real claim. (With only a
 * few dozen seeded rows the planner would otherwise pick a seq scan on cost
 * grounds regardless of the index.)
 */
@SpringBootTest(properties = {
        "guildworkman.discovery.reputation.poll-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000"
})
@Transactional
class WorkerDiscoveryIndexUsageTest {

    @Autowired private SkilledWorkerRepository workers;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // Transactional test: these rows roll back. No wipe needed — the EXPLAIN
        // assertion forces enable_seqscan off and only cares whether the geo
        // index *can* serve the predicate, not the row count.
        for (int i = 0; i < 60; i++) {
            SkilledWorker w = new SkilledWorker();
            w.setFullName("w" + i);
            w.setUsername("idx-w" + i + "-" + System.nanoTime());
            w.setEmail("idx-w" + i + "-" + System.nanoTime() + "@example.com");
            w.setCategory(Category.ELECTRICAL);
            w.setLatitude(6.0 + (i % 20) * 0.05);
            w.setLongitude(3.0 + (i % 15) * 0.05);
            w.setAvailable(true);
            workers.save(w);
        }
        workers.flush();
        jdbc.execute("ANALYZE skilled_workers");
    }

    @Test
    void boundingBoxPredicateUsesTheGeoIndex() {
        jdbc.execute("SET LOCAL enable_seqscan = off");

        List<String> plan = jdbc.queryForList("""
                EXPLAIN
                SELECT w.id FROM skilled_workers w
                WHERE w.latitude BETWEEN 6.40 AND 6.60
                  AND w.longitude BETWEEN 3.30 AND 3.50
                """, String.class);

        String planText = String.join("\n", plan);
        assertThat(planText).contains("idx_skilled_workers_geo");
        assertThat(planText).doesNotContain("Seq Scan on skilled_workers");
    }
}
