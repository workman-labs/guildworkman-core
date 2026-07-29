# Transactional Escrow Orchestration Service

Implements [#21](https://github.com/workman-labs/guildworkman-core/issues/21):
a backend orchestration service that submits and confirms escrow-contract
operations over Soroban RPC, with idempotency keys, exactly-once submission
semantics, and reconciliation of on-chain versus off-chain state.

## New dependencies

None. `okhttp3.OkHttpClient` and `com.fasterxml.jackson.databind.ObjectMapper`
were already provided (`AppConfig.okHttpClient()`, Spring Boot's
auto-configured Jackson bean) and are reused for `SorobanRpcClient`.

An official Java/Kotlin SDK for Stellar/Soroban was deliberately **not**
added — see decision 1 below.

## Schema / migrations

There is no migration file, and none is needed: this codebase has no
Flyway/Liquibase (`grep -r flyway\|liquibase backend-api/pom.xml` is empty)
and manages schema entirely via Hibernate's `spring.jpa.hibernate.ddl-auto=update`
(`application.properties`). This PR's tables/constraints are declared the
same way every existing table is — as JPA annotations, source of truth in
the entity classes:

- `escrow_orchestration_requests` — `EscrowOrchestrationRequest.java`,
  including the `uk_escrow_orch_idempotency_key` unique constraint on
  `idempotency_key`.

This is the identical approach issue #22 used for `on_chain_events` /
`chain_event_outbox` (also new tables with a new unique constraint, also no
migration file) — this PR doesn't introduce a new schema-management strategy,
it follows the one already in place.

**Deploy safety under `ddl-auto=update`:** Hibernate will `CREATE TABLE`/`CREATE
CONSTRAINT` for anything missing on startup; it does not drop or destructively
alter existing columns. Since `escrow_orchestration_requests` is an
entirely new table, there's no existing data or column it could conflict
with — the first deploy simply creates it, and every deploy after that is a
no-op for this table. The known limitation of this approach (shared with
every other table in the app, not specific to this PR) is that it can't
express a safe *rename* or *type change* — those still require a hand-written
`ALTER TABLE` and manual coordination, same as they always have here. If the
team wants migration-tracked, reviewable schema changes going forward,
introducing Flyway is a reasonable ask, but it's a cross-cutting change that
affects every existing table, not something to fold into this feature PR.

## Architecture decisions

1. **The service relays opaque, already-signed transaction XDR; it does not
   build or sign transactions itself.** Building a Soroban `InvokeHostFunction`
   transaction (and reading contract storage via `getLedgerEntries`) requires
   encoding Stellar's XDR wire format. There is no official Stellar/Soroban SDK
   published to Maven Central under any Java package we could find (searched
   `org.stellar`, `network.stellar`, `java-stellar-sdk`,
   `stellar-android-sdk` — only `org.stellar:wallet-sdk` and an unrelated
   `org.stellar:core` exist, neither of which builds Soroban invoke
   transactions). Hand-rolling that binary encoding for this PR would be hard
   to get right and impossible to verify without a live network round-trip.
   Instead, callers (who already build and sign transactions client-side, e.g.
   with a wallet) hand this service a signed `TransactionEnvelope` XDR string;
   `SorobanRpcClient` treats it, the returned transaction hash, and the
   `resultXdr` fields as opaque strings passed straight through
   `sendTransaction` / `getTransaction`. This keeps submission, retry/backoff,
   and status polling entirely inside the JVM without needing to decode
   Soroban's wire format.

2. **Idempotency via a unique `idempotency_key` column**, using the same
   nested-transaction insert pattern as `ChainEventInserter` (issue #22):
   `EscrowOrchestrationInserter.insert` runs in `REQUIRES_NEW`, so a
   unique-constraint race aborts only that nested transaction and the caller
   falls back to reading the winning row. Resubmitting the same key (e.g. a
   client-side retry of the REST call) always returns the original request
   instead of creating a second one. See the Javadoc on
   `EscrowOrchestrationInserter` for why this beats an optimistic
   compare-and-swap (a CAS has a read/write race window across concurrent
   requests that only a database unique index can close), and on
   `EscrowOrchestrationRequestRepository.claimNext` for why the
   pessimistic-lock claim used by the submit/poll loops (decision 3) can't
   deadlock against it or against itself.

3. **Exactly-once is achieved compositely, not by one lock:**
   - the idempotency key stops duplicate rows for the same logical request;
   - `submitPending()` claims rows via a `SELECT … FOR UPDATE`-backed query
     (`EscrowOrchestrationRequestRepository.claimNext`, mirroring
     `OnChainEventRepository.claimNext`) and only ever hands a `PENDING` row's
     envelope to `sendTransaction` once per attempt;
   - even if the process crashes between the RPC call succeeding and the row
     being committed, Soroban RPC itself dedupes by the envelope's own hash —
     resubmitting identical XDR comes back `DUPLICATE` with the same hash
     rather than executing twice.

4. **Two independent claim/poll cycles, not one.** `submitPending()` moves
   `PENDING → SUBMITTED` (calls `sendTransaction`); `pollSubmitted()` moves
   `SUBMITTED → CONFIRMED/FAILED` (calls `getTransaction`). Splitting them
   means a slow chain confirmation never blocks new submissions, and each
   phase has its own retry/backoff counter. An on-chain `FAILED` result is
   terminal (not retried) — the envelope's sequence number is consumed the
   moment it lands on a ledger, so resubmitting it can never succeed.
   RPC-level errors (timeouts, `TRY_AGAIN_LATER`) are retried with capped
   exponential backoff *plus jitter* — see "Retry, backoff & observability"
   below — up to `escrow.orchestration.retry.max-attempts` before moving to
   `DEAD_LETTER`.

5. **Reconciliation reuses the on-chain event ingestion pipeline (#22)
   instead of issuing its own ledger reads.** Reading contract storage
   directly (`getLedgerEntries` against a `ScVal`-keyed `LedgerKey`) has the
   same XDR-encoding problem as decision 1. Rather than inventing that,
   `EscrowReconciliationService` treats a `CONFIRMED` request as corroborated
   once a `PROCESSED` `OnChainEvent` for the same `contractId`, tagged with
   this request's `operationRef` as one of its topics, has been ingested
   through the existing `/api/v1/chain/events` pipeline. A request that stays
   uncorroborated past a configurable grace window is flagged `MISMATCHED`
   for operator follow-up — see "Operations" below for tuning and recovery.

6. **Test-suite scheduler isolation.** While adding the integration test we
   found a pre-existing flake: `@SpringBootTest` classes that don't disable
   scheduling leave their `@Scheduled` pollers running against the *shared*
   test database for the rest of the test JVM's life (Spring caches
   `ApplicationContext`s), racing with whatever test runs next and
   processing its rows out from under it. `ChainEventServiceIntegrationTest`
   already worked around this for itself; `pom.xml`'s `maven-surefire-plugin`
   now sets a 1-hour default for every poller's delay
   (`chain.events.poll-delay-ms`, `escrow.orchestration.*-poll-delay-ms`,
   `escrow.reconciliation.poll-delay-ms`) via `systemPropertyVariables`
   (that block carries an inline comment explaining the rationale, so it
   isn't accidentally deleted as dead config), so only tests that explicitly
   opt in (via their own `@SpringBootTest(properties = …)`, which takes
   precedence) run a poller at all.

## Retry, backoff & observability

- **Configurable, not hardcoded.** `EscrowOrchestrationRetryProperties`
  (`escrow.orchestration.retry.*`) binds `maxAttempts`, `baseDelay`,
  `maxDelay` and `jitter`, and is constructor-injected into
  `EscrowOrchestrationService` — tests construct their own instance with
  explicit values instead of depending on a hardcoded constant, and
  production tuning is a config change, not a recompile.
- **Backoff with jitter.** Delay doubles from `baseDelay` per attempt, capped
  at `maxDelay`, then randomized by `± jitter` (a fraction of the capped
  delay — default `0.2`, i.e. ±20%) so a batch of requests that failed
  together don't all retry in the same instant and hammer Soroban RPC again.
  See `EscrowOrchestrationService.nextAttemptAt`.
- **RPC timeouts.** `SorobanRpcClient` builds its own `OkHttpClient` from the
  shared bean with `callTimeout`/`connectTimeout`/`readTimeout`/`writeTimeout`
  all set to `soroban.rpc.request-timeout` (default 10s) — a stuck Soroban RPC
  endpoint can't pin the calling thread (and, transitively, the pessimistic
  lock it's holding via `claimNext`) indefinitely.
- **Structured logs, not metrics.** Every state transition
  (request created, submitted, confirmed, on-chain failure, retry scheduled,
  DEAD_LETTER, reconciliation mismatch) is logged at INFO/WARN with the
  orchestration request id, so `grep`/log-search on that id reconstructs a
  request's full history. RPC failures are logged with
  `ex.getClass().getSimpleName()` so timeout vs. other `SorobanRpcException`
  causes are distinguishable. We deliberately **did not** add Micrometer
  counters/gauges in this PR: there's no existing Actuator/Micrometer
  dependency or instrumentation anywhere else in this codebase (`grep -r
  micrometer backend-api/pom.xml` is empty), and introducing one is a
  cross-cutting infra decision (new dependency, `/actuator` exposure surface,
  security implications) that deserves its own discussion rather than being
  smuggled into a feature PR. Happy to follow up with that as its own PR if
  wanted.
- **Log payload safety.** `SorobanRpcClient` correlates every JSON-RPC call
  with a random request id (also sent as the JSON-RPC `id`), logged and
  included in any exception message, and truncates response/error bodies to
  500 characters before they're logged or embedded in a message. The
  *outgoing* signed XDR is never itself logged or placed in an exception
  message (only `method`/`rpcId` are) — `SorobanRpcClientTest` asserts a
  large signed XDR never appears untruncated across the HTTP-error,
  JSON-RPC-error, and IOException paths, including the pathological case of
  a server response that echoes the request back. `SubmitOrchestrationRequest.signedTransactionXdr`
  is also capped at 8192 chars and validated as base64 at the API boundary,
  bounding both the size of what could ever reach those logs and the size of
  an oversized/malicious request body in general.
- **No circuit breaker / rate limiter around `SorobanRpcClient`, deliberately,
  for now.** Same reasoning as the metrics decision above: there's no
  Resilience4j (or similar) dependency or circuit-breaker pattern anywhere
  else in this codebase, and introducing one is a cross-cutting infra choice
  that deserves its own discussion. What's already in place mitigates the
  immediate risk without it: a single unhealthy request can't retry forever
  (bounded by `retry.max-attempts`, then `DEAD_LETTER`), can't hang a thread
  indefinitely (bounded by `soroban.rpc.request-timeout`), and the
  claim-one-row-per-tick shape of `submitPending`/`pollSubmitted` naturally
  throttles how many requests hit an unhealthy endpoint concurrently — it's
  not a token-bucket rate limit, but it's not unbounded parallel retries
  either. A circuit breaker would mainly help by *failing fast* across
  requests once RPC is known-down, rather than each request independently
  discovering that; worth adding if Soroban RPC outages turn out to be
  frequent enough to matter in practice.

## API behavior

- **`POST /api/v1/escrow/orchestrations` always returns `202 Accepted`**,
  whether the `idempotencyKey` was new or already existed. The response body
  is always the canonical current state of the request (its current
  `status`, not just what was just submitted). Which case happened is
  signalled by the `X-Idempotent-Replay` response header (`true`/`false`)
  rather than a different status code — a replay isn't an error, so
  overloading `409`/`200` vs `201` for it seemed more likely to confuse
  clients than help them. See `EscrowOrchestrationController`.
- **`GET /api/v1/escrow/orchestrations/{id}`** returns the request's
  *current-state* timestamps (`submittedAt`, `confirmedAt`, `reconciledAt`)
  and `attempts` count. It is **not** a per-attempt audit log — e.g. if a
  request retried 3 times before submitting, you get `attempts: 3` but not
  the timestamp or error of each individual attempt. That level of detail is
  only in application logs (see above), correlated by orchestration request
  id and, once `SUBMITTED`, by the Soroban RPC request id in
  `SorobanRpcClient`'s log lines. Adding a persisted per-attempt history
  table is a reasonable follow-up if operators need it queryable rather than
  grep-able.

## Operations

- **Tuning the reconciliation window** (`escrow.reconciliation.window`,
  default 10 minutes): this is how long a `CONFIRMED` request can go without
  a corroborating on-chain event before being flagged `MISMATCHED`. It
  should be set comfortably above the on-chain event indexer's normal
  ingestion lag (ledger close time + indexer processing + the ingestion
  pipeline's own retry/backoff — see `ChainEventService.MAX_ATTEMPTS`
  and its backoff). Setting it too low produces false-positive `MISMATCHED`
  flags under normal indexer lag; too high delays real drift detection.
  `escrow.reconciliation.poll-delay-ms` (default 5s) is how often the sweep
  itself runs and can be tuned independently — it doesn't affect the window.
- **If the ingestion pipeline lags or restarts:** `MISMATCHED` is a terminal
  status — `reconcilePending()` only ever selects rows still `PENDING`
  (`findByStatusAndReconciliationStatus`), so once flagged it will not
  silently self-heal even after the indexer catches up and the corroborating
  event eventually appears. Recovery is an explicit step: confirm the missing
  on-chain event now exists (`GET /api/v1/chain/events` or a direct query),
  then call `POST /api/v1/escrow/orchestrations/{id}/requeue-reconciliation`
  (ADMIN only) to reset it back to `PENDING` so the next sweep reconsiders
  it — 409 if the request isn't currently `MISMATCHED`. That endpoint wraps
  exactly this update:
  ```sql
  UPDATE escrow_orchestration_requests
  SET reconciliation_status = 'PENDING', reconciled_at = NULL
  WHERE id = :id;
  ```
  which remains a valid manual fallback if direct database access is what's
  on hand.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/escrow/orchestrations` | Bearer | Submit a signed escrow-contract transaction for orchestration (idempotent) |
| `GET` | `/api/v1/escrow/orchestrations/{id}` | Bearer | Fetch a request's current status |
| `POST` | `/api/v1/escrow/orchestrations/{id}/requeue-reconciliation` | Bearer + ADMIN | Reset a `MISMATCHED` request back to `PENDING` for the next reconciliation sweep |

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `soroban.rpc.url` | `https://soroban-testnet.stellar.org` | Soroban JSON-RPC endpoint |
| `soroban.rpc.request-timeout` | `PT10S` | Per-call HTTP timeout (connect/read/write/call, all capped the same) |
| `escrow.orchestration.submit-poll-delay-ms` | `1000` | `submitPending()` poll interval |
| `escrow.orchestration.confirm-poll-delay-ms` | `1000` | `pollSubmitted()` poll interval |
| `escrow.orchestration.retry.max-attempts` | `5` | Attempts before a request moves to `DEAD_LETTER` |
| `escrow.orchestration.retry.base-delay` | `PT1S` | Backoff for attempt 1; doubles each subsequent attempt |
| `escrow.orchestration.retry.max-delay` | `PT64S` | Ceiling on the (pre-jitter) computed backoff |
| `escrow.orchestration.retry.jitter` | `0.2` | ± fraction of the computed backoff to randomize by |
| `escrow.reconciliation.window` | `PT10M` | Grace period before an uncorroborated `CONFIRMED` request is flagged `MISMATCHED` |
| `escrow.reconciliation.poll-delay-ms` | `5000` | Reconciliation sweep interval |

## Follow-ups (out of scope for this PR)

- Wiring an actual on-chain indexer to populate `/api/v1/chain/events` for
  the escrow contract (issue #22 shipped the ingestion pipeline itself, not
  an indexer).
- Milestone-escrow operations beyond `RELEASE_MILESTONE_FUNDS`
  (`add_milestone`, `approve_milestone`, `raise_milestone_dispute`,
  `resolve_milestone_dispute`) — the same orchestration machinery applies,
  just more `EscrowOperationType` values.
- Micrometer metrics (counters/gauges) if the team wants them, once
  Actuator/Micrometer is introduced app-wide.
- A circuit breaker / rate limiter around `SorobanRpcClient` (e.g.
  Resilience4j) if Soroban RPC outages prove frequent enough that failing
  fast across requests is worth the new dependency.
- A persisted per-attempt audit trail, if grepping logs proves insufficient
  operationally.
