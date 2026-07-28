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
   instead of creating a second one.

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
   RPC-level errors (timeouts, `TRY_AGAIN_LATER`) are retried with the same
   capped exponential backoff as the chain-ingestion pipeline, up to
   `MAX_ATTEMPTS` before moving to `DEAD_LETTER`.

5. **Reconciliation reuses the on-chain event ingestion pipeline (#22)
   instead of issuing its own ledger reads.** Reading contract storage
   directly (`getLedgerEntries` against a `ScVal`-keyed `LedgerKey`) has the
   same XDR-encoding problem as decision 1. Rather than inventing that,
   `EscrowReconciliationService` treats a `CONFIRMED` request as corroborated
   once a `PROCESSED` `OnChainEvent` for the same `contractId`, tagged with
   this request's `operationRef` as one of its topics, has been ingested
   through the existing `/api/v1/chain/events` pipeline. A request that stays
   uncorroborated past a configurable grace window
   (`escrow.reconciliation.window`, default 10 minutes) is flagged
   `MISMATCHED` for operator follow-up; this convention (topics carrying the
   affected appointment/escrow id) needs to be honored by whatever indexer
   feeds the ingestion endpoint.

6. **Test-suite scheduler isolation.** While adding the integration test we
   found a pre-existing flake: `@SpringBootTest` classes that don't disable
   scheduling leave their `@Scheduled` pollers running against the *shared*
   test database for the rest of the test JVM's life (Spring caches
   `ApplicationContext`s), racing with whatever test runs next and
   processing its rows out from under it. `ChainEventServiceIntegrationTest`
   already worked around this for itself; `pom.xml`'s `maven-surefire-plugin`
   now sets a 1-hour default for every poller's delay
   (`chain.events.poll-delay-ms`, `escrow.orchestration.*-poll-delay-ms`,
   `escrow.reconciliation.poll-delay-ms`) via `systemPropertyVariables`, so
   only tests that explicitly opt in (via their own
   `@SpringBootTest(properties = …)`, which takes precedence) run a poller at
   all.

## Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/escrow/orchestrations` | Bearer | Submit a signed escrow-contract transaction for orchestration (idempotent) |
| `GET` | `/api/v1/escrow/orchestrations/{id}` | Bearer | Fetch a request's current status |

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `soroban.rpc.url` | `https://soroban-testnet.stellar.org` | Soroban JSON-RPC endpoint |
| `soroban.rpc.request-timeout` | `PT10S` | Per-call HTTP timeout |
| `escrow.orchestration.submit-poll-delay-ms` | `1000` | `submitPending()` poll interval |
| `escrow.orchestration.confirm-poll-delay-ms` | `1000` | `pollSubmitted()` poll interval |
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
