# GuildWorkman API

![Test](https://github.com/workman-labs/guildworkman-core/actions/workflows/test.yml/badge.svg)

Spring Boot backend for **GuildWorkman**, a marketplace that connects clients
with skilled workers (electricians, plumbers, barbers, beauticians,
carpenters, fashion designers, photographers, and more) for booked
appointments and consultations, with in-app payments and reviews.

> **Monorepo layout.** This repository now also houses the Soroban smart
> contracts (previously `guildworkman-contracts`):
> - `src/`, `pom.xml` — the Spring Boot API (this document).
> - `soroban/` — the Rust/Soroban on-chain escrow & reputation contracts
>   (see `soroban/README.md`).
>
> CI is path-filtered: backend changes run the Maven workflows, `soroban/**`
> changes run `soroban-ci`. See `MIGRATION.md` for how the merge was done.

## Table of contents

- [Project ecosystem](#project-ecosystem)
- [Tech stack](#tech-stack)
- [Architecture](#architecture)
- [Domain model](#domain-model)
- [Features](#features)
- [Not yet wired up](#not-yet-wired-up)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Running locally](#running-locally)
- [Running with Docker](#running-with-docker)
- [API reference](#api-reference)
- [Testing](#testing)
- [Deployment](#deployment)
- [Branching](#branching)
- [Contributing](#contributing)

## Project ecosystem

GuildWorkman lives in two repositories:

| Repo | Role |
|---|---|
| **`guildworkman-core`** (this repo) | The Spring Boot backend (`backend-api/`, documented here) **and** the Soroban smart contracts (`soroban-contracts/`) — on-chain escrow, reputation, and loyalty rewards, designed to be called *from* this backend but not yet integrated (see [Not yet wired up](#not-yet-wired-up)). |
| [`guildworkman-web`](https://github.com/workman-labs/guildworkman-web) | Next.js frontend that talks to this API over REST. |

## Tech stack

- **Java 17**, **Spring Boot 3.3.2**
- **Spring Web**, **Spring Data JPA**, **Spring Security**, **Lombok**
- **PostgreSQL** (`org.postgresql:postgresql`)
- **Auth0 java-jwt** — JWT issuance/verification
- **ModelMapper** — DTO ↔ entity mapping
- **Cloudinary** (`cloudinary-http44`) — media/image uploads
- **OkHttp** + **Gson** — outbound HTTP calls (e.g. Paystack)
- **json-patch** — partial-update support
- Build: **Maven** (wrapper included), packaged as a runnable jar, containerized via Docker

## Architecture

Single Spring Boot module, package root `com.guildworkman.api`, in a
conventional layered structure:

```
controllers/       REST endpoints (@RestController)
services/
  ServiceUtils/        service interfaces
  implmentations/      interface implementations
  paystack/            Paystack payment integration
data/models/       JPA entities
dto/requests/      inbound request payloads
dto/responses/     outbound response payloads
handler/           GlobalExceptionHandler
exceptions/        custom exceptions
config/            Spring config (security, mail, cloud, mapper, app)
utils/             JWT + validation helpers
```

Requests flow `Controller → Service interface (ServiceUtils) → ServiceImpl
(implmentations) → JPA repository`, with DTOs converted to/from entities via
ModelMapper (`config/MapperConfig.java`) and every response wrapped in a
common `ApiResponse { data, success }` envelope. `GlobalExceptionHandler`
translates the custom exceptions in `exceptions/` (e.g.
`UserNotFoundException`, `AppointmentNotFoundException`,
`InvalidPasswordException`) into consistent HTTP error responses.

## Domain model

Core JPA entities in `data/models/`:

| Entity | Relationships | Purpose |
|---|---|---|
| `Client` | 1:N `Appointment`, 1:1 `Address` | A user who books workers |
| `SkilledWorker` | 1:N `Skill`, 1:1 `Address` | A tradesperson who gets booked |
| `Skill` | N:1 `SkilledWorker` | A trade/category a worker offers |
| `Address` | — | Shared geo/address data for clients and workers (backs the `/nearby` lookup) |
| `Appointment` | N:1 `Client`, N:1 `SkilledWorker` | A booked job |
| `SlotReservation` | worker/client by id (`booking/model/`) | A claim on one slot of a worker's calendar — held, then confirmed into an `Appointment`. The row double-booking prevention is built on |
| `Consultation` | N:1 `Client`, N:1 `SkilledWorker`, 1:1 `ConsultationAvailability` | A pre-booking consultation between client and worker |
| `ConsultationAvailability` | 1:1 `Consultation` | Scheduled availability windows for a consultation |
| `Review` | N:1 (worker/appointment) | A client's rating/feedback on a completed job |
| `Transaction` / `TransactionHistory` | M:N | Payment records (Paystack) |
| `Notification` | — | In-app/email notification records |
| `Admin` | — | Admin-role user |

## Features

- Client and skilled-worker registration and login (JWT-based auth)
- Appointment booking, update, cancellation, and deletion
- Concurrency-safe slot reservation — database-level locking prevents two clients
  double-booking a worker, plus a per-worker availability read
  ([`docs/APPOINTMENT_BOOKING.md`](docs/APPOINTMENT_BOOKING.md))
- Consultations: booking a consultation and scheduling client/worker availability
- Skilled-worker profile management, skill listing, and geo lookup (`/nearby`)
- Payment initiation/verification via **Paystack** (service layer + `PaymentServiceImpl`)
- Transactional email sending (mail service, provider-agnostic API key + URL config)
- Reviews and worker ratings (`ReviewServiceImpl`)
- Admin operations (`AdminServiceImpl`)
- Global exception handling with typed exceptions

## Not yet wired up

A few service-layer pieces exist but currently have **no REST endpoint**
exposing them — the business logic is implemented and (where applicable)
tested, but not yet reachable over HTTP:

- **`PaymentController`** (`/payment`) and **`MapController`**
  (`/showMap`) exist in the tree but are commented out and inactive. Payment
  logic lives in `services/paystack/PaymentServiceImpl` and can be
  re-exposed by uncommenting `PaymentController` once its request/response
  wiring is finalized.
- **`AdminServiceImpl`** has no corresponding `AdminController` yet.
- **Reviews** (`ReviewServiceImpl`, `PostReviewRequest`/`EditReviewRequest`)
  aren't exposed on `ClientController` or `SkilledWorkerController` today,
  even though the service and DTOs are fully implemented and tested.

This also means **none of `guildworkman-contracts`'s Soroban contracts are
called from here yet** — wiring `escrow.create_appointment`,
`reputation.submit_review`, and `loyalty-token.mint` into the appointment
and review flows requires a Soroban RPC client and per-role Stellar keypair
handling that doesn't exist in this codebase today. See that repo's README
for the intended integration flow.

## Prerequisites

- JDK 17
- PostgreSQL instance (local or remote)
- Maven (or just use the included `./mvnw` wrapper — no local Maven install needed)

## Configuration

All configuration lives in `src/main/resources/application.properties`, entirely
as `${ENV_VAR:default}` placeholders — there is no separate secrets file anymore.
Non-secret keys have real defaults; secret keys default to empty and must be
supplied via environment variables in every environment (local, CI, production).
See `.env.example` at the repo root for the full list with a short description
of each. Copy it to `.env` for local reference (never commit a populated one) and
export the values into your shell, IDE run configuration, or `docker run --env-file`.

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `DATABASE_URL` | `jdbc:postgresql://localhost:5432/service` |
| `spring.datasource.username` | `DATABASE_USERNAME` | `postgres` |
| `spring.datasource.password` | `DATABASE_PASSWORD` | `password` |
| `spring.jpa.hibernate.ddl-auto` | `DDL_AUTO` | `update` |
| `mail.api.key` | `MAIL_API_KEY` | *(empty — required)* |
| `mail.api.url` | `MAIL_API_URL` | `https://api.brevo.com/v3/smtp/email` |
| `cloud.api.name` | `CLOUDINARY_API_NAME` | *(empty — required)* |
| `cloud.api.key` | `CLOUDINARY_API_KEY` | *(empty — required)* |
| `cloud.api.secret` | `CLOUDINARY_API_SECRET` | *(empty — required)* |
| `paystack.secret.key` | `PAYSTACK_SECRET_KEY` | *(empty — required)* |
| `paystack.verify.payment.url` | `PAYSTACK_VERIFY_URL` | `https://api.paystack.co/transaction/verify` |
| `paystack.initiate.payment` | `PAYSTACK_INITIATE_URL` | `https://api.paystack.co/transaction/initialize` |
| `spring.h2.console.enabled` | `H2_CONSOLE_ENABLED` | `false` |

> **Security note:** this repository's git history (both the old `secret.properties`
> committed file and, briefly, this repo's own earlier state) contains real
> credentials: a Postgres password for a Railway-hosted database, a Brevo/Sendinblue
> mail API key, a Cloudinary API key + secret, and a Paystack test secret key.
> **Rotate/revoke all of these** in their respective dashboards — removing them from
> the tracked file does not undo the exposure already baked into git history.
> Never commit real credentials anywhere in this repo again; supply them only as
> environment variables at deploy time.

## Running locally

A `docker-compose.yml` is provided for the database, so you don't need a local
PostgreSQL install:

```sh
docker compose up -d   # starts Postgres on localhost:5432, db=service user=postgres password=password

export MAIL_API_KEY=...
export CLOUDINARY_API_NAME=...
export CLOUDINARY_API_KEY=...
export CLOUDINARY_API_SECRET=...
export PAYSTACK_SECRET_KEY=...

./mvnw spring-boot:run
```

`DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD` already default to match
the `docker-compose.yml` credentials, so you don't need to export them unless
you're pointing at a different database.

The app starts on the default Spring Boot port (`8080`) unless overridden.

## Running with Docker

```sh
docker build -t guildworkman-api .
docker run -p 8080:8080 \
  -e DATABASE_URL=jdbc:postgresql://<host>:5432/service \
  -e DATABASE_USERNAME=postgres \
  -e DATABASE_PASSWORD=yourpassword \
  -e MAIL_API_KEY=... \
  -e CLOUDINARY_API_NAME=... -e CLOUDINARY_API_KEY=... -e CLOUDINARY_API_SECRET=... \
  -e PAYSTACK_SECRET_KEY=... \
  guildworkman-api
```

The Dockerfile is a two-stage build: Maven build stage (`maven:3.8.7`) →
runtime stage (`openjdk:17`) copying the packaged jar.

## API reference

All endpoints are prefixed `/api/v1/...`. Responses are wrapped in a common
`ApiResponse { data, success }` envelope unless noted.

An interactive OpenAPI/Swagger UI is served at `/swagger-ui.html`
(spec at `/v3/api-docs`).

### `AuthController` — `/api/v1/auth`

JWT authentication, RBAC, and rotating refresh tokens. Full design notes:
[`docs/AUTHENTICATION.md`](docs/AUTHENTICATION.md).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/register` | public | Create an account, returns an access + refresh token pair |
| POST | `/login` | public | Authenticate, returns a token pair |
| POST | `/refresh` | refresh token | Rotate to a new token pair (revokes the old one; reuse revokes the session) |
| POST | `/logout` | refresh token | Revoke the refresh token's whole family |
| GET | `/me` | access token | The authenticated caller's identity |

`/api/v1/admin/**` requires the `ADMIN` role (see `AdminController`).

### `ClientController` — `/api/v1/client`

| Method | Path | Purpose |
|---|---|---|
| POST | `/registerClient` | Register a new client |
| POST | `/login` | Client login |
| POST | `/bookAppointment` | Book an appointment in one step. Body: `clientId`, `category`, `scheduleTime`, plus optional `skilledWorkerId` (which tradesperson) and `amount` (agreed price). When a worker is named, the slot is claimed through the same concurrency guard as `/api/v1/booking` — a second caller racing for it gets `409 slot-unavailable` |
| PUT | `/updateAppointment?appointmentId=` | Set an appointment's status — body `{ "status": "ACCEPTED" }` (also accepts `amount`, `startTime`) |
| PUT | `/cancelAppointment?appointmentId=` | Cancel an appointment — no body. Leaves the record in place with status `CANCELLED` |
| DELETE | `/deleteAppointment?appointmentId=` | Delete an appointment outright — no body |
| GET | `/viewAllAppointment?clientId=` | **List** of a client's appointments (`id`, `status`, `category`, `scheduleTime`, `amount`, `worker`) |
| PUT | `/updateClientProfile` | Update client profile |
| POST | `/consult?clientId=&workerId=&details=` | Book a consultation |
| POST | `/{consultationId}/availability?clientAvailability=&workerAvailability=` | Schedule consultation availability |

CORS: configured globally (see below) — no per-controller `@CrossOrigin`.

### `BookingController` — `/api/v1/booking`

Concurrency-safe booking: two visitors can never both take the same slot on a
worker's calendar. Full design notes:
[`docs/APPOINTMENT_BOOKING.md`](docs/APPOINTMENT_BOOKING.md).

| Method | Path | Purpose |
|---|---|---|
| POST | `/reservations` | Hold a slot (201). Body: `idempotencyKey`, `skilledWorkerId`, `clientId`, `slotStart`, optional `durationMinutes`. Holds lapse after 5 minutes. Idempotent on `idempotencyKey` — see the `X-Idempotent-Replay` response header |
| GET | `/reservations/{id}` | A reservation's current state |
| POST | `/reservations/{id}/confirm` | Turn a hold into an appointment. Optional body: `category`, `amount` |
| DELETE | `/reservations/{id}` | Release a hold early |
| GET | `/workers/{workerId}/availability?from=&to=` | A worker's **taken** slots in a window — booked appointments and live holds. The read `viewAllAppointment` can't do, since that returns only the calling client's own bookings |

Losing a race for a slot is `409 slot-unavailable`, not a `400`: the request was
well-formed, it just arrived second.

These endpoints are unwrapped (no `ApiResponse` envelope) — they return the
reservation or availability object directly, as the escrow endpoints do.

### `SkilledWorkerController` — `/api/v1/skilledWorker`

| Method | Path | Purpose |
|---|---|---|
| POST | `/registerSkilledWorker` | Register a new skilled worker |
| POST | `/login` | Skilled-worker login |
| POST | `/addSkill` | Add a skill to a worker's profile |
| GET | `/findById?skilledWorkerId=` | Fetch a worker by ID |
| GET | `/findByFullName?skilledWorkerFullName=` | Fetch a worker by name |
| PUT | `/updateSkilledWorkerProfile` | Update worker profile |
| GET | `/nearby?lat=&lon=&radius=` | Find workers near a location (`radius` defaults to 10) |

CORS: configured globally (see below) — no per-controller `@CrossOrigin`.

### `MailController` — `/api/v1/mail`

| Method | Path | Purpose |
|---|---|---|
| POST | `/sendMail` | Send a transactional email |

CORS: configured globally (see below) — no per-controller `@CrossOrigin`.

### CORS

CORS is configured in **one place** — `config/WebConfig` — from the
`cors.allowed-origins` property (env `CORS_ALLOWED_ORIGINS`), a comma-separated
list. Default:

```
http://localhost:3000,https://guildworkman-web.vercel.app
```

It uses `allowedOriginPatterns`, so wildcard patterns (e.g. `https://*.vercel.app`
for preview deployments) stay compatible with `allowCredentials(true)` — Spring
forbids that combination with a literal `"*"`. Don't add `@CrossOrigin` to
controllers: handler-level annotations get combined with this config and
previously drifted out of sync.

## Testing

Tests are `@SpringBootTest`-based and need a real database — bring up the
Postgres container first:

```sh
docker compose up -d
./mvnw test
```

CI runs the same suite against a Postgres service container on every push/PR
to `development` (`.github/workflows/test.yml`).

**Background `@Scheduled` pollers default to off in tests.** `pom.xml`'s
`maven-surefire-plugin` sets `chain.events.poll-delay-ms`,
`escrow.orchestration.*-poll-delay-ms`, and
`escrow.reconciliation.poll-delay-ms` to 1 hour via `systemPropertyVariables`
for every test JVM. Without this, a `@SpringBootTest` class that doesn't
explicitly disable scheduling leaves its poller running against the shared
test database for the rest of the test JVM's life (Spring caches
`ApplicationContext`s), racing with whatever test class runs next and
processing its rows out from under it — this was an actual source of
intermittent CI failures before the fix. Tests that specifically want a
poller running (e.g. `ChainEventServiceIntegrationTest`,
`EscrowOrchestrationIntegrationTest`) override the relevant property via
their own `@SpringBootTest(properties = …)`, which takes precedence over
the surefire-level system properties. Don't remove that
`systemPropertyVariables` block without replacing it with an equivalent
per-test opt-out.

Existing tests cover `MailServiceTest`, `ClientServiceTest`,
`SkilledWorkerServiceTest`, `AppointmentServiceTest`, `ReviewServiceTest`, and
`SkillServiceTest`. All 14 tests pass as of this writing.

## Deployment

`.github/workflows/build.yml` builds and pushes a Docker image to Docker Hub
(`meshackyaro/guildworkman-api:latest`) on every push to `development`, then
deploys it via `docker pull` + `docker run` on the target host. That file
also contains several earlier, now-commented-out iterations of the same
pipeline (SSH-based deploy, alternate Maven caching) — only the final,
uncommented job at the bottom of the file is live.

## Branching

Default branch is **`development`**. Feature/area work happens on
short-lived branches and merges back into `development` via PR — check
`git branch -r` for whatever's currently active rather than relying on a
hardcoded list here, since branches get created and deleted regularly.

## Contributing

Branch off `development`, keep real credentials out of your commits (only
`.env.example` placeholders, never a populated `.env`), and open a PR back
into `development`.
