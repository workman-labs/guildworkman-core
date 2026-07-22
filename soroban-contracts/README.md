# guildworkman-contracts

![CI](https://github.com/workman-labs/guildworkman-contracts/actions/workflows/ci.yml/badge.svg)

Soroban (Stellar) smart contracts for GuildWorkman, the skilled-worker booking
marketplace. This workspace has four independent contracts:

| Contract | Path | Purpose |
|---|---|---|
| `escrow` | `contracts/escrow` | Holds a client's payment for a booked appointment until the client confirms the job is done; releases funds to the skilled worker, refunds on cancellation, and supports admin-arbitrated disputes. |
| `reputation` | `contracts/reputation` | Stores one immutable review per completed appointment and keeps a running rating aggregate per skilled worker. |
| `loyalty-token` | `contracts/loyalty-token` | A SEP-41-style fungible token used to reward clients/workers with points on completed appointments. Only a designated `minter` (the backend's service account) can mint. |
| `loyalty-emissions` | `contracts/loyalty-emissions` | An emission engine that owns the `loyalty-token`'s `minter` role. Instead of minting rewards in a lump sum, it streams them out of per-account linear vesting schedules, throttled by per-account and global rate limits, and lets the admin reclaim allocations left unclaimed past a deadline. |

These mirror the domain already implemented server-side in the backend
([`../backend-api`](../backend-api): `AppointmentService`, `ReviewService`,
`TransactionService`), moving the trust-sensitive parts of that flow —
holding money, recording reviews, issuing rewards — on-chain.

## Table of contents

- [Project ecosystem](#project-ecosystem)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Test](#test)
- [Deploy (testnet example)](#deploy-testnet-example)
- [Contract interfaces](#contract-interfaces)
- [Security considerations / known limitations](#security-considerations--known-limitations)
- [Notes / follow-ups](#notes--follow-ups)
- [License](#license)

## Project ecosystem

GuildWorkman lives in two repositories:

| Repo | Role |
|---|---|
| **`guildworkman-core`** (this repo) | These Soroban contracts (`soroban-contracts/`, here) **and** the Spring Boot backend (`backend-api/`) — which implements the same domain server-side today (`AppointmentService`, `ReviewService`, `TransactionService`) and is the intended caller of these contracts once integrated (see [Suggested backend integration](#suggested-backend-integration-not-yet-wired-in)). |
| [`guildworkman-web`](https://github.com/workman-labs/guildworkman-web) | Next.js frontend. Already has a real Freighter wallet-connect button and a "Trust, backed by smart contracts" section describing these contracts — but makes no contract calls yet. |

## Architecture

These are three separate contracts rather than one monolith:

- **Independent storage and upgrade paths.** `escrow` handles money,
  `reputation` handles reviews, `loyalty-token` handles a token balance
  ledger. Each has a different risk profile and a different reason to change;
  bugs or upgrades in one shouldn't force redeploying the others.
- **Independent trust boundaries.** `escrow` and `loyalty-token` each have
  their own `admin`/`minter` role — they don't need to trust each other's
  internal state, only the caller's authenticated identity.
- **Composability over coupling.** A future contract (or the backend) can
  call into any of the three via their public functions without depending on
  their private storage layout.

### Suggested backend integration (not yet wired in)

None of this is currently called from the backend (`../backend-api`). The
intended flow, if/when integrated, looks like:

1. Client books a worker in the existing Java backend → backend (or the
   client's wallet, if going non-custodial) calls `escrow.create_appointment`
   with the agreed amount and a token address (e.g. a Stellar Asset Contract
   wrapping USDC), instead of only charging via Paystack.
2. Client marks the job done in the app → backend calls
   `escrow.confirm_completion`, which pays the worker.
3. Backend calls `reputation.submit_review` with the same `appointment_id`
   right after the client submits their in-app review, so on-chain reviews
   stay 1:1 with completed, paid appointments.
4. Backend (as `minter`) calls `loyalty-token.mint` to reward the client
   and/or worker some points for the completed appointment — or, for rewards
   that should vest over time rather than land instantly, registers a
   `loyalty-emissions.create_schedule` and lets the recipient `claim` the
   stream as it vests (see [loyalty-emissions](#loyalty-emissions)).

This requires the backend to hold a Stellar keypair per role (or per user, if
going non-custodial) and a Soroban RPC client — none of that exists in
`backend-api/` today.

## Prerequisites

- Rust with the `wasm32v1-none` target: `rustup target add wasm32v1-none`
  (Soroban does not yet support `wasm32-unknown-unknown` on Rust 1.82+;
  use `wasm32v1-none` with Rust 1.84+, or Rust 1.81 or earlier for the old target.)
- [Stellar CLI](https://developers.stellar.org/docs/tools/cli/install-cli) (`stellar` binary), used for deploying/invoking contracts.

## Build

```sh
stellar contract build
# or, per-contract:
cargo build --target wasm32v1-none --release -p guildworkman-escrow
```

Wasm output lands in `target/wasm32v1-none/release/*.wasm`.

## Test

```sh
cargo test --workspace
```

Each contract has unit tests under `contracts/<name>/src/test.rs` using
`soroban-sdk`'s `testutils`. Current coverage:

- **escrow** (5 tests): happy-path completion pays the worker and drains the
  contract's balance; cancellation refunds the client; a raised dispute
  resolved in the worker's favor pays the worker; creating a duplicate
  `appointment_id` is rejected; confirming an already-completed appointment
  is rejected.
- **reputation** (3 tests): submitting reviews updates the count/sum
  aggregate and average correctly; reviewing the same `appointment_id` twice
  is rejected; a rating outside 1-5 is rejected.
- **loyalty-token** (6 tests): mint increases balance; transfer moves balance
  between accounts; transferring more than the balance fails; approve +
  transfer_from spends down the allowance correctly; burn reduces balance;
  the admin can rotate the minter and the new minter can mint.
- **loyalty-emissions** (24 tests): linear vesting reports the right amount at
  the start, midpoint, and end of a stream and stays capped afterwards; a
  cliff blocks vesting until it's reached; `claim` mints the vested delta and
  incremental claims only mint what's newly vested; per-account and global
  rate limits clamp a claim to the remaining window budget and open a fresh
  budget the next window; the `claimable` view reflects both vesting and rate
  limits; `reclaim` returns the unclaimed remainder only after the deadline and
  blocks further claims; and adversarial paths — double-init, bad config, bad
  schedule params, reclaim-before-vesting-end, double-claim, double-reclaim,
  claiming a missing/reclaimed schedule, and looping claims across many windows
  never mints more than a schedule's `total`.

These are unit tests against the in-process Soroban test host
(`Env::default()` + `mock_all_auths()`), not integration tests against a real
network — they don't cover cross-contract calls, real fee/resource limits, or
multi-node consensus behavior.

## Deploy (testnet example)

```sh
stellar keys generate deployer --network testnet --fund

stellar contract deploy \
  --wasm target/wasm32v1-none/release/guildworkman_escrow.wasm \
  --source deployer --network testnet

stellar contract invoke \
  --id <ESCROW_CONTRACT_ID> --source deployer --network testnet \
  -- initialize --admin <ADMIN_ADDRESS>
```

Repeat `deploy` for `guildworkman_reputation.wasm`,
`guildworkman_loyalty_token.wasm`, and `guildworkman_loyalty_emissions.wasm`,
then call each contract's `initialize` once. For the emission engine to be
able to mint, point it at the token in its `initialize` and then hand it the
token's `minter` role:

```sh
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR --token $LOYALTY_CONTRACT_ID \
     --config '{ "window": 17280, "account_cap": "1000", "global_cap": "100000" }'

# Hand the token's minter role to the emissions contract.
stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- set_minter --new_minter $EMISSIONS
```

## Contract interfaces

### escrow

- `initialize(admin: Address)`
- `create_appointment(appointment_id: u64, client: Address, worker: Address, token: Address, amount: i128)`
- `confirm_completion(appointment_id: u64)` — client-only, pays the worker
- `cancel_appointment(appointment_id: u64)` — client-only, refunds the client
- `raise_dispute(appointment_id: u64, caller: Address)` — client or worker
- `resolve_dispute(appointment_id: u64, refund_to_client: bool)` — admin-only
- `get_appointment(appointment_id: u64) -> Appointment`

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Admin` | instance | The dispute arbiter's `Address`, set once in `initialize`. |
| `Appointment(u64)` | persistent | An `Appointment { client, worker, token, amount, status }` keyed by `appointment_id`. `status` is one of `Funded`, `Completed`, `Cancelled`, `Disputed`, `Resolved`. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `AlreadyInitialized` | 1 | `initialize` called more than once. |
| `NotInitialized` | 2 | `resolve_dispute` called before `initialize`. |
| `AppointmentExists` | 3 | `create_appointment` reused an existing `appointment_id`. |
| `AppointmentNotFound` | 4 | No appointment stored under that `appointment_id`. |
| `InvalidStatus` | 5 | The requested transition doesn't apply to the appointment's current status (e.g. confirming a non-`Funded` appointment). |
| `InvalidAmount` | 6 | `amount <= 0` in `create_appointment`. |
| `NotAParticipant` | 7 | `raise_dispute` called by an address that is neither the client nor the worker. |

#### CLI usage

```sh
# One-time setup
stellar contract invoke --id $ESCROW --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR

# Client books worker WORKER_ADDR, depositing 10000 units of TOKEN_ADDR, appointment id 1
stellar contract invoke --id $ESCROW --source client --network testnet \
  -- create_appointment --appointment_id 1 --client $CLIENT_ADDR \
     --worker $WORKER_ADDR --token $TOKEN_ADDR --amount 10000

# Client confirms the job is done -> pays the worker
stellar contract invoke --id $ESCROW --source client --network testnet \
  -- confirm_completion --appointment_id 1

# Client cancels before completion -> refunds the client
stellar contract invoke --id $ESCROW --source client --network testnet \
  -- cancel_appointment --appointment_id 1

# Either party raises a dispute
stellar contract invoke --id $ESCROW --source client --network testnet \
  -- raise_dispute --appointment_id 1 --caller $CLIENT_ADDR

# Admin resolves the dispute in the worker's favor
stellar contract invoke --id $ESCROW --source admin --network testnet \
  -- resolve_dispute --appointment_id 1 --refund_to_client false

# Read appointment state
stellar contract invoke --id $ESCROW --source admin --network testnet \
  -- get_appointment --appointment_id 1
```

### reputation

- `submit_review(appointment_id: u64, client: Address, worker: Address, rating: u32, comment: String)` — 1-5 stars, one review per `appointment_id`
- `get_rating(worker: Address) -> Rating { count, sum }`
- `get_average_rating_x100(worker: Address) -> u32` — e.g. `437` = 4.37 stars
- `get_review(worker: Address, index: u32) -> Option<Review>`
- `get_review_count(worker: Address) -> u32`

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Reviewed(u64)` | persistent | A `bool` flag per `appointment_id`, used only to enforce one review per appointment. |
| `Rating(Address)` | persistent | A `Rating { count, sum }` aggregate per worker; `sum` is the running total of all star ratings given. |
| `Review(Address, u32)` | persistent | An individual `Review { client, rating, comment }`, keyed by `(worker, index)` where `index` is a per-worker sequence number starting at 0. |
| `ReviewCount(Address)` | persistent | The next free `index` for a given worker — also doubles as the total review count. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `InvalidRating` | 1 | `rating` outside the 1-5 range. |
| `AlreadyReviewed` | 2 | `submit_review` called twice for the same `appointment_id`. |
| `NoReviews` | 3 | `get_average_rating_x100` called for a worker with zero reviews (avoids a divide-by-zero). |

#### CLI usage

```sh
stellar contract invoke --id $REPUTATION --source client --network testnet \
  -- submit_review --appointment_id 1 --client $CLIENT_ADDR \
     --worker $WORKER_ADDR --rating 5 --comment "Great job, on time"

stellar contract invoke --id $REPUTATION --source admin --network testnet \
  -- get_rating --worker $WORKER_ADDR

stellar contract invoke --id $REPUTATION --source admin --network testnet \
  -- get_average_rating_x100 --worker $WORKER_ADDR

stellar contract invoke --id $REPUTATION --source admin --network testnet \
  -- get_review --worker $WORKER_ADDR --index 0

stellar contract invoke --id $REPUTATION --source admin --network testnet \
  -- get_review_count --worker $WORKER_ADDR
```

### loyalty-token

- `initialize(admin: Address, minter: Address, decimals: u32, name: String, symbol: String)`
- `set_minter(new_minter: Address)` — admin-only
- `mint(to: Address, amount: i128)` — minter-only
- `transfer`, `transfer_from`, `approve`, `allowance`, `burn`, `balance`, `decimals`, `name`, `symbol` — standard SEP-41 token surface

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Admin` | instance | The admin `Address`, allowed to call `set_minter`. |
| `Minter` | instance | The only `Address` allowed to call `mint`. |
| `Metadata` | instance | `TokenMetadata { decimals, name, symbol }`. |
| `Balance(Address)` | persistent | The `i128` token balance for a given holder. |
| `Allowance(Address, Address)` | temporary | An `AllowanceValue { amount, expiration_ledger }` for `(from, spender)`, cleared once `expiration_ledger` passes. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `AlreadyInitialized` | 1 | `initialize` called more than once. |
| `NotInitialized` | 2 | `mint`/`set_minter` called before `initialize`. |
| `InsufficientBalance` | 3 | `transfer`/`transfer_from`/`burn` amount exceeds the sender's balance. |
| `InsufficientAllowance` | 4 | `transfer_from` amount exceeds the spender's remaining allowance. |
| `InvalidAmount` | 5 | A negative/zero amount passed where a positive amount is required. |
| `NotAuthorized` | 6 | Reserved for authorization failures; current checks rely on `require_auth()` panicking directly rather than returning this variant. |

#### CLI usage

```sh
stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR --minter $MINTER_ADDR \
     --decimals 2 --name '"GuildWorkman Points"' --symbol '"GWP"'

stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- set_minter --new_minter $NEW_MINTER_ADDR

stellar contract invoke --id $LOYALTY --source minter --network testnet \
  -- mint --to $CLIENT_ADDR --amount 500

stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- balance --id $CLIENT_ADDR

stellar contract invoke --id $LOYALTY --source client --network testnet \
  -- transfer --from $CLIENT_ADDR --to $WORKER_ADDR --amount 100

stellar contract invoke --id $LOYALTY --source client --network testnet \
  -- approve --from $CLIENT_ADDR --spender $SPENDER_ADDR \
     --amount 200 --expiration_ledger 5000000

stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- allowance --from $CLIENT_ADDR --spender $SPENDER_ADDR

stellar contract invoke --id $LOYALTY --source spender --network testnet \
  -- transfer_from --spender $SPENDER_ADDR --from $CLIENT_ADDR \
     --to $WORKER_ADDR --amount 150

stellar contract invoke --id $LOYALTY --source client --network testnet \
  -- burn --from $CLIENT_ADDR --amount 50

stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- decimals
stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- name
stellar contract invoke --id $LOYALTY --source admin --network testnet \
  -- symbol
```

### loyalty-emissions

An emission engine layered on top of `loyalty-token`. It holds the token's
`minter` role and releases rewards as **linear vesting streams** rather than
lump-sum mints, gated by anti-abuse rate limits, with an admin reclaim path for
allocations left unclaimed past a deadline.

- `initialize(admin: Address, token: Address, config: Config)` — `config` is
  `{ window: u32, account_cap: i128, global_cap: i128 }`
- `create_schedule(beneficiary: Address, total: i128, start: u32, cliff: u32, duration: u32, reclaimable_after: u32)` — admin-only; `start: 0` means "start now"
- `claim(beneficiary: Address) -> i128` — beneficiary-authorized; mints the
  vested-and-unclaimed amount clamped to the current rate-limit budget, returns
  the amount minted
- `reclaim(beneficiary: Address) -> i128` — admin-only; after `reclaimable_after`,
  marks the schedule reclaimed and returns the never-minted remainder
- `vested(beneficiary) -> i128`, `claimable(beneficiary) -> i128`,
  `get_schedule(beneficiary) -> Schedule`, `get_config() -> Config`,
  `get_admin() -> Address`, `get_token() -> Address` — read-only views

#### Vesting & rate-limit model

A schedule is `{ total, start, cliff, duration }`. At ledger `t`:

- before `start + cliff` → nothing vested;
- `start + cliff` .. `start + duration` → `vested = total * (t - start) / duration`;
- at/after `start + duration` → fully vested (`total`).

`claimable = vested - claimed`, then clamped to the smaller of the account's and
the global remaining budget for the current window. Windows are fixed tumbling
windows of `window` ledgers (`ledger / window`): each account may claim at most
`account_cap` per window, and all accounts combined at most `global_cap` per
window. `create_schedule` requires `reclaimable_after >= start + duration`, so
the admin can never reclaim allocations that are still vesting.

#### Storage layout

| `DataKey` variant | Storage | Holds |
|---|---|---|
| `Admin` | instance | The admin `Address`; the only caller allowed to `create_schedule`/`reclaim`. |
| `Token` | instance | The `loyalty-token` contract `Address` this engine mints through. |
| `Config` | instance | `Config { window, account_cap, global_cap }`, fixed at `initialize`. |
| `Schedule(Address)` | persistent | A `Schedule { total, claimed, start, cliff, duration, reclaimable_after, reclaimed }` per beneficiary. |
| `AccountWindow(Address, u64)` | temporary | Units a given account has claimed in a given window index; enforces the per-account cap. Auto-expires ~2 windows after use. |
| `GlobalWindow(u64)` | temporary | Units all accounts have claimed in a given window index; enforces the global cap. |

#### Errors

| Variant | Code | Meaning |
|---|---|---|
| `AlreadyInitialized` | 1 | `initialize` called more than once. |
| `NotInitialized` | 2 | A method needing state was called before `initialize`. |
| `InvalidConfig` | 3 | `window == 0` or a non-positive cap passed to `initialize`. |
| `InvalidSchedule` | 4 | `total <= 0`, `duration == 0`, `cliff > duration`, or `reclaimable_after < start + duration`. |
| `ScheduleExists` | 5 | `create_schedule` for a beneficiary that already has one. |
| `ScheduleNotFound` | 6 | No schedule stored for that beneficiary. |
| `NothingToClaim` | 7 | Nothing has vested beyond what's already claimed. |
| `AccountRateLimited` | 8 | The account's per-window budget is already exhausted. |
| `GlobalRateLimited` | 9 | The global per-window budget is already exhausted. |
| `NotYetReclaimable` | 10 | `reclaim` called before `reclaimable_after`. |
| `AlreadyReclaimed` | 11 | `claim`/`reclaim` on an already-reclaimed schedule. |
| `NothingToReclaim` | 12 | `reclaim` when the schedule was already fully claimed. |

#### Authorization & safety notes

- **Trust root is the admin.** Only the admin can register or reclaim
  schedules. `claim` is authorized by the beneficiary themselves.
- **The engine, not the backend, is the token's `minter`.** After deployment
  the admin must call `loyalty-token.set_minter` with the emission contract's
  address; the token then only mints through vesting + rate-limit checks.
- **Reclaim cannot rug a vesting stream.** Because `reclaimable_after` is forced
  to be at or after the vesting end, the admin can only reclaim what a
  beneficiary chose not to claim after the stream fully vested — never
  still-vesting funds.
- **Reclaim is un-mint, not burn.** Reclaimed units were never minted, so no
  balance is touched; the schedule is simply closed.

#### CLI usage

```sh
# One-time setup (see the Deploy section for wiring the minter role).
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- initialize --admin $ADMIN_ADDR --token $LOYALTY_CONTRACT_ID \
     --config '{ "window": 17280, "account_cap": "1000", "global_cap": "100000" }'

# Admin registers a stream: 5000 points vesting over 30 days (starting now),
# reclaimable after ~31 days if left unclaimed.
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- create_schedule --beneficiary $WORKER_ADDR --total 5000 \
     --start 0 --cliff 0 --duration 518400 --reclaimable_after 535680

# Worker claims whatever has vested and fits the rate limit.
stellar contract invoke --id $EMISSIONS --source worker --network testnet \
  -- claim --beneficiary $WORKER_ADDR

# Read-only views.
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- vested --beneficiary $WORKER_ADDR
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- claimable --beneficiary $WORKER_ADDR

# Admin reclaims the unclaimed remainder after the deadline.
stellar contract invoke --id $EMISSIONS --source admin --network testnet \
  -- reclaim --beneficiary $WORKER_ADDR
```

## Security considerations / known limitations

- **Unaudited.** None of these contracts have had an independent security
  review. Do not move real funds through `escrow` or mint real value through
  `loyalty-token` on mainnet before getting one.
- **Trusted token contract.** `escrow` assumes the `token` address passed to
  `create_appointment` behaves like a well-formed SEP-41/Stellar Asset
  Contract. A malicious or buggy token contract (e.g. one that lies about
  transfer success) could break escrow's invariants.
- **No partial completion or partial refunds.** `escrow` is all-or-nothing:
  the full amount goes to either the client or the worker. There's no
  mechanism for a partially-completed job.
- **Single point of trust for admin.** The `escrow` admin unilaterally
  decides disputes, the `loyalty-token` admin unilaterally controls who can
  mint, and the `loyalty-emissions` admin unilaterally registers and reclaims
  vesting schedules. All are single-key roles with no timelock, multisig, or
  on-chain governance — compromising that key compromises the contract. Note
  `loyalty-emissions` deliberately blocks reclaiming still-vesting funds
  (`reclaimable_after >= start + duration`), so even a compromised admin cannot
  claw back what has already vested to a beneficiary before they claim it.
- **No spam/rate limiting beyond one-review-per-appointment.** `reputation`
  only prevents double-reviewing the same `appointment_id`; it does not
  prevent a client and worker from colluding to create fake appointments
  (that responsibility sits with whatever system calls `create_appointment`
  and `submit_review` with real appointment IDs — today, nothing does, since
  the Java backend isn't integrated yet).
- **No pause/upgrade mechanism.** None of the three contracts have an
  emergency pause switch or upgrade path built in; fixing a deployed bug
  means deploying a new contract and migrating state/callers manually.
- **Comments are not authenticated content.** `reputation`'s `comment` field
  is an arbitrary `String` supplied by the reviewer with no length cap or
  content moderation — treat it as untrusted user input wherever it's
  displayed.

## Notes / follow-ups

- `escrow` expects a standard Soroban token contract address (e.g. a Stellar
  Asset Contract wrapping USDC or XLM) for the `token` parameter — it does not
  handle native fiat payments, which stay on Paystack in the existing backend.
- These contracts have not been audited. Get an independent security review
  before moving any real funds through `escrow` or `loyalty-token` on mainnet.

## License

MIT — see [LICENSE](./LICENSE).
