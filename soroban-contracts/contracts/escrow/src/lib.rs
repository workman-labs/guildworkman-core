#![no_std]

//! Escrow contract for GuildWorkman appointments.
//!
//! A client funds an appointment by depositing payment into the contract.
//! Funds sit in escrow until the client confirms the job is done, at which
//! point they are released to the skilled worker. Either party can raise a
//! dispute, which freezes the funds until the admin (arbiter) resolves it.

use soroban_sdk::{
    contract, contracterror, contractimpl, contracttype, token, Address, BytesN, Env, Vec,
};

use guildworkman_governance_guard as governance;
pub use guildworkman_governance_guard::PendingUpgrade;

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
pub enum DataKey {
    Admin,
    Appointment(u64),
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

    fn read_appointment(env: &Env, key: &DataKey) -> Result<Appointment, Error> {
        env.storage()
            .persistent()
            .get(key)
            .ok_or(Error::AppointmentNotFound)
    }
}

#[cfg(test)]
mod test;
