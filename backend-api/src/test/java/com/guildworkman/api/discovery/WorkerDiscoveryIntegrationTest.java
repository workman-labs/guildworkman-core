package com.guildworkman.api.discovery;

import com.guildworkman.api.data.constants.Category;
import com.guildworkman.api.data.models.Skill;
import com.guildworkman.api.data.models.SkilledWorker;
import com.guildworkman.api.data.repository.SkillRepository;
import com.guildworkman.api.data.repository.SkilledWorkerRepository;
import com.guildworkman.api.discovery.api.WorkerDiscoveryController;
import com.guildworkman.api.discovery.api.WorkerSearchResponse;
import com.guildworkman.api.discovery.api.WorkerSearchResponse.FacetCount;
import com.guildworkman.api.discovery.pagination.SearchCursor;
import com.guildworkman.api.discovery.reputation.ReputationContractClient;
import com.guildworkman.api.discovery.reputation.WorkerReputationSnapshot;
import com.guildworkman.api.discovery.reputation.WorkerReputationSnapshot.ReputationSource;
import com.guildworkman.api.discovery.reputation.WorkerReputationSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises worker discovery end to end against a real Postgres, because the
 * parts that actually matter — the bounding-box + Haversine filter, the SQL
 * ranking blend, and keyset pagination — are database behaviour a mocked
 * repository would happily fake while getting the order wrong.
 */
@SpringBootTest(properties = {
        "guildworkman.discovery.reputation.poll-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000"
})
@Transactional
class WorkerDiscoveryIntegrationTest {

    private static final double BASE_LAT = 6.5;
    private static final double BASE_LON = 3.4;

    @Autowired private WorkerDiscoveryService discoveryService;
    @Autowired private WorkerDiscoveryController controller;
    @Autowired private SkilledWorkerRepository workers;
    @Autowired private SkillRepository skills;
    @Autowired private WorkerReputationSnapshotRepository snapshots;

    @PersistenceContext private EntityManager em;

    // The refresher's chain dependency: never touched on the search path, and
    // mocked here so the background poller (already slowed to an hour) is inert.
    @MockBean private ReputationContractClient reputationContractClient;

    private Long w1, w2, w3, w4, w5;

    @BeforeEach
    void seed() {
        // No table wipe: the test runs in a transaction that rolls back, every
        // seeded worker gets a unique username/email, and every assertion is
        // scoped to a 10 km radius around a point far from any other test's
        // fixtures — so rows from other classes are invisible here and there is
        // no FK-ordering dance to get wrong.
        w1 = worker("near-mid", 1.0, Category.ELECTRICAL, "wiring", true, 0.5);
        w2 = worker("near-top", 2.0, Category.ELECTRICAL, "wiring", true, 0.9);
        w3 = worker("mid-plumb", 5.0, Category.PLUMBING, "piping", true, 0.9);
        w4 = worker("far-away", 30.0, Category.ELECTRICAL, "wiring", true, 1.0);
        w5 = worker("edge-busy", 3.0, Category.ELECTRICAL, "wiring", false, 0.9);

        // The search runs as native SQL, which Hibernate does not auto-flush
        // before. Push the seed rows to the DB connection so the query sees them.
        em.flush();
    }

    private Long worker(String name, double kmNorth, Category category, String skillName,
                        boolean available, double reputationScore) {
        SkilledWorker w = new SkilledWorker();
        w.setFullName(name);
        w.setUsername(name + "-" + System.nanoTime());
        w.setEmail(name + "-" + System.nanoTime() + "@example.com");
        w.setCategory(category);
        w.setLatitude(BASE_LAT + kmNorth / 111.32);
        w.setLongitude(BASE_LON);
        w.setAvailable(available);
        w = workers.save(w);

        Skill skill = new Skill();
        skill.setSkillName(skillName);
        skill.setSkillCategory(category);
        skill.setSkilledWorker(w);
        skills.save(skill);

        WorkerReputationSnapshot snapshot = new WorkerReputationSnapshot(w.getId());
        snapshot.setRatingCount(20);
        snapshot.setAverageRating(reputationScore * 5);
        snapshot.setReputationScore(reputationScore);
        snapshot.setSource(ReputationSource.ONCHAIN);
        snapshot.setRefreshedAt(Instant.now());
        snapshots.save(snapshot);
        return w.getId();
    }

    private WorkerSearchResponse search(String skill, Category category, Boolean available,
                                        int size, SearchCursor cursor) {
        return discoveryService.search(new WorkerSearchCriteria(
                BASE_LAT, BASE_LON, 10.0, skill, category, available, size, cursor));
    }

