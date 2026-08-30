# Worker Discovery API

`GET /api/v1/discovery/workers` — the marketplace's front door. Find skilled
workers near a location, filter by skill / category / availability, and get them
back ranked by a documented, configurable blend of proximity, on-chain
reputation and availability, with stable cursor pagination and facet counts for
building filter UI.

Before this endpoint existed a client could only book a worker whose id it
already knew (`GET /api/v1/skilledWorker/findById`). The pre-existing
`GET /api/v1/skilledWorker/nearby` loaded every worker row and filtered them in
application memory with a per-row Haversine — fine for a seed database, a
full-table scan in production. This feature replaces that approach.

## Endpoint

```
GET /api/v1/discovery/workers
      ?latitude=6.5244&longitude=3.3792     (required)
      &radiusKm=10                          (optional, default 10, max 50)
      &skill=wiring                         (optional, case-insensitive exact match on skill name)
      &category=ELECTRICAL                  (optional, Category enum)
      &available=true                       (optional; omitted = no availability filter)
      &size=20                              (optional, default 20, max 50)
      &cursor=<opaque>                      (optional, from a previous response)
```

Public (no bearer token), matching the access level of the flows it sits in
front of — `/api/v1/skilledWorker/**` and `/api/v1/booking/**` are already
public because a booking calendar has to be readable before anyone signs in.

### Response

```jsonc
{
  "results": [
    {
      "workerId": 42,
      "fullName": "Ada Okafor",
      "username": "ada",
      "category": "ELECTRICAL",
      "latitude": 6.53, "longitude": 3.37,
      "distanceKm": 1.84,
      "available": true,
      "reputationScore": 0.86,   // 0..1, materialised — never a live chain read
      "reviewCount": 12,
      "rankScore": 0.79          // the blended score this ordering used
    }
  ],
  "facets": {
    "category": [ { "value": "ELECTRICAL", "count": 7 }, { "value": "PLUMBING", "count": 3 } ],
    "skill":    [ { "value": "wiring", "count": 5 }, { "value": "rewiring", "count": 2 } ]
  },
  "pageInfo": { "size": 20, "hasMore": true, "nextCursor": "eyJzIjowLjc5LC..." }
}
```

Errors follow the app-wide RFC 7807 (`application/problem+json`) contract
(`GlobalExceptionHandler`). A malformed/tampered `cursor` is
`400 invalid-search-cursor`; out-of-range parameters are `400
constraint-violation` with an `errors` map.

OpenAPI: the endpoint and its DTOs are annotated, so `/swagger-ui.html` and
`/v3/api-docs` describe it in full.

## Geo filtering is index-backed

Two-stage, so the distance predicate never forces a full-table scan:

1. **Bounding-box prefilter** — `WorkerSearchCriteria` turns
   `(latitude, longitude, radiusKm)` into a lat/lon box (`GeoBox.around`). The
   query's first predicate is
   `latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLon AND :maxLon`,
   served by the composite index `idx_skilled_workers_geo (latitude, longitude)`
   declared on `SkilledWorker`. Longitude degrees are widened by `1/cos(lat)`;
   near the poles or when the box would wrap the antimeridian it degrades safely
   to the full longitude range (correctness over selectivity in the rare case).
2. **Exact distance** — a Haversine (`GeoDistance`, mirrored in SQL) runs only
   over the rows the box already narrowed to, both to drop the box's corners
   (`distanceKm <= radiusKm`) and to produce `distanceKm` for ranking and the
   response.

`WorkerDiscoveryIndexUsageTest` asserts the query plan uses
`idx_skilled_workers_geo` rather than a sequential scan.

There is no PostGIS / `earthdistance` dependency — the box is plain B-tree range
scanning and the Haversine is plain trigonometry, which keeps the schema change
to "one composite index" and avoids an extension the deploy target may not have.

## Reputation is materialised, never read on the request path

