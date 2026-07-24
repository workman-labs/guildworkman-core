#![no_std]

//! Loyalty-token emission engine for GuildWorkman.
//!
//! This contract sits *in front of* the [`loyalty-token`] SEP-41 token and owns
//! the token's `minter` role. Instead of the backend minting reward points in
//! one lump sum, the admin registers a per-account **emission schedule** that
//! releases points linearly over time (a stream), gated by anti-abuse rate
//! limits. Beneficiaries `claim` whatever has vested and is within their rate
//! budget; the emission contract mints exactly that amount into the underlying
//! token. Allocations that go unclaimed past a deadline can be **reclaimed** by
//! the admin so they don't inflate the outstanding supply forever.
//!
//! ## Why a separate contract
//!
//! Following the workspace convention (`escrow` / `reputation` / `loyalty-token`
//! are independent), emission policy — vesting math, rate limiting, reclaim — is
//! kept out of the token itself. The token stays a minimal SEP-41 ledger; this
//! contract is the only thing holding its `minter` role and is the single place
//! emission rules can change or be upgraded without touching balances.
//!
//! ## Streaming / vesting model
//!
//! A schedule is `{ total, start, cliff, duration }`. At ledger time `t`:
//! - before `start + cliff`: nothing has vested;
//! - from `start + cliff` to `start + duration`: linearly vested, i.e.
//!   `vested = total * (t - start) / duration`;
//! - at/after `start + duration`: fully vested (`vested = total`).
//!
//! `claimed` tracks what has already been minted. `claimable = vested - claimed`,
//! further clamped by the account's rate-limit budget for the current window.
//!
//! ## Rate limiting (anti-abuse)
//!
//! Two independent gates, both configured at `initialize` and enforced on every
//! `claim`:
//! - **Per-account window cap**: an account may claim at most `account_cap`
//!   units per `window` ledgers. The window is a fixed tumbling window derived
//!   from `ledger / window`, so a fresh budget opens each window boundary.
//! - **Global window cap**: across *all* accounts, at most `global_cap` units may
//!   be claimed per `window` ledgers — a circuit breaker against a mass-claim
//!   event draining emissions faster than intended.

use soroban_sdk::{
    contract, contractclient, contracterror, contractimpl, contracttype, Address, BytesN, Env, Vec,
};

use guildworkman_governance_guard as governance;
pub use guildworkman_governance_guard::{PendingRotation, PendingUpgrade};

/// Bump when this contract's storage layout actually changes shape and
/// needs a real transformation in `migrate`. There's no such change yet.
const CURRENT_STORAGE_VERSION: u32 = 1;

/// Minimal client for the underlying `loyalty-token` contract — just the one
/// function this engine needs. Declaring it locally (rather than depending on
/// the token crate) keeps the two contracts' exported symbols from colliding at
/// wasm link time while still giving us a typed cross-contract call.
#[contractclient(name = "LoyaltyTokenClient")]
pub trait LoyaltyToken {
    fn mint(env: Env, to: Address, amount: i128) -> Result<(), TokenError>;
}

/// The subset of the token's error surface reachable through `mint`.
#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub enum TokenError {
    NotInitialized = 2,
    InvalidAmount = 5,
}

/// Storage keys. See the README "Storage layout" table for the durability of
/// each and what it holds.
#[contracttype]
pub enum DataKey {
    Admin,
    Token,
    Config,
    /// Emission schedule for a beneficiary.
    Schedule(Address),
    /// `(account, window_index)` -> units already claimed by that account in
    /// that tumbling window. Temporary: it only needs to outlive one window.
    AccountWindow(Address, u64),
    /// `window_index` -> units claimed across all accounts in that window.
    GlobalWindow(u64),
}

/// Immutable-after-initialize rate-limit configuration.
#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Config {
    /// Length of a rate-limit window, in ledgers (~5s each).
    pub window: u32,
    /// Max units a single account may claim per window.
    pub account_cap: i128,
    /// Max units all accounts combined may claim per window.
    pub global_cap: i128,
}

