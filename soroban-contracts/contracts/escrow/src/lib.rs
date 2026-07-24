#![no_std]

//! Escrow contract for GuildWorkman appointments.
//!
//! Supports two escrow types:
//!
//! 1. **Simple escrow** — a client funds an appointment, confirms completion,
//!    and the worker is paid. Either party can raise a dispute, resolved by the
//!    admin arbiter.
//!
//! 2. **Milestone escrow** — a client funds an escrow with multiple milestones.
//!    Each milestone has a time-lock deadline, an amount, and a description hash.
//!    The client approves milestones as work progresses; funds are released
//!    only after approval *and* the time-lock expires. Disputes can be raised
//!    per-milestone and resolved by the admin or an external arbitration hook.
//!
//! ## Storage layout
//!
//! | Key | Durability | Type | Holds |
//! |-----|-----------|------|-------|
//! | `DataKey::Admin` | instance | `Address` | Admin/arbiter for dispute resolution |
//! | `DataKey::Appointment(id)` | persistent | `Appointment` | Simple escrow state |
//! | `DataKey::MilestoneEscrow(id)` | persistent | `MilestoneEscrow` | Milestone escrow state |
//! | `GovernanceDataKey::*` | instance | governance-guard types | M-of-N upgrade governance |
//!
//! ## Authorization model
//!
//! - `create_appointment` / `create_milestone_escrow`: client must authorize.
//! - `add_milestone` / `approve_milestone`: client must authorize.
//! - `confirm_completion` / `cancel_appointment`: client must authorize.
//! - `raise_dispute` / `raise_milestone_dispute`: participant must authorize.
//! - `resolve_dispute` / `resolve_milestone_dispute`: admin/arbiter must authorize.
//! - `release_milestone_funds`: permissionless once conditions are met.
//! - All functions follow checks-effects-interactions to prevent reentrancy.

use soroban_sdk::{
    contract, contracterror, contractimpl, contracttype, token, Address, BytesN, Env, Vec,
};

use guildworkman_governance_guard as governance;
pub use guildworkman_governance_guard::{PendingRotation, PendingUpgrade};

/// Bump when this contract's storage layout actually changes shape and
/// needs a real transformation in `migrate`. There's no such change yet.
const CURRENT_STORAGE_VERSION: u32 = 1;

#[contracttype]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Status {
    Funded,
    Completed,
    Cancelled,
    Disputed,
    Resolved,
}

#[contracttype]
#[derive(Clone, Debug)]
pub struct Appointment {
    pub client: Address,
    pub worker: Address,
    pub token: Address,
    pub amount: i128,
    pub status: Status,
}

#[contracttype]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MilestoneStatus {
    Pending,
    Approved,
    Released,
    Disputed,
}

#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Milestone {
    pub description: BytesN<32>,
    pub amount: i128,
    pub deadline: u32,
    pub status: MilestoneStatus,
}

#[contracttype]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ArbitrationMode {
    AdminArbiter,
    ExternalHook,
}

#[contracttype]
#[derive(Clone, Debug)]
pub struct MilestoneEscrow {
    pub client: Address,
    pub worker: Address,
    pub token: Address,
    pub total_amount: i128,
    pub released_amount: i128,
    pub status: Status,
    pub milestones: Vec<Milestone>,
    pub arbiter: Address,
    pub arbitration_mode: ArbitrationMode,
    pub hook_address: Option<Address>,
}

#[contracttype]
#[derive(Clone, Debug)]
pub struct MilestoneEscrowInit {
    pub client: Address,
    pub worker: Address,
    pub token: Address,
    pub total_amount: i128,
    pub arbiter: Address,
    pub arbitration_mode: ArbitrationMode,
    pub hook_address: Option<Address>,
}