    private static List<Long> ids(WorkerSearchResponse response) {
        return response.results().stream().map(WorkerSearchResponse.WorkerResult::workerId).toList();
    }

    @Test
    void ranksByTheDocumentedBlendAndExcludesWorkersOutsideTheRadius() {
        WorkerSearchResponse response = search(null, null, null, 10, null);

        // Default weights 0.5/0.3/0.2: w2 (.87) > w1 (.80) > w3 (.72) > w5 (.62); w4 is 30km out.
        assertThat(ids(response)).containsExactly(w2, w1, w3, w5);
        assertThat(ids(response)).doesNotContain(w4);

        WorkerSearchResponse.WorkerResult top = response.results().get(0);
        assertThat(top.workerId()).isEqualTo(w2);
        assertThat(top.distanceKm()).isBetween(1.5, 2.5);
        assertThat(response.results().get(0).rankScore())
                .isGreaterThan(response.results().get(1).rankScore());
    }

    @Test
    void reputationIsServedFromTheSnapshotWithoutAnyChainCall() {
        search(null, null, null, 10, null);
        org.mockito.Mockito.verifyNoInteractions(reputationContractClient);
    }

    @Test
    void skillAndCategoryFiltersCompose() {
        assertThat(ids(search("wiring", null, null, 10, null))).containsExactly(w2, w1, w5);
        assertThat(ids(search(null, Category.ELECTRICAL, null, 10, null))).containsExactly(w2, w1, w5);
        assertThat(ids(search("wiring", Category.PLUMBING, null, 10, null))).isEmpty();
        assertThat(ids(search("WIRING", null, null, 10, null))).containsExactly(w2, w1, w5); // case-insensitive
    }

    @Test
    void availabilityFilterHidesUnavailableWorkers() {
        assertThat(ids(search(null, null, true, 10, null))).containsExactly(w2, w1, w3);
        assertThat(ids(search(null, null, false, 10, null))).containsExactly(w5);
    }

    @Test
    void facetCountsCoverTheFilteredSetAndIgnoreTheirOwnDimension() {
        Map<String, Long> category = asMap(search(null, null, null, 10, null).facets().category());
        assertThat(category).containsEntry("ELECTRICAL", 3L).containsEntry("PLUMBING", 1L);

        Map<String, Long> skill = asMap(search(null, null, null, 10, null).facets().skill());
        assertThat(skill).containsEntry("wiring", 3L).containsEntry("piping", 1L);

        // A category filter must NOT collapse the category facet (it ignores its
        // own dimension) but MUST narrow the skill facet.
        WorkerSearchResponse filtered = search(null, Category.ELECTRICAL, null, 10, null);
        assertThat(asMap(filtered.facets().category())).containsEntry("ELECTRICAL", 3L).containsEntry("PLUMBING", 1L);
        assertThat(asMap(filtered.facets().skill())).containsOnlyKeys("wiring");
    }

    @Test
    void keysetPaginationDoesNotRepeatOrSkipWhenARowIsInsertedMidScroll() {
        WorkerSearchResponse page1 = search(null, null, null, 2, null);
        assertThat(ids(page1)).containsExactly(w2, w1);
        assertThat(page1.pageInfo().hasMore()).isTrue();
        assertThat(page1.pageInfo().nextCursor()).isNotNull();

        // A brand-new, top-ranked worker appears between page fetches. With OFFSET
        // pagination it would shove w1 onto page 2 and repeat it; keyset keys off
        // the last score, so page 2 continues cleanly.
        Long w6 = worker("newcomer", 1.5, Category.ELECTRICAL, "wiring", true, 0.95);
        em.flush();

        WorkerSearchResponse page2 = search(null, null, null, 2,
                decodeCursor(page1.pageInfo().nextCursor()));
        assertThat(ids(page2)).containsExactly(w3, w5);
        assertThat(ids(page2)).doesNotContain(w2, w1, w6);
        assertThat(page2.pageInfo().hasMore()).isFalse();
    }

    @Test
    void aMalformedCursorIsRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> controller.searchWorkers(
                BASE_LAT, BASE_LON, null, null, null, null, null, "not-a-real-cursor"))
                .isInstanceOf(InvalidSearchCursorException.class);
    }

    private SearchCursor decodeCursor(String encoded) {
        return new com.guildworkman.api.discovery.pagination.CursorCodec(new com.fasterxml.jackson.databind.ObjectMapper())
                .decode(encoded);
    }

    private static Map<String, Long> asMap(List<FacetCount> facets) {
        return facets.stream().collect(java.util.stream.Collectors.toMap(FacetCount::value, FacetCount::count));
    }
}