/// A per-beneficiary linear vesting stream.
#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Schedule {
    /// Total units that will vest over the life of the stream.
    pub total: i128,
    /// Units already claimed (minted) so far.
    pub claimed: i128,
    /// Ledger at which vesting begins.
    pub start: u32,
    /// Ledgers after `start` before *any* units vest (a cliff).
    pub cliff: u32,
    /// Ledgers after `start` over which `total` vests linearly.
    pub duration: u32,
    /// Ledger at/after which the admin may reclaim the still-unclaimed remainder.
    pub reclaimable_after: u32,
    /// Set once the admin reclaims; a reclaimed schedule can no longer be claimed.
    pub reclaimed: bool,
}

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub enum Error {
    AlreadyInitialized = 1,
    NotInitialized = 2,
    InvalidConfig = 3,
    InvalidSchedule = 4,
    ScheduleExists = 5,
    ScheduleNotFound = 6,
    NothingToClaim = 7,
    AccountRateLimited = 8,
    GlobalRateLimited = 9,
    NotYetReclaimable = 10,
    AlreadyReclaimed = 11,
    NothingToReclaim = 12,
    // --- Upgrade governance (see guildworkman-governance-guard) ---
    GovernanceAlreadyInitialized = 13,
    GovernanceNotInitialized = 14,
    InvalidThreshold = 15,
    DuplicateSigner = 16,
    NotASigner = 17,
    NoPendingUpgrade = 18,
    AlreadyApproved = 19,
    ProposalExpired = 20,
    HashMismatch = 21,
    AlreadyMigrated = 22,
    NothingToMigrate = 23,
    // --- Signer rotation (see guildworkman-governance-guard) ---
    NoPendingRotation = 24,
    RotationMismatch = 25,
    RotationNotReady = 26,
    RotationTimelockActive = 27,
    RotationExpired = 28,
    RotationInProgress = 29,
}

impl From<governance::GovernanceError> for Error {
    fn from(e: governance::GovernanceError) -> Self {
        match e {
            governance::GovernanceError::AlreadyInitialized => Error::GovernanceAlreadyInitialized,
            governance::GovernanceError::NotInitialized => Error::GovernanceNotInitialized,
            governance::GovernanceError::InvalidThreshold => Error::InvalidThreshold,
            governance::GovernanceError::DuplicateSigner => Error::DuplicateSigner,
            governance::GovernanceError::NotASigner => Error::NotASigner,
            governance::GovernanceError::NoPendingUpgrade => Error::NoPendingUpgrade,
            governance::GovernanceError::AlreadyApproved => Error::AlreadyApproved,
            governance::GovernanceError::ProposalExpired => Error::ProposalExpired,
            governance::GovernanceError::HashMismatch => Error::HashMismatch,
            governance::GovernanceError::AlreadyMigrated => Error::AlreadyMigrated,
            governance::GovernanceError::NoPendingRotation => Error::NoPendingRotation,
            governance::GovernanceError::RotationMismatch => Error::RotationMismatch,
            governance::GovernanceError::RotationNotReady => Error::RotationNotReady,
            governance::GovernanceError::RotationTimelockActive => Error::RotationTimelockActive,
            governance::GovernanceError::RotationExpired => Error::RotationExpired,
            governance::GovernanceError::RotationInProgress => Error::RotationInProgress,
        }
    }
}

const DAY_IN_LEDGERS: u32 = 17_280;
const INSTANCE_BUMP_AMOUNT: u32 = DAY_IN_LEDGERS * 60;
const INSTANCE_LIFETIME_THRESHOLD: u32 = DAY_IN_LEDGERS * 30;
const SCHEDULE_BUMP_AMOUNT: u32 = DAY_IN_LEDGERS * 30;
const SCHEDULE_LIFETIME_THRESHOLD: u32 = DAY_IN_LEDGERS * 29;

#[contract]
pub struct LoyaltyEmissions;

#[contractimpl]
impl LoyaltyEmissions {
    /// One-time setup.
    ///
    /// - `admin` registers/reclaims schedules and is the trust root.
    /// - `token` is the deployed `loyalty-token` contract this engine mints
    ///   through. The engine's own address must be set as that token's `minter`
    ///   (via `loyalty-token.set_minter`) for `claim` to succeed.
    /// - `config` fixes the rate-limit windows and caps for the life of the
    ///   contract.
    pub fn initialize(
        env: Env,
        admin: Address,
        token: Address,
        config: Config,
        governance_init: governance::GovernanceInit,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::Admin) {
            return Err(Error::AlreadyInitialized);
        }
        admin.require_auth();
        Self::validate_config(&config)?;
        governance::init_governance(&env, governance_init)?;