The ranking signal is partly on-chain — the `reputation` contract's `Rating`
aggregate. A synchronous Soroban RPC call on a search request is not acceptable
(latency, and a down RPC endpoint taking search down with it), so:

* `worker_reputation_snapshots` holds a per-worker materialised
  `(rating_count, average_rating, reputation_score, source, refreshed_at)` row.
  The search query `LEFT JOIN`s it and `COALESCE`s a missing/absent score to the
  configured fallback. The request path touches Postgres only.
* `ReputationSnapshotService` refreshes snapshots on a schedule
  (`@Scheduled`, `guildworkman.discovery.reputation.poll-delay-ms`), oldest
  first, a bounded batch per tick.
* The chain read itself goes through `ReputationContractClient` →
  `HttpReputationContractClient`, which calls a **read-model / indexer**
  endpoint (`guildworkman.discovery.reputation.read-model-url`) that projects
  the reputation contract's `Rating` aggregate. This mirrors the decision
  `EscrowReconciliationService` already made for the same reason: reconciling
  against an already-ingested projection rather than hand-encoding
  `LedgerKey` / `ScVal` XDR to issue our own ledger-entry reads.

**Staleness bound.** A snapshot is refreshed once it is older than
`guildworkman.discovery.reputation.staleness-bound` (default `PT15M`). So a
worker's reputation contribution to ranking is at most that stale. This is also
what keeps ranking stable within a paginated scroll (see below).

**Fallback when the read-model is unavailable.** If the client errors or times
out and the worker has no prior snapshot, a `source = FALLBACK` snapshot is
written with `reputation_score = guildworkman.discovery.reputation.fallback-score`
(default `0.5`, i.e. neutral). If a prior snapshot exists it is left in place and
retried on the next tick — a stale real score beats a neutral guess. Search
therefore always has a value and never blocks.

**Score formula.** `reputation_score ∈ [0,1]` is computed at refresh time from
the aggregate:

```
norm   = clamp(averageRating / 5.0, 0, 1)
weight = ratingCount / (ratingCount + shrinkK)      // shrinkK default 5
score  = weight * norm + (1 - weight) * fallbackScore
```

Small samples are shrunk toward the neutral fallback so a worker with one
5-star rating does not outrank a worker with fifty 4.6-star ratings.

## Ranking formula

`WorkerRankingCalculator` — weights are `@ConfigurationProperties`
(`guildworkman.discovery.ranking.*`), not constants in a comparator:

```
proximity    = max(0, 1 - distanceKm / radiusKm)          // 1 at the caller, 0 at the edge
reputation   = reputation_score                            // 0..1, from the snapshot / fallback
availability = available ? 1 : 0

rankScore = (wProx*proximity + wRep*reputation + wAvail*availability)
            / (wProx + wRep + wAvail)
```

Defaults: `proximity-weight = 0.5`, `reputation-weight = 0.3`, `availability-weight = 0.2`. The
divisor normalises `rankScore` into `[0,1]` regardless of the weights chosen, so
an operator can retune weights without the score changing scale. The same
expression is computed in SQL (so ordering and keyset pagination happen in the
database, not in memory) and in `WorkerRankingCalculator` (the reference
implementation, unit-tested); `WorkerRankingCalculatorTest` pins the two
together on sample inputs.

Ordering is `rankScore DESC, workerId ASC` — the `workerId` tie-break makes the
order total and deterministic.

## Cursor pagination is stable

Keyset, not offset. The cursor encodes the last row's `(rankScore, workerId)`;
the next page adds
`WHERE rankScore < :s OR (rankScore = :s AND workerId > :id)` and re-runs the
same ordered query. Consequences:

* **Rows inserted or deleted mid-scroll do not shift the window.** An offset
  query would skip or repeat a worker when a row is added/removed on an earlier
  page; a keyset scan is anchored to a value, not a position.
