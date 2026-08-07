# GuildWorkman Core

[![Test](https://github.com/workman-labs/guildworkman-core/actions/workflows/test.yml/badge.svg)](https://github.com/workman-labs/guildworkman-core/actions/workflows/test.yml)
[![Soroban CI](https://github.com/workman-labs/guildworkman-core/actions/workflows/soroban-ci.yml/badge.svg)](https://github.com/workman-labs/guildworkman-core/actions/workflows/soroban-ci.yml)

The server-side core of **GuildWorkman** — a two-sided marketplace connecting
clients with skilled tradespeople (electricians, plumbers, barbers, carpenters,
fashion designers, photographers, and more) for booked, in-person appointments.

This is a **monorepo** holding the two halves of the system that run away from
the browser: the REST API the app talks to, and the on-chain contracts that are
meant to hold the money and the reputation.

| Package | What it is | Stack |
|---|---|---|
| [**`backend-api/`**](backend-api/) | The REST API — auth, bookings, consultations, payments, email, reviews | Java 17 · Spring Boot 3.3 · Maven · PostgreSQL |
| [**`soroban-contracts/`**](soroban-contracts/) | On-chain escrow, reputation, and loyalty-token contracts | Rust · Soroban SDK 26 · Stellar |

The client-facing web app lives in a separate repo —
[**`guildworkman-web`**](https://github.com/workman-labs/guildworkman-web)
(Next.js), live at **[guildworkman-web.vercel.app](https://guildworkman-web.vercel.app)**.

Follow GuildWorkman on X: **[@guildworkman](https://x.com/guildworkman)**.

---

## Table of contents

- [Why a monorepo](#why-a-monorepo)
- [Repository layout](#repository-layout)
- [Architecture](#architecture)
- [`backend-api/` — the REST API](#backend-api--the-rest-api)
- [`soroban-contracts/` — the on-chain layer](#soroban-contracts--the-on-chain-layer)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Continuous integration](#continuous-integration)
- [Deployment](#deployment)
- [Project status — what's wired and what isn't](#project-status--whats-wired-and-what-isnt)
- [Branching & contributing](#branching--contributing)
- [History](#history)

---

## Why a monorepo

The backend and the contracts are **two halves of one feature**. The escrow
flow — hold a client's payment, release it when the job is confirmed, refund it
if it isn't — spans a Spring service *and* a Soroban contract. Keeping them in
one repo means a change to a contract and the code that calls it can land in a
**single, atomically-reviewed PR**, with one history.

They stay independently buildable: separate toolchains, separate CI (see
[Continuous integration](#continuous-integration)), separate deploy targets.
The frontend stays in its own repo because it ships on a completely different
cadence and platform (Vercel).

## Repository layout

```
guildworkman-core/
├── backend-api/            # Spring Boot REST API (the deployable service)
│   ├── src/main/java/...   # controllers, services, JPA models, security
│   ├── src/test/java/...   # unit + integration tests
│   ├── pom.xml             # Maven module root
│   ├── Dockerfile          # multi-stage build -> runnable jar
│   └── docker-compose.yml  # local Postgres for development
│
├── soroban-contracts/      # Rust / Soroban workspace
│   └── contracts/
│       ├── escrow/         # holds payment until the job is confirmed
│       ├── reputation/     # immutable, one-per-appointment reviews
│       └── loyalty-token/  # SEP-41-style rewards token
│
├── .github/workflows/      # CI (must live at repo root)
│   ├── build.yml           # backend: Docker build/push + deploy
│   ├── test.yml            # backend: Maven tests against Postgres
│   └── soroban-ci.yml      # contracts: cargo test + wasm build
│
└── MIGRATION.md            # how the two repos were merged
```

Each package keeps its **own README** with the deep detail:
[`backend-api/README.md`](backend-api/README.md) (domain model, full endpoint
reference, config) and [`soroban-contracts/README.md`](soroban-contracts/README.md)
(contract interfaces, storage layouts, CLI usage, security notes).

## Architecture

```
        ┌─────────────────────────────┐
        │      guildworkman-web       │   Next.js on Vercel  (separate repo)
        └──────────────┬──────────────┘
                       │  REST  /api/v1/**   (JWT-authenticated)
                       ▼
   ┌────────────────────────────────────────┐        ┌──────────────┐
   │       backend-api/   (this repo)       │───────▶│  PostgreSQL  │
   │  Spring Boot · Spring Security + JWT   │        └──────────────┘
   │  JPA/Hibernate · ModelMapper           │───────▶  Paystack    (payments)
   │                                        │───────▶  Brevo       (email)
   │                                        │───────▶  Cloudinary  (media)
   └────────────────────┬───────────────────┘
                        ┆
                        ┆  Soroban RPC + Stellar keypairs
                        ┆  ⚠️ PLANNED — not implemented yet
                        ▼
   ┌────────────────────────────────────────┐
   │    soroban-contracts/   (this repo)    │   Stellar / Soroban
   │    escrow · reputation · loyalty-token │
   └────────────────────────────────────────┘
```

Today the backend is the **single source of truth**: it books appointments,
charges via Paystack, and stores reviews in Postgres. The contracts implement
the same trust-sensitive parts of that flow on-chain, but **nothing calls them
yet** — see [Project status](#project-status--whats-wired-and-what-isnt).

## `backend-api/` — the REST API

**Stack:** Java 17 · Spring Boot 3.3.2 · Spring Web · Spring Data JPA
(Hibernate) · Spring Security with JWT (`java-jwt`) · PostgreSQL · Lombok ·
ModelMapper · OkHttp/Gson (outbound HTTP) · Cloudinary (media) · H2 +
MockWebServer (tests).

### Domain model

Core JPA entities in `data/models/`:

| Entity | Purpose |
|---|---|
| `Client` | A user who books workers (1:N `Appointment`, 1:1 `Address`) |
| `SkilledWorker` | A tradesperson who gets booked (1:N `Skill`, 1:1 `Address`) |
| `Skill` | A trade/category a worker offers |
| `Address` | Shared geo/address data — backs the `/nearby` lookup |
| `Appointment` | A booked job (N:1 `Client`, N:1 `SkilledWorker`) |
| `SlotReservation` | A claim on one slot of a worker's calendar — the row that makes double-booking impossible (`booking/model/`) |
| `Consultation` / `ConsultationAvailability` | A pre-booking consultation and its scheduled windows |
| `Review` | A client's rating/feedback on a completed job |
| `Transaction` / `TransactionHistory` | Paystack payment records |
| `Notification`, `Admin` | Notification records; admin-role user |

### API surface

All routes are prefixed **`/api/v1`**, and responses are wrapped in a common
envelope: **`ApiResponse { data, status }`**.

| Controller | Base path | Highlights |
|---|---|---|
| `ClientController` | `/api/v1/client` | `registerClient`, `login`, `bookAppointment`, `updateAppointment`, `cancelAppointment`, `deleteAppointment`, `viewAllAppointment`, `updateClientProfile`, consultations |
| `BookingController` | `/api/v1/booking` | Concurrency-safe booking: `reservations` (hold a slot), `confirm`, release, and `workers/{id}/availability` (a worker's taken slots) |
| `SkilledWorkerController` | `/api/v1/skilledWorker` | `registerSkilledWorker`, `login`, `addSkill`, `findById`, `findByFullName`, `updateSkilledWorkerProfile`, `nearby` |
| `MailController` | `/api/v1/mail` | `sendMail` (transactional email) |

Mutations on appointments take the id as a **query param**
(`?appointmentId=`), and `cancel`/`update` are `PUT`, `delete` is `DELETE`.

📖 Full method-by-method reference: [`backend-api/README.md`](backend-api/README.md#api-reference).

## `soroban-contracts/` — the on-chain layer

A Cargo workspace (`soroban-sdk` 26.1.0) with three contracts. They mirror the
domain the backend already implements server-side, moving the trust-sensitive
parts on-chain:

| Contract | Purpose |
|---|---|
| [`escrow`](soroban-contracts/contracts/escrow) | Holds a client's payment for a booked appointment until the client confirms the job is done; releases funds to the worker, refunds on cancellation, supports admin-arbitrated disputes. |
| [`reputation`](soroban-contracts/contracts/reputation) | Stores one immutable review per completed appointment and keeps a running rating aggregate per worker. |
| [`loyalty-token`](soroban-contracts/contracts/loyalty-token) | A SEP-41-style fungible token used to reward clients/workers with points. Only a designated `minter` (the backend's service account) can mint. |

**Tested:** escrow (5 tests), reputation (3), loyalty-token (6) — covering the
happy path, refunds, disputes, duplicate-appointment rejection, out-of-range
ratings, and mint/transfer/burn authorisation.

📖 Contract interfaces, storage layouts, errors, and CLI usage:
[`soroban-contracts/README.md`](soroban-contracts/README.md).

## Quick start

### Prerequisites

- **Backend:** JDK 17+, Docker (for the local Postgres)
- **Contracts:** Rust toolchain + the `wasm32v1-none` target, and the
  [Stellar CLI](https://developers.stellar.org/docs/build/smart-contracts/getting-started) for building/deploying

### Run the backend

```sh
cd backend-api

docker compose up -d          # Postgres on :5432 (db=service, user=postgres, pw=password)

export MAIL_API_KEY=...       # required secrets (see Configuration)
export CLOUDINARY_API_NAME=...
export CLOUDINARY_API_KEY=...
export CLOUDINARY_API_SECRET=...
export PAYSTACK_SECRET_KEY=...

./mvnw spring-boot:run        # starts on :8080
```

`DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` already default to
the `docker-compose.yml` credentials, so you only need them if you're pointing
at a different database.

### Build & test the contracts

```sh
cd soroban-contracts

cargo test --workspace        # unit tests (soroban-sdk testutils)
stellar contract build        # wasm -> target/wasm32v1-none/release/*.wasm
```

## Configuration

Backend config is environment-driven (`backend-api/src/main/resources/application.properties`):

| Env var | Default | Notes |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/service` | Matches `docker-compose.yml` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `postgres` / `password` | |
| `DDL_AUTO` | `update` | Hibernate schema strategy |
| `MAIL_API_KEY` | *(required)* | Brevo |
| `MAIL_API_URL` | `https://api.brevo.com/v3/smtp/email` | |
| `CLOUDINARY_API_NAME` / `_KEY` / `_SECRET` | *(required)* | Media uploads |
| `PAYSTACK_SECRET_KEY` | *(required)* | Payments |
| `PAYSTACK_VERIFY_URL` / `PAYSTACK_INITIATE_URL` | Paystack defaults | |
| `H2_CONSOLE_ENABLED` | `false` | |

## Continuous integration

Workflows live at the repo root (GitHub only runs them there) and are
**path-filtered**, so each stack only builds when its own files change:

| Workflow | Triggers on | Does |
|---|---|---|
| `test.yml` | `backend-api/**` | Spins up Postgres, runs `./mvnw test` from `backend-api/` |
| `build.yml` | `backend-api/**` (push to `development`) | Builds the Docker image (context: `backend-api/`), pushes to Docker Hub, deploys |
| `soroban-ci.yml` | `soroban-contracts/**` | `cargo test --workspace` + release wasm build |

A contracts-only PR won't run the Java suite, and a backend-only PR won't
compile Rust.

## Deployment

- **Backend** — `build.yml` builds a Docker image from `backend-api/`, pushes
  it to Docker Hub as `meshackyaro/guildworkman-api:latest` on every push to
  `development`, then deploys via `docker pull` + `docker run` on the target
  host. (The image name intentionally keeps the `-api` suffix: it ships the
  API service, not the whole repo.)
- **Contracts** — deployed to Stellar with the Stellar CLI; see the
  [testnet example](soroban-contracts/README.md#deploy-testnet-example).
- **Frontend** — Vercel, from the `guildworkman-web` repo.

## Project status — what's wired and what isn't

Being honest about the seams, because the code is further along than the
integration:

**Working today**
- Client/worker registration + JWT login, appointment booking/update/cancel,
  consultations, worker profiles and `/nearby` geo lookup, transactional email.
- `viewAllAppointment` returns a **list** of the client's appointments, each with
  the `id` the cancel/update/delete endpoints require.
- Bookings record **which worker** was booked and the agreed **amount**
  (`skilledWorkerId` + `amount` on `bookAppointment`, both optional), so each
  appointment comes back with a `worker` summary.
- CORS is configured in one place (`WebConfig`) from `CORS_ALLOWED_ORIGINS`, and
  allows the live frontend.
- All three Soroban contracts are implemented and unit-tested.

**Not yet wired**
- 🔌 **The contracts aren't called from the backend.** Wiring
  `escrow.create_appointment`, `reputation.submit_review`, and
  `loyalty-token.mint` into the appointment/review flows needs a Soroban RPC
  client and per-role Stellar keypair handling that doesn't exist here yet.
  The intended flow is spelled out in
  [`soroban-contracts/README.md`](soroban-contracts/README.md#suggested-backend-integration-not-yet-wired-in).
- 🔌 **Payments/Admin/Reviews have no REST surface.** `PaymentController` and
  `MapController` exist but are commented out; `AdminServiceImpl` has no
  controller; `ReviewServiceImpl` and its DTOs are implemented and tested but
  not exposed. The business logic is there — the endpoints aren't.
- ⚠️ **No escrow, still.** Payments go through Paystack; the Soroban escrow
  contract is not in the loop (see the first item above).

## Branching & contributing

Default branch is **`development`**. Work happens on short-lived branches and
merges back via PR. Because CI is path-filtered, keep changes scoped where you
can — and when a change genuinely spans both halves (e.g. the escrow
integration), put them in **one** PR: that's the reason this monorepo exists.

## History

This repo was formed by merging the standalone `guildworkman-contracts` repo
into the backend repo with `git subtree` (both commit histories preserved), then
restructured into the symmetric `backend-api/` + `soroban-contracts/` layout and
renamed from `guildworkman-api` to **`guildworkman-core`** — since it no longer
houses just an API. The full runbook, including what was preserved and what
wasn't, is in [`MIGRATION.md`](MIGRATION.md).