        env.storage().instance().set(&DataKey::Admin, &admin);
        env.storage().instance().set(&DataKey::Token, &token);
        env.storage().instance().set(&DataKey::Config, &config);
        env.storage()
            .instance()
            .extend_ttl(INSTANCE_LIFETIME_THRESHOLD, INSTANCE_BUMP_AMOUNT);
        Ok(())
    }

    // ----- Upgrade governance -----

    pub fn propose_upgrade(
        env: Env,
        proposer: Address,
        wasm_hash: BytesN<32>,
    ) -> Result<bool, Error> {
        let ready = governance::propose_upgrade(&env, proposer, wasm_hash.clone())?;
        if ready {
            env.deployer().update_current_contract_wasm(wasm_hash);
        }
        Ok(ready)
    }

    pub fn approve_upgrade(
        env: Env,
        approver: Address,
        wasm_hash: BytesN<32>,
    ) -> Result<bool, Error> {
        let ready = governance::approve_upgrade(&env, approver, wasm_hash.clone())?;
        if ready {
            env.deployer().update_current_contract_wasm(wasm_hash);
        }
        Ok(ready)
    }

    pub fn cancel_upgrade(env: Env, caller: Address) -> Result<(), Error> {
        governance::cancel_upgrade(&env, caller).map_err(Into::into)
    }

    // ----- Signer rotation -----

    /// Opens a timelocked proposal to rotate the governance signer set and
    /// threshold, authorized by the current signers at the current
    /// threshold. Reaching threshold only *schedules* the rotation;
    /// `execute_signer_rotation` applies it after the timelock. Returns
    /// `true` if this call reached threshold.
    pub fn propose_signer_rotation(
        env: Env,
        proposer: Address,
        new_signers: Vec<Address>,
        new_threshold: u32,
    ) -> Result<bool, Error> {
        governance::propose_signer_rotation(&env, proposer, new_signers, new_threshold)
            .map_err(Into::into)
    }

    /// Approves the pending signer rotation. Returns `true` when this
    /// approval reaches threshold and schedules the rotation.
    pub fn approve_signer_rotation(
        env: Env,
        approver: Address,
        new_signers: Vec<Address>,
        new_threshold: u32,
    ) -> Result<bool, Error> {
        governance::approve_signer_rotation(&env, approver, new_signers, new_threshold)
            .map_err(Into::into)
    }

    /// Applies a scheduled rotation once its timelock has elapsed. Any
    /// current signer may trigger it.
    pub fn execute_signer_rotation(env: Env, caller: Address) -> Result<(), Error> {
        governance::execute_signer_rotation(&env, caller).map_err(Into::into)
    }

    pub fn get_pending_rotation(env: Env) -> Option<PendingRotation> {
        governance::get_pending_rotation(&env)
    }

    pub fn migrate(env: Env, signer: Address) -> Result<(), Error> {
        governance::require_signer(&env, &signer)?;
        if governance::current_storage_version(&env) >= CURRENT_STORAGE_VERSION {
            return Err(Error::NothingToMigrate);
        }
        // No storage shape has changed since v1 — nothing to transform yet.
        governance::mark_migrated(&env, CURRENT_STORAGE_VERSION)?;
        Ok(())
    }

    pub fn get_signers(env: Env) -> Vec<Address> {
        governance::get_signers(&env)
    }

    pub fn get_upgrade_threshold(env: Env) -> u32 {
        governance::get_threshold(&env)
    }

    pub fn get_pending_upgrade(env: Env) -> Option<PendingUpgrade> {
        governance::get_pending_upgrade(&env)
    }

    pub fn get_storage_version(env: Env) -> u32 {
        governance::current_storage_version(&env)
    }

    /// Admin-only: register a linear vesting stream for `beneficiary`.
    ///
    /// `start` defaults to the current ledger when `0` is passed. `cliff` must
    /// not exceed `duration`, and `duration` must be positive. Only one active
    /// schedule per beneficiary; reclaim (or a future `total` top-up flow) is
    /// required before re-registering.
    #[allow(clippy::too_many_arguments)]
    pub fn create_schedule(
        env: Env,
        beneficiary: Address,
        total: i128,
        start: u32,
        cliff: u32,
        duration: u32,
        reclaimable_after: u32,
    ) -> Result<(), Error> {
        let admin = Self::require_admin(&env)?;
        admin.require_auth();

        let key = DataKey::Schedule(beneficiary);
        if env.storage().persistent().has(&key) {
            return Err(Error::ScheduleExists);
        }

        let start = if start == 0 {
            env.ledger().sequence()
        } else {
            start
        };
        if total <= 0 || duration == 0 || cliff > duration {
            return Err(Error::InvalidSchedule);
        }
        // Reclaim must not fire before the stream has fully vested, otherwise
        // the admin could rug still-vesting allocations out from under a
        // beneficiary.
        if reclaimable_after < start.saturating_add(duration) {
            return Err(Error::InvalidSchedule);
        }

        let schedule = Schedule {
            total,
            claimed: 0,
            start,
            cliff,
            duration,
            reclaimable_after,
            reclaimed: false,
        };
        env.storage().persistent().set(&key, &schedule);
        env.storage().persistent().extend_ttl(
            &key,
            SCHEDULE_LIFETIME_THRESHOLD,
            SCHEDULE_BUMP_AMOUNT,
        );
        Self::bump_instance(&env);
        Ok(())
    }

    /// Beneficiary-authorized: mint everything that has vested and fits within
    /// the current rate-limit budget. Returns the amount actually minted.
    ///
    /// The claim is clamped (not rejected) to the smaller of the vested
    /// remainder, the account's remaining per-window budget, and the global
    /// remaining per-window budget. A claim only errors if there is genuinely
    /// nothing available, or if *both* budgets are already exhausted for the
    /// window (so the caller can distinguish "come back later").
    pub fn claim(env: Env, beneficiary: Address) -> Result<i128, Error> {
        beneficiary.require_auth();

        let key = DataKey::Schedule(beneficiary.clone());
        let mut schedule = Self::read_schedule(&env, &key)?;
        if schedule.reclaimed {
            return Err(Error::AlreadyReclaimed);
        }

        let vested = Self::vested_amount(&env, &schedule);
        let claimable = vested - schedule.claimed;
        if claimable <= 0 {
            return Err(Error::NothingToClaim);
        }

        let config = Self::read_config(&env);
        let window_index = Self::window_index(&env, &config);

        // Per-account budget for this window.
        let account_used = Self::account_used(&env, &beneficiary, window_index);
        let account_budget = config.account_cap - account_used;
        if account_budget <= 0 {
            return Err(Error::AccountRateLimited);
        }

        // Global budget for this window.
        let global_used = Self::global_used(&env, window_index);
        let global_budget = config.global_cap - global_used;
        if global_budget <= 0 {
            return Err(Error::GlobalRateLimited);
        }

        let amount = claimable.min(account_budget).min(global_budget);

        // Mint through the underlying loyalty token. This contract must hold the
        // token's `minter` role, so the cross-contract call carries this
        // contract's own address as the authorizing `minter`.
        let token = Self::read_token(&env);
        LoyaltyTokenClient::new(&env, &token).mint(&beneficiary, &amount);

        schedule.claimed += amount;
        env.storage().persistent().set(&key, &schedule);
        env.storage().persistent().extend_ttl(
            &key,
            SCHEDULE_LIFETIME_THRESHOLD,
            SCHEDULE_BUMP_AMOUNT,
        );

        Self::set_account_used(
            &env,
            &beneficiary,
            window_index,
            account_used + amount,
            &config,
        );
        Self::set_global_used(&env, window_index, global_used + amount, &config);
        Self::bump_instance(&env);
        Ok(amount)
    }

    /// Admin-only: reclaim the still-unclaimed remainder of a schedule once it
    /// has passed `reclaimable_after`. Marks the schedule `reclaimed` so no
    /// further claims are possible. Returns the reclaimed (un-minted) amount.
    ///
    /// Nothing is minted or burned here — reclaimed units simply never enter
    /// circulation, since the engine only ever mints on `claim`.
    pub fn reclaim(env: Env, beneficiary: Address) -> Result<i128, Error> {
        let admin = Self::require_admin(&env)?;
        admin.require_auth();

        let key = DataKey::Schedule(beneficiary);
        let mut schedule = Self::read_schedule(&env, &key)?;
        if schedule.reclaimed {
            return Err(Error::AlreadyReclaimed);
        }
        if env.ledger().sequence() < schedule.reclaimable_after {
            return Err(Error::NotYetReclaimable);
        }

        let remainder = schedule.total - schedule.claimed;
        if remainder <= 0 {
            return Err(Error::NothingToReclaim);
        }

        schedule.reclaimed = true;
        env.storage().persistent().set(&key, &schedule);
        env.storage().persistent().extend_ttl(
            &key,
            SCHEDULE_LIFETIME_THRESHOLD,
            SCHEDULE_BUMP_AMOUNT,
        );
        Self::bump_instance(&env);
        Ok(remainder)
    }

    // --- views ---

    /// Total units vested for `beneficiary` at the current ledger, ignoring
    /// what has already been claimed.
    pub fn vested(env: Env, beneficiary: Address) -> Result<i128, Error> {
        let schedule = Self::read_schedule(&env, &DataKey::Schedule(beneficiary))?;
        Ok(Self::vested_amount(&env, &schedule))
    }

    /// Units `beneficiary` could claim *right now* considering vesting and both
    /// rate-limit budgets for the current window. `0` if reclaimed or nothing
    /// available.
    pub fn claimable(env: Env, beneficiary: Address) -> Result<i128, Error> {
        let schedule = Self::read_schedule(&env, &DataKey::Schedule(beneficiary.clone()))?;
        if schedule.reclaimed {
            return Ok(0);
        }
        let vested = Self::vested_amount(&env, &schedule);
        let claimable = vested - schedule.claimed;
        if claimable <= 0 {
            return Ok(0);
        }
        let config = Self::read_config(&env);
        let window_index = Self::window_index(&env, &config);
        let account_budget =
            config.account_cap - Self::account_used(&env, &beneficiary, window_index);
        let global_budget = config.global_cap - Self::global_used(&env, window_index);
        let budget = account_budget.max(0).min(global_budget.max(0));
        Ok(claimable.min(budget))
    }

    pub fn get_schedule(env: Env, beneficiary: Address) -> Result<Schedule, Error> {
        Self::read_schedule(&env, &DataKey::Schedule(beneficiary))
    }

    pub fn get_config(env: Env) -> Result<Config, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Config)
            .ok_or(Error::NotInitialized)
    }

    pub fn get_admin(env: Env) -> Result<Address, Error> {
        Self::require_admin(&env)
    }

    pub fn get_token(env: Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Token)
            .ok_or(Error::NotInitialized)
    }

    // --- internal helpers ---

    fn validate_config(config: &Config) -> Result<(), Error> {
        if config.window == 0 || config.account_cap <= 0 || config.global_cap <= 0 {
            return Err(Error::InvalidConfig);
        }
        Ok(())
    }

    /// Linear vesting with a cliff, saturating so nothing can overflow `i128`.
    fn vested_amount(env: &Env, schedule: &Schedule) -> i128 {
        let now = env.ledger().sequence();
        if now < schedule.start.saturating_add(schedule.cliff) {
            return 0;
        }
        let end = schedule.start.saturating_add(schedule.duration);
        if now >= end {
            return schedule.total;
        }
        // start <= now < end, so elapsed fits in the [1, duration) range.
        let elapsed = (now - schedule.start) as i128;
        let duration = schedule.duration as i128;
        // total * elapsed / duration; total and elapsed are both non-negative
        // and bounded, elapsed < duration so the product stays well within i128.
        (schedule.total.saturating_mul(elapsed)) / duration
    }

    fn window_index(env: &Env, config: &Config) -> u64 {
        env.ledger().sequence() as u64 / config.window as u64
    }

    fn account_used(env: &Env, account: &Address, window_index: u64) -> i128 {
        env.storage()
            .temporary()
            .get(&DataKey::AccountWindow(account.clone(), window_index))
            .unwrap_or(0)
    }

    fn set_account_used(
        env: &Env,
        account: &Address,
        window_index: u64,
        used: i128,
        config: &Config,
    ) {
        let key = DataKey::AccountWindow(account.clone(), window_index);
        env.storage().temporary().set(&key, &used);
        // The counter only needs to outlive its window; keep it a little longer
        // to be safe against ledger-time jitter.
        let ttl = config.window.saturating_mul(2).max(1);
        env.storage().temporary().extend_ttl(&key, ttl, ttl);
    }

    fn global_used(env: &Env, window_index: u64) -> i128 {
        env.storage()
            .temporary()
            .get(&DataKey::GlobalWindow(window_index))
            .unwrap_or(0)
    }

    fn set_global_used(env: &Env, window_index: u64, used: i128, config: &Config) {
        let key = DataKey::GlobalWindow(window_index);
        env.storage().temporary().set(&key, &used);
        let ttl = config.window.saturating_mul(2).max(1);
        env.storage().temporary().extend_ttl(&key, ttl, ttl);
    }

    fn require_admin(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Admin)
            .ok_or(Error::NotInitialized)
    }

    fn read_token(env: &Env) -> Address {
        env.storage()
            .instance()
            .get(&DataKey::Token)
            .expect("not initialized")
    }

    fn read_config(env: &Env) -> Config {
        env.storage()
            .instance()
            .get(&DataKey::Config)
            .expect("not initialized")
    }

    fn read_schedule(env: &Env, key: &DataKey) -> Result<Schedule, Error> {
        env.storage()
            .persistent()
            .get(key)
            .ok_or(Error::ScheduleNotFound)
    }

    fn bump_instance(env: &Env) {
        env.storage()
            .instance()
            .extend_ttl(INSTANCE_LIFETIME_THRESHOLD, INSTANCE_BUMP_AMOUNT);
    }
}

#[cfg(test)]
mod test;