* **`rankScore` is stable enough to key on for the duration of a scroll.**
  `distanceKm` and `radiusKm` are fixed for a given query; `available` changes
  rarely; `reputation_score` only changes when a snapshot is refreshed, at most
  once per `staleness-bound`. A reputation refresh landing between two page
  fetches can in principle move one worker relative to the cursor — the one
  documented edge, bounded to the small set of workers whose snapshot changed in
  that window, and self-correcting on the next full scroll.

The cursor is base64url(JSON) with a version tag and is validated on the way in
(`CursorCodec`); anything unparseable or from a different version is
`400 invalid-search-cursor`, never a 500 or a silently-ignored parameter.

## Facet counts

`facets.category` and `facets.skill` are returned alongside every page so a
client renders its filter sidebar without extra round-trips. Each facet is
counted over the same filtered set **minus its own dimension** (the category
counts ignore any `category=` filter, the skill counts ignore any `skill=`
filter) — the standard faceted-search behaviour where selecting one value in a
facet doesn't collapse the other options in it. Skill facets are capped at
`guildworkman.discovery.max-skill-facets` (default 25), most-common first.

Facet queries reuse the bounding-box prefilter, so they are index-backed too.

## Schema changes

`ddl-auto=update` (no Flyway/Liquibase in this repo — see
`ESCROW_ORCHESTRATION.md` "Schema / migrations"). All additive:

| Change | Table | Notes |
| --- | --- | --- |
| `+ available BOOLEAN NULL` | `skilled_workers` | null = "not stated", treated as available |
| `+ INDEX idx_skilled_workers_geo (latitude, longitude)` | `skilled_workers` | the bounding-box prefilter |
| `+ INDEX idx_skilled_workers_category (category)` | `skilled_workers` | category facet / filter |
| `+ TABLE worker_reputation_snapshots` | new | `worker_id` PK, materialised Rating aggregate |

Hibernate creates the indexes and table on boot; the column is added nullable.
Nothing is dropped or retyped.

## Configuration

```properties
# Ranking weights (need not sum to 1 — the score is normalised by their total)
guildworkman.discovery.ranking.proximity-weight=0.5
guildworkman.discovery.ranking.reputation-weight=0.3
guildworkman.discovery.ranking.availability-weight=0.2

# Search bounds
guildworkman.discovery.default-radius-km=10
guildworkman.discovery.max-radius-km=50
guildworkman.discovery.default-page-size=20
guildworkman.discovery.max-page-size=50
guildworkman.discovery.max-skill-facets=25

# Reputation materialisation
guildworkman.discovery.reputation.read-model-url=${REPUTATION_READ_MODEL_URL:http://localhost:8081}
guildworkman.discovery.reputation.request-timeout=PT5S
guildworkman.discovery.reputation.staleness-bound=PT15M
guildworkman.discovery.reputation.fallback-score=0.5
guildworkman.discovery.reputation.shrink-k=5
guildworkman.discovery.reputation.refresh-batch-size=100
# Read as a raw @Scheduled placeholder, hence outside the bound group. Set high
# in tests so the poller doesn't race the shared DB (see pom.xml surefire notes).
guildworkman.discovery.reputation.poll-delay-ms=60000
```

## Tests

* `GeoBoxTest` — box math, pole clamp, antimeridian widening.
* `WorkerRankingCalculatorTest` — component maths, weight configurability, clamping, ordering.
* `CursorCodecTest` — round-trip, tamper / wrong-version / garbage rejection.
* `HttpReputationContractClientTest` — MockWebServer: happy path, 404 → empty, 500 / timeout → error.
* `ReputationSnapshotServiceIntegrationTest` — refresh writes ONCHAIN snapshots; read-model down writes FALLBACK; existing snapshot kept on transient failure.
* `WorkerDiscoveryIntegrationTest` — seeded dataset asserting ranked order, geo exclusion, composed filters, facet counts, and a keyset scroll that stays stable across an insert.
* `WorkerDiscoveryIndexUsageTest` — `EXPLAIN` shows `idx_skilled_workers_geo`, not a `Seq Scan`.
