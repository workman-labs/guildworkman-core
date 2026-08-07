# Concurrency-Safe Appointment Booking & Slot Reservation

Implements [#24](https://github.com/workman-labs/guildworkman-core/issues/24):
booking endpoints that cannot double-book a worker under concurrent requests,
plus the per-worker availability read a booking calendar needs.

## The problem

Two things were missing before this change.

**Nothing stopped a double-booking.** `POST /api/v1/client/bookAppointment`
inserted an appointment unconditionally. Two clients posting the same
`skilledWorkerId` and `scheduleTime` at the same moment both succeeded, and the
worker found out later.

**Nothing could see a worker's calendar.** `GET /api/v1/client/viewAllAppointment`
returns the *calling client's own* bookings, so a booking UI had no way to ask
"what is already taken on this worker's calendar?". The calendar in
[guildworkman-web#34](https://github.com/workman-labs/guildworkman-web/pull/34)
worked around that with a client-side lock (`localStorage` + `BroadcastChannel`,
5-minute TTL) in `src/lib/slotLock.ts`. That stops *one* visitor
double-booking themselves across tabs; it cannot stop two *different* visitors
racing, because neither browser can see the other's state. This PR closes that
at the authoritative layer, so the frontend can retire its client-only lock and
treat the server as the source of truth.

## New dependencies

None. Everything here is Spring Data JPA, Bean Validation and springdoc, all
already on the classpath.

## Schema / migrations

No migration file, and none is needed: this codebase has no Flyway/Liquibase and
manages schema through Hibernate's `spring.jpa.hibernate.ddl-auto=update` (see
`application.properties`). The new table is declared the same way every existing
table is — as JPA annotations, source of truth in the entity class:

- `slot_reservations` — `booking/model/SlotReservation.java`, including both
  unique constraints and the three supporting indexes.

Same approach [#22](https://github.com/workman-labs/guildworkman-core/issues/22)
used for `on_chain_events` and
[#21](https://github.com/workman-labs/guildworkman-core/issues/21) used for
`escrow_orchestration_requests`. This PR does not introduce a new
schema-management strategy; it follows the one in place.

**Deploy safety.** `slot_reservations` is an entirely new table, so the first
deploy creates it and every deploy after is a no-op for it. No existing table or
column is altered. Existing appointment rows keep working — see "Appointments
that predate this feature" below.

## How double-booking is prevented

Three mechanisms, in order. The first is the guarantee; the other two exist so
the guarantee stays true as the code changes.

### 1. A per-worker lock, taken before anything is read

`SlotReservationRepository.lockWorkerForBooking` issues
`SELECT id FROM skilled_workers WHERE id = ? FOR UPDATE`. Every writer that
wants to occupy one of that worker's slots takes it first.

The ordering is the entire point. An overlap query run *before* the lock reads a
snapshot from before the competing writer committed — which is exactly how two
concurrent bookings both conclude a slot is free. Taking the lock first means
the second request blocks inside Postgres until the first commits, and only then
runs its own overlap check, by which time it can see what the winner took.

The lock is per worker, so bookings for different workers never contend, and it
is held only across one query and one insert — no network calls inside the
critical section. It cannot deadlock: a booking transaction locks exactly one
worker row and never a second, so no wait-cycle can form between two bookings.

Native SQL projecting only `id` is deliberate — loading the `SkilledWorker`
entity would hydrate its `EAGER` / `cascade = ALL` appointment collection on a
path that only needs a row lock.

### 2. An overlap check, under that lock

`findOccupying` looks for any active reservation overlapping the half-open
interval `[slotStart, slotEnd)`. Half-open means back-to-back slots are fine:
`10:00–11:00` and `11:00–12:00` share only the boundary instant.

This is what catches *partial* overlaps — `10:00–11:00` versus `10:30–11:30` —
which no equality-based constraint can express.

Lapsed holds are filtered out in SQL (`expiresAt is null or expiresAt > now`)
rather than relying on the sweep having run, so a hold that ran out of time stops
blocking the slot immediately.

### 3. A unique index, as a backstop

`SlotReservation.activeSlotKey` holds `"<workerId>@<slotStart>"` while the
reservation occupies the slot, and is set to `NULL` the moment it stops.
Postgres treats `NULL`s in a unique index as distinct, so any number of
released/expired rows can coexist for one slot while at most one active row ever
can.

Under the lock this index should never fire. It is there so that if a future code
path ever claims a slot *without* taking the lock, the database rejects it
loudly (surfacing as `409`) instead of silently double-booking. It can only
catch two claims on the identical start instant; partial overlaps are step 2's
job.

### Why pessimistic rather than optimistic locking

An optimistic (version / compare-and-swap) scheme needs an existing row to
contend over. The race here is between two **inserts** — there is no shared row
whose `@Version` could clash, so a version check has nothing to fail on. The
realistic alternatives were a lock covering the read-then-insert window (what
this does) or the unique index alone. The index alone works for exact-start
collisions, but turns the ordinary "someone got there first" case into a
rolled-back transaction plus an integrity-violation round trip, and cannot
express partial overlaps at all.

## The booking flow

```
POST /reservations            -> 201, status HELD, expires in 5 minutes
POST /reservations/{id}/confirm -> 200, status CONFIRMED, appointmentId set
DELETE /reservations/{id}     -> 200, status RELEASED (early give-back)
```

A hold is authoritative and short-lived: it is what lets a visitor fill in
booking details without racing anyone, and it lapses on its own if they walk
away. `booking.reservation.hold-ttl` defaults to 5 minutes, matching the TTL the
web calendar already used, so the behaviour a visitor sees is unchanged in
duration — only in authority.

Holds are reclaimed two ways, and neither is the only line of defence:

- **Inline**, on the reserve path: after taking the worker lock, this worker's
  lapsed holds are expired before the overlap check runs. A slot is never
  blocked by a dead hold, regardless of when the sweep last ran.
- **In the background**, every `booking.expiry-sweep-delay-ms` (30s), so rows
  don't linger as `HELD` on a quiet calendar and the availability read reflects
  reality without needing someone to attempt a booking first. The sweep claims
  rows with `SELECT ... FOR UPDATE ... ORDER BY id`, so two application
  instances never process the same row and can't deadlock against each other.

### Idempotency

`reserve` dedupes on a caller-supplied `idempotencyKey`: re-sending the same key
returns the original hold rather than consuming a second slot, so a retried HTTP
call or a double-clicked button is harmless. This is signalled by the
`X-Idempotent-Replay: true|false` response header rather than a different status
code, matching `EscrowOrchestrationController` — a replay is not an error
condition.

The subtle case is a *concurrent* retry: the duplicate queues behind the original
on the worker lock, and by the time it runs its overlap check the original has
committed and taken the slot. Left alone that would report `409` — telling the
caller they lost a race against themselves. `SlotReservationService.reserve`
therefore re-reads the idempotency key when a claim fails, and replays the
winner if the row turns out to be the caller's own. Anything else is a genuine
loss and stays a `409`.

`SlotReservationService.reserve` is deliberately **not** `@Transactional`: a
unique violation marks the surrounding transaction rollback-only, so the recovery
read has to happen after that transaction has ended. Keeping the transaction
boundary at `SlotReservationBooker.hold` rather than nesting one (as
`EscrowOrchestrationInserter` does with `REQUIRES_NEW`) also means a booking
never holds two pooled connections at once — which matters here, because the
outer call already holds a row lock for its whole duration.

`confirm` and `release` are idempotent too, and two concurrent confirms of one
hold are serialised by a `PESSIMISTIC_WRITE` lock on the reservation row: the
loser blocks, re-reads a row that is already `CONFIRMED`, and gets the first
confirm's appointment back instead of booking a second one.

## The one-step endpoint goes through the same guard

`POST /api/v1/client/bookAppointment` still works exactly as before for callers
that don't want the two-step flow, but it now claims its slot through
`SlotReservationBooker.claimConfirmed` — same lock, same overlap check, writing a
reservation that is `CONFIRMED` from the start. Leaving it unguarded would have
made it a way *around* the guard, and would have left the availability read
under-reporting.

The claim happens *before* the appointment is saved. That is not just for early
failure: an appointment saved first would show up in its own overlap query and
conflict with itself.

Bookings that name no worker (`skilledWorkerId` is optional) are not guarded,
because there is no calendar to double-book — the appointment records no worker
at all.

## Every path that changes a slot goes through the guard

A guard that only covers *creation* isn't a guard — the other endpoints that
move or free a worker's time have to respect it too, or they become the way
around it.

**Cancelling or deleting** an appointment releases its reservation
(`releaseForAppointment`). Without that, a cancelled appointment would leave a
`CONFIRMED` reservation occupying the calendar forever and nobody could rebook
the time.

**`PUT /updateAppointment` with a `status`** that no longer occupies the slot
(`CANCELLED`, `DECLINED`) releases it for the same reason — declining a job has
to free the worker's time.

**`PUT /updateAppointment` with a `startTime`** is a booking of a *different*
slot, so it claims the new one under the same lock and overlap check and gives
the old one back. If the new time is taken it fails with `409`, and because the
whole update is one transaction, a reschedule that can't get its slot rolls back
rather than half-applying.

The appointment is excluded from its own conflict check when moving: it still
carries its old `scheduleTime` at that point, so a small shift (10:00 → 10:30)
would otherwise be reported as conflicting with itself.

The one asymmetry: re-activating a cancelled appointment (setting it back to
`SCHEDULED`) does not re-claim a reservation. It doesn't create a double-booking
— the appointment row itself is visible to every overlap check via
`findOverlapping` — it just isn't represented in `slot_reservations`.

## Appointments that predate this feature

Rows booked before this shipped have no `slot_reservations` row but still occupy
the worker's calendar. Both the overlap check and the availability read therefore
union reservations with `AppointmentRepository.findOverlapping`.

An `Appointment` records only a `scheduleTime`, no end, so each legacy row is
treated as occupying `[scheduleTime, scheduleTime + booking.reservation.slot-duration)`.
`SCHEDULED`, `ACCEPTED` and `UPDATED` occupy the slot; `CANCELLED` and `DECLINED`
free it.

An appointment already represented by a `CONFIRMED` reservation is reported once,
from the reservation, which is the row that carries the real slot end.

## The availability read

`GET /api/v1/booking/workers/{workerId}/availability?from=&to=` is the
counterpart to `viewAllAppointment` that the calendar was missing.

It deliberately reports what is **taken**, not what is free. Free time is
whatever a worker's published working hours leave over, which is a frontend (and
future working-hours feature) concern; the server is only authoritative about
what is already claimed. A slot missing from the response was unclaimed *at read
time* and still has to be won by reserving it — the read informs the UI, the
reserve call settles the race.

Live holds are reported as `state: "HELD"` with a `holdExpiresAt`, so a calendar
can distinguish "booked" from "someone is mid-checkout, check back shortly".

## Times and timezones

`slotStart`/`slotEnd` are `LocalDateTime` — wall-clock times in the server's
zone, the same convention `Appointment.scheduleTime` already uses. `expiresAt`
and the audit timestamps are `Instant`s, because they are absolute points in
time rather than calendar positions.

## Error contract

Booking errors are RFC 7807 problem JSON like everything else, wired up in
`GlobalExceptionHandler`:

| Exception | Status | `type` slug |
| --- | --- | --- |
| `SlotUnavailableException` | 409 | `slot-unavailable` |
| `ReservationNotHeldException` | 409 | `reservation-not-held` |
| `ReservationNotFoundException` | 404 | `reservation-not-found` |
| `UserNotFoundException` (unknown worker/client) | 404 | `user-not-found` |
| `GuildWorkmanException` (bad window, oversized duration) | 400 | `bad-request` |

Losing a booking race is a `409`, not a `400`: the request was well-formed, it
just arrived second.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/booking/reservations` | Hold a slot (201; `X-Idempotent-Replay` header) |
| `GET` | `/api/v1/booking/reservations/{id}` | Current state of a reservation |
| `POST` | `/api/v1/booking/reservations/{id}/confirm` | Turn a hold into an appointment |
| `DELETE` | `/api/v1/booking/reservations/{id}` | Release a hold early |
| `GET` | `/api/v1/booking/workers/{id}/availability` | A worker's taken slots in a window |

All are documented in OpenAPI (`/v3/api-docs`, `/swagger-ui.html`).

**Access.** `/api/v1/booking/**` is public in `SecurityConfig`, mirroring the
flow it replaces: `/api/v1/client/bookAppointment` is already public, and a
booking calendar has to read a worker's taken slots before anyone signs in.
Tightening the booking surface means tightening the client surface with it,
which is a deliberate auth-scope change rather than something to fold into a
concurrency fix — see "Follow-ups".

## Configuration

| Property | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `booking.reservation.hold-ttl` | `BOOKING_HOLD_TTL` | `PT5M` | How long an unconfirmed hold keeps a slot |
| `booking.reservation.slot-duration` | `BOOKING_SLOT_DURATION` | `PT1H` | Default slot length; also the assumed length of a legacy appointment |
| `booking.reservation.max-slot-duration` | `BOOKING_MAX_SLOT_DURATION` | `PT12H` | Cap on a caller-supplied duration |
| `booking.reservation.max-availability-window` | `BOOKING_MAX_AVAILABILITY_WINDOW` | `P62D` | Widest window one availability call may ask for |
| `booking.reservation.expiry-sweep-batch-size` | `BOOKING_EXPIRY_SWEEP_BATCH_SIZE` | `100` | Rows the sweep claims per pass |
| `booking.expiry-sweep-delay-ms` | `BOOKING_EXPIRY_SWEEP_DELAY_MS` | `30000` | How often the sweep runs |

`booking.expiry-sweep-delay-ms` sits outside the `booking.reservation.*` prefix
on purpose: `@Scheduled` reads it as a raw placeholder, not through
`SlotReservationProperties`. It is pinned high in the Surefire config so the
poller can't race tests through the shared test database, alongside the existing
chain-event and escrow pollers.

## Testing

- `booking/SlotReservationIntegrationTest` — runs against a real Postgres,
  because that is the only place the guarantee lives. A mocked repository would
  happily "pass" while double-booking in production. Covers the 12-thread race
  for one slot (exactly one winner, eleven `409`s), the same race through the
  one-step `bookAppointment` endpoint, per-worker isolation, partial and
  adjacent overlaps, legacy appointments, expiry, cancel/decline/reschedule, and
  the availability read.
- `booking/api/BookingControllerTest` — HTTP contract: status codes, the replay
  header, and problem-JSON shape for every failure.
- `booking/service/SlotReservationServiceTest` — the decisions the service makes
  on its own (replay, race recovery, confirm/release state checks), mocked.
- `booking/api/ReserveSlotRequestValidationTest` — bean-validation rules.

CI already caches Maven dependencies via `setup-java`'s `cache: maven` in
`.github/workflows/test.yml`, and runs the suite against a `postgres:16-alpine`
service container, so these integration tests run there as-is. The background
sweep's poll delay is pinned high in the Surefire config, alongside the existing
chain-event and escrow pollers, so it can't race tests through the shared test
database.

### One unrelated fix these tests needed

`src/main/resources/db/data.sql` (used by `SkilledWorkerServiceTest` via `@Sql`)
seeds rows with hard-coded ids — `address` 101-104, `skilled_workers` 201-204,
`clients` 301-304 — but never realigned the identity sequences afterwards.
Postgres columns declared `GENERATED BY DEFAULT AS IDENTITY` (what Hibernate's
`GenerationType.IDENTITY` produces) don't advance their sequence for an explicit
id, and `TRUNCATE` without `RESTART IDENTITY` doesn't reset it either. So any
later test that lets Hibernate generate an id gets a duplicate-key violation the
moment the counter walks into the seeded range — which is what the booking tests
hit, since they create their own workers and clients rather than reusing the
fixture.

The script now ends with `setval(pg_get_serial_sequence(...), MAX(id))` per
seeded table. It was previously invisible only because CI starts from an empty
database each run and the ordering happened to be favourable.

## Follow-ups (out of scope for this PR)

- **Retire the client-side lock in guildworkman-web.** `src/lib/slotLock.ts` can
  go once the calendar reads `/availability` and reserves through this API.
- **Worker working hours.** The server knows what is *taken*; it has no concept
  of when a worker is *available*. Until that exists, free time is the
  frontend's model.
- **Authentication scope.** Booking is public because the flow it replaces is.
  Requiring a bearer token for reservations (and scoping `clientId` to the
  authenticated principal instead of trusting the body) is a worthwhile change,
  but it has to move `/api/v1/client/**` at the same time.
- **Overlap enforcement in the database itself.** A Postgres `EXCLUDE USING gist`
  constraint over a `tstzrange` would make partial overlaps impossible at the
  schema level rather than under an application-held lock. Hibernate's
  `ddl-auto=update` cannot express it, so it needs the Flyway/Liquibase
  discussion the escrow doc already raises.