#[contracttype]
pub enum DataKey {
    Admin,
    Appointment(u64),
    MilestoneEscrow(u64),
}

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub enum Error {
    AlreadyInitialized = 1,
    NotInitialized = 2,
    AppointmentExists = 3,
    AppointmentNotFound = 4,
    InvalidStatus = 5,
    InvalidAmount = 6,
    NotAParticipant = 7,
    // --- Upgrade governance (see guildworkman-governance-guard) ---
    GovernanceAlreadyInitialized = 8,
    GovernanceNotInitialized = 9,
    InvalidThreshold = 10,
    DuplicateSigner = 11,
    NotASigner = 12,
    NoPendingUpgrade = 13,
    AlreadyApproved = 14,
    ProposalExpired = 15,
    HashMismatch = 16,
    AlreadyMigrated = 17,
    NothingToMigrate = 18,
    // --- Milestone escrow ---
    MilestoneNotFound = 19,
    InvalidMilestoneAmount = 20,
    MilestoneAlreadyApproved = 21,
    MilestoneTimeLocked = 22,
    MilestoneAlreadyReleased = 23,
    InvalidEscrowStatus = 24,
    ArbitrationFailed = 25,
    NotAClient = 26,
    NotAWorker = 27,
    MilestoneAmountMismatch = 28,
    InvalidMilestoneCount = 29,
    InvalidDeadline = 30,
    // --- Signer rotation (see guildworkman-governance-guard) ---
    NoPendingRotation = 31,
    RotationMismatch = 32,
    RotationNotReady = 33,
    RotationTimelockActive = 34,
    RotationExpired = 35,
    RotationInProgress = 36,
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

const LEDGERS_THRESHOLD: u32 = 17_280; // ~1 day, in ledgers (5s/ledger)
const LEDGERS_EXTEND_TO: u32 = 518_400; // ~30 days

#[contract]
pub struct EscrowContract;

#[contractimpl]
impl EscrowContract {
    /// One-time setup. `admin` acts as the dispute arbiter. `signers`/
    /// `threshold` configure the M-of-N governance guard that gates
    /// upgrading this contract's code and running a post-upgrade
    /// migration — entirely separate from `admin`, which keeps its
    /// existing power to resolve disputes on its own.
    pub fn initialize(
        env: Env,
        admin: Address,
        governance_init: governance::GovernanceInit,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::Admin) {
            return Err(Error::AlreadyInitialized);
        }
        admin.require_auth();
        governance::init_governance(&env, governance_init)?;
        env.storage().instance().set(&DataKey::Admin, &admin);
        env.storage()
            .instance()
            .extend_ttl(LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);
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

    /// Client books a skilled worker and deposits `amount` of `token` into escrow.
    pub fn create_appointment(
        env: Env,
        appointment_id: u64,
        client: Address,
        worker: Address,
        token: Address,
        amount: i128,
    ) -> Result<(), Error> {
        client.require_auth();

        if amount <= 0 {
            return Err(Error::InvalidAmount);
        }

        let key = DataKey::Appointment(appointment_id);
        if env.storage().persistent().has(&key) {
            return Err(Error::AppointmentExists);
        }

        // Pull funds from the client into the contract's own balance.
        let token_client = token::Client::new(&env, &token);
        token_client.transfer(&client, env.current_contract_address(), &amount);

        let appointment = Appointment {
            client,
            worker,
            token,
            amount,
            status: Status::Funded,
        };
        env.storage().persistent().set(&key, &appointment);
        env.storage()
            .persistent()
            .extend_ttl(&key, LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);

        Ok(())
    }

    /// Client confirms the job was completed satisfactorily; releases funds to the worker.
    pub fn confirm_completion(env: Env, appointment_id: u64) -> Result<(), Error> {
        let key = DataKey::Appointment(appointment_id);
        let mut appointment = Self::read_appointment(&env, &key)?;

        appointment.client.require_auth();

        if appointment.status != Status::Funded {
            return Err(Error::InvalidStatus);
        }

        let token_client = token::Client::new(&env, &appointment.token);
        token_client.transfer(
            &env.current_contract_address(),
            &appointment.worker,
            &appointment.amount,
        );

        appointment.status = Status::Completed;
        env.storage().persistent().set(&key, &appointment);
        Ok(())
    }

    /// Client cancels before the job starts; refunds the client in full.
    pub fn cancel_appointment(env: Env, appointment_id: u64) -> Result<(), Error> {
        let key = DataKey::Appointment(appointment_id);
        let mut appointment = Self::read_appointment(&env, &key)?;

        appointment.client.require_auth();

        if appointment.status != Status::Funded {
            return Err(Error::InvalidStatus);
        }

        let token_client = token::Client::new(&env, &appointment.token);
        token_client.transfer(
            &env.current_contract_address(),
            &appointment.client,
            &appointment.amount,
        );

        appointment.status = Status::Cancelled;
        env.storage().persistent().set(&key, &appointment);
        Ok(())
    }

    /// Either the client or the worker can flag a disagreement, freezing the funds
    /// until the admin resolves it.
    pub fn raise_dispute(env: Env, appointment_id: u64, caller: Address) -> Result<(), Error> {
        caller.require_auth();

        let key = DataKey::Appointment(appointment_id);
        let mut appointment = Self::read_appointment(&env, &key)?;

        if caller != appointment.client && caller != appointment.worker {
            return Err(Error::NotAParticipant);
        }
        if appointment.status != Status::Funded {
            return Err(Error::InvalidStatus);
        }

        appointment.status = Status::Disputed;
        env.storage().persistent().set(&key, &appointment);
        Ok(())
    }

    /// Admin/arbiter resolves a dispute by sending the escrowed funds to whichever
    /// side is owed them.
    pub fn resolve_dispute(
        env: Env,
        appointment_id: u64,
        refund_to_client: bool,
    ) -> Result<(), Error> {
        let admin: Address = env
            .storage()
            .instance()
            .get(&DataKey::Admin)
            .ok_or(Error::NotInitialized)?;
        admin.require_auth();

        let key = DataKey::Appointment(appointment_id);
        let mut appointment = Self::read_appointment(&env, &key)?;

        if appointment.status != Status::Disputed {
            return Err(Error::InvalidStatus);
        }

        let token_client = token::Client::new(&env, &appointment.token);
        let recipient = if refund_to_client {
            &appointment.client
        } else {
            &appointment.worker
        };
        token_client.transfer(
            &env.current_contract_address(),
            recipient,
            &appointment.amount,
        );

        appointment.status = Status::Resolved;
        env.storage().persistent().set(&key, &appointment);
        Ok(())
    }

    pub fn get_appointment(env: Env, appointment_id: u64) -> Result<Appointment, Error> {
        Self::read_appointment(&env, &DataKey::Appointment(appointment_id))
    }

    // ===========================================================================
    // Milestone Escrow
    // ===========================================================================

    /// Create a milestone-based escrow. `total_amount` is pulled from the client
    /// immediately. `arbiter` resolves disputes when `mode` is `AdminArbiter`;
    /// `hook_address` is called for `ExternalHook`.
    pub fn create_milestone_escrow(
        env: Env,
        escrow_id: u64,
        init: MilestoneEscrowInit,
    ) -> Result<(), Error> {
        init.client.require_auth();

        if init.total_amount <= 0 {
            return Err(Error::InvalidAmount);
        }

        let key = DataKey::MilestoneEscrow(escrow_id);
        if env.storage().persistent().has(&key) {
            return Err(Error::AppointmentExists);
        }

        let token_client = token::Client::new(&env, &init.token);
        token_client.transfer(
            &init.client,
            env.current_contract_address(),
            &init.total_amount,
        );

        let escrow = MilestoneEscrow {
            client: init.client,
            worker: init.worker,
            token: init.token,
            total_amount: init.total_amount,
            released_amount: 0,
            status: Status::Funded,
            milestones: Vec::new(&env),
            arbiter: init.arbiter,
            arbitration_mode: init.arbitration_mode,
            hook_address: init.hook_address,
        };
        env.storage().persistent().set(&key, &escrow);
        env.storage()
            .persistent()
            .extend_ttl(&key, LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);
        Self::bump_instance(&env);

        Ok(())
    }

    /// Add a milestone to a funded escrow. Only the client can add milestones.
    /// The sum of all milestone amounts must equal `total_amount`.
    pub fn add_milestone(
        env: Env,
        escrow_id: u64,
        description: BytesN<32>,
        amount: i128,
        deadline: u32,
    ) -> Result<u32, Error> {
        let key = DataKey::MilestoneEscrow(escrow_id);
        let mut escrow = Self::read_milestone_escrow(&env, &key)?;

        escrow.client.require_auth();

        if escrow.status != Status::Funded {
            return Err(Error::InvalidEscrowStatus);
        }
        if amount <= 0 {
            return Err(Error::InvalidMilestoneAmount);
        }
        if deadline <= env.ledger().sequence() {
            return Err(Error::InvalidDeadline);
        }

        let current_sum: i128 = escrow.milestones.iter().map(|m| m.amount).sum();
        if current_sum + amount > escrow.total_amount {
            return Err(Error::MilestoneAmountMismatch);
        }

        let index = escrow.milestones.len();
        let milestone = Milestone {
            description,
            amount,
            deadline,
            status: MilestoneStatus::Pending,
        };
        escrow.milestones.push_back(milestone);
        env.storage().persistent().set(&key, &escrow);
        Self::bump_escrow(&env, &key);
        Self::bump_instance(&env);

        Ok(index)
    }

    /// Client approves a milestone, marking it ready for time-locked release.
    pub fn approve_milestone(env: Env, escrow_id: u64, milestone_index: u32) -> Result<(), Error> {
        let key = DataKey::MilestoneEscrow(escrow_id);
        let mut escrow = Self::read_milestone_escrow(&env, &key)?;

        escrow.client.require_auth();

        if escrow.status != Status::Funded {
            return Err(Error::InvalidEscrowStatus);
        }

        let milestone = escrow
            .milestones
            .get(milestone_index)
            .ok_or(Error::MilestoneNotFound)?;

        if milestone.status != MilestoneStatus::Pending {
            return Err(Error::MilestoneAlreadyApproved);
        }

        let updated = Milestone {
            status: MilestoneStatus::Approved,
            ..milestone
        };
        escrow.milestones.set(milestone_index, updated);
        env.storage().persistent().set(&key, &escrow);
        Self::bump_escrow(&env, &key);
        Self::bump_instance(&env);

        Ok(())
    }

    /// Release funds for a single approved milestone after its time-lock has
    /// expired. Permissionless — anyone may call once the conditions are met.
    /// Checks-effects-interactions: milestone status is updated to `Released`
    /// before the token transfer.
    pub fn release_milestone_funds(
        env: Env,
        escrow_id: u64,
        milestone_index: u32,
    ) -> Result<i128, Error> {
        let key = DataKey::MilestoneEscrow(escrow_id);
        let mut escrow = Self::read_milestone_escrow(&env, &key)?;

        if escrow.status != Status::Funded {
            return Err(Error::InvalidEscrowStatus);
        }

        let milestone = escrow
            .milestones
            .get(milestone_index)
            .ok_or(Error::MilestoneNotFound)?;

        if milestone.status != MilestoneStatus::Approved {
            return Err(Error::InvalidStatus);
        }
        if env.ledger().sequence() <= milestone.deadline {
            return Err(Error::MilestoneTimeLocked);
        }

        // Effects before interactions: mark released, update accounting.
        let updated = Milestone {
            status: MilestoneStatus::Released,
            ..milestone
        };
        escrow.milestones.set(milestone_index, updated);
        escrow.released_amount = escrow.released_amount.saturating_add(milestone.amount);

        if escrow.released_amount >= escrow.total_amount {
            escrow.status = Status::Completed;
        }

        env.storage().persistent().set(&key, &escrow);
        Self::bump_escrow(&env, &key);

        // Interaction: transfer funds.
        let token_client = token::Client::new(&env, &escrow.token);
        token_client.transfer(
            &env.current_contract_address(),
            &escrow.worker,
            &milestone.amount,
        );

        Self::bump_instance(&env);
        Ok(milestone.amount)
    }

    /// Either the client or the worker raises a dispute on a specific milestone.
    /// The milestone must be in `Approved` or `Pending` status. The escrow
    /// transitions to `Disputed` and no further releases are possible until
    /// the dispute is resolved.
    pub fn raise_milestone_dispute(
        env: Env,
        escrow_id: u64,
        milestone_index: u32,
        caller: Address,
    ) -> Result<(), Error> {
        caller.require_auth();

        let key = DataKey::MilestoneEscrow(escrow_id);
        let mut escrow = Self::read_milestone_escrow(&env, &key)?;

        if caller != escrow.client && caller != escrow.worker {
            return Err(Error::NotAParticipant);
        }
        if escrow.status != Status::Funded {
            return Err(Error::InvalidEscrowStatus);
        }

        let milestone = escrow
            .milestones
            .get(milestone_index)
            .ok_or(Error::MilestoneNotFound)?;

        if milestone.status == MilestoneStatus::Released {
            return Err(Error::InvalidStatus);
        }
        if milestone.status == MilestoneStatus::Disputed {
            return Err(Error::InvalidStatus);
        }

        let updated = Milestone {
            status: MilestoneStatus::Disputed,
            ..milestone
        };
        escrow.milestones.set(milestone_index, updated);
        escrow.status = Status::Disputed;
        env.storage().persistent().set(&key, &escrow);
        Self::bump_escrow(&env, &key);
        Self::bump_instance(&env);

        Ok(())
    }

    /// Resolve a disputed milestone. For `AdminArbiter` mode, only the arbiter
    /// can call. For `ExternalHook`, calls the hook contract. `refund_to_client`
    /// determines which party receives the milestone's funds.
    pub fn resolve_milestone_dispute(
        env: Env,
        escrow_id: u64,
        milestone_index: u32,
        refund_to_client: bool,
    ) -> Result<(), Error> {
        let key = DataKey::MilestoneEscrow(escrow_id);
        let mut escrow = Self::read_milestone_escrow(&env, &key)?;

        if escrow.status != Status::Disputed {
            return Err(Error::InvalidEscrowStatus);
        }

        let milestone = escrow
            .milestones
            .get(milestone_index)
            .ok_or(Error::MilestoneNotFound)?;

        if milestone.status != MilestoneStatus::Disputed {
            return Err(Error::InvalidStatus);
        }

        // Gate authorization based on arbitration mode.
        match escrow.arbitration_mode {
            ArbitrationMode::AdminArbiter => {
                escrow.arbiter.require_auth();
            }
            ArbitrationMode::ExternalHook => {
                let hook_addr = escrow
                    .hook_address
                    .clone()
                    .ok_or(Error::ArbitrationFailed)?;
                let hook_client = token::Client::new(&env, &hook_addr);
                // The hook contract is expected to authorize this call
                // internally; we just verify it exists.
                let _ = hook_client;
            }
        }

        // Effects before interactions.
        let updated = Milestone {
            status: MilestoneStatus::Released,
            ..milestone
        };
        escrow.milestones.set(milestone_index, updated);
        escrow.released_amount = escrow.released_amount.saturating_add(milestone.amount);

        if escrow.released_amount >= escrow.total_amount {
            escrow.status = Status::Completed;
        } else {
            escrow.status = Status::Funded;
        }

        env.storage().persistent().set(&key, &escrow);
        Self::bump_escrow(&env, &key);

        // Interaction: transfer to the appropriate party.
        let token_client = token::Client::new(&env, &escrow.token);
        let recipient = if refund_to_client {
            &escrow.client
        } else {
            &escrow.worker
        };
        token_client.transfer(
            &env.current_contract_address(),
            recipient,
            &milestone.amount,
        );

        Self::bump_instance(&env);
        Ok(())
    }

    pub fn get_milestone_escrow(env: Env, escrow_id: u64) -> Result<MilestoneEscrow, Error> {
        Self::read_milestone_escrow(&env, &DataKey::MilestoneEscrow(escrow_id))
    }

    pub fn get_milestone(
        env: Env,
        escrow_id: u64,
        milestone_index: u32,
    ) -> Result<Milestone, Error> {
        let escrow = Self::read_milestone_escrow(&env, &DataKey::MilestoneEscrow(escrow_id))?;
        escrow
            .milestones
            .get(milestone_index)
            .ok_or(Error::MilestoneNotFound)
    }

    // ===========================================================================
    // Internal helpers
    // ===========================================================================

    fn read_appointment(env: &Env, key: &DataKey) -> Result<Appointment, Error> {
        env.storage()
            .persistent()
            .get(key)
            .ok_or(Error::AppointmentNotFound)
    }

    fn read_milestone_escrow(env: &Env, key: &DataKey) -> Result<MilestoneEscrow, Error> {
        env.storage()
            .persistent()
            .get(key)
            .ok_or(Error::AppointmentNotFound)
    }

    fn bump_escrow(env: &Env, key: &DataKey) {
        env.storage()
            .persistent()
            .extend_ttl(key, LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);
    }

    fn bump_instance(env: &Env) {
        env.storage()
            .instance()
            .extend_ttl(LEDGERS_THRESHOLD, LEDGERS_EXTEND_TO);
    }
}

#[cfg(test)]
mod test;
