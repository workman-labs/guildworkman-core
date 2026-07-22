#![no_std]

//! Shared multi-sig governance guard for gating contract upgrades and
//! storage migrations across the GuildWorkman core contracts.
//!
//! This is a plain library, not a deployed contract on its own. Each
//! contract that wants safe upgradeability depends on this crate as a path
//! dependency and calls its functions against its own storage — the
//! `GovernanceDataKey` type below is distinct from any host contract's own
//! `DataKey`, so there's no collision between a contract's existing state
//! (its single `admin`, its config, etc.) and the governance state this
//! crate manages.
//!
//! Deliberately scoped to upgrade/migration only. It does not touch or
//! replace each contract's existing single-admin auth for its normal
//! operations (dispute resolution, config updates, minting, and so on) —
//! that would be a much larger, higher-risk change than this issue asked
//! for, and a compromised signer set should only ever be able to swap the
//! contract's code, not reach into its day-to-day admin powers.
//!
//! ## Upgrade flow
//!
//! 1. Off-chain, any signer uploads the new Wasm via
//!    `env.deployer().upload_contract_wasm(...)`, getting back a hash.
//! 2. A signer calls the host contract's `propose_upgrade(hash)`, which
//!    calls [`propose_upgrade`] here. That signer's approval is recorded
//!    immediately — with a 1-of-N setup this alone reaches threshold, so
//!    [`propose_upgrade`] reports `ready` the same way [`approve_upgrade`]
//!    does, rather than requiring a distinct second call that a 1-signer
//!    governance could never actually place.
//! 3. Otherwise, other signers call `approve_upgrade(hash)` until
//!    approvals reach the configured threshold. Whichever call — propose
//!    or approve — is the one that crosses the threshold is the one that
//!    should trigger the actual swap, see below for why that has to
//!    happen in the host contract itself, not in this crate.
//! 4. Because `update_current_contract_wasm` only takes effect after the
//!    *current* invocation finishes, the upgrade and the migration can
//!    never happen in the same call. A signer calls the host contract's
//!    `migrate()` afterward, in a separate transaction, to run whatever
//!    the new code's migration logic is and bump the stored version.
//!
//! ## Why this crate can't call `update_current_contract_wasm` itself
//!
//! Soroban's self-upgrade always replaces the Wasm of whichever contract
//! is *currently executing* — there's no "target address" argument. If
//! this shared library called it, it would only ever be replacing this
//! crate's own (non-existent, never-deployed) code, not the host
//! contract's. So [`approve_upgrade`] returns `true` once the threshold is
//! reached, and it's the host contract's job to react to that by calling
//! `env.deployer().update_current_contract_wasm(wasm_hash)` itself.

use soroban_sdk::{contracterror, contracttype, Address, BytesN, Env, Vec};

#[contracttype]
pub enum GovernanceDataKey {
    Signers,
    Threshold,
    PendingUpgrade,
    StorageVersion,
}

#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PendingUpgrade {
    pub wasm_hash: BytesN<32>,
    pub approvals: Vec<Address>,
    pub proposed_at_ledger: u32,
}

/// Bundles the two `init_governance` arguments into one so host contracts'
/// own `initialize` — already taking an admin and whatever else it needs —
/// doesn't creep past clippy's argument-count lint by bolting on two more
/// loose parameters.
#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct GovernanceInit {
    pub signers: Vec<Address>,
    pub threshold: u32,
}

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub enum GovernanceError {
    AlreadyInitialized = 1,
    NotInitialized = 2,
    InvalidThreshold = 3,
    DuplicateSigner = 4,
    NotASigner = 5,
    NoPendingUpgrade = 6,
    AlreadyApproved = 7,
    ProposalExpired = 8,
    HashMismatch = 9,
    AlreadyMigrated = 10,
}

/// How long a proposal stays open for approval before it must be
/// re-proposed. ~7 days at 5s/ledger. A stale, half-approved proposal
/// shouldn't be approvable indefinitely — signer keys or intent can change
/// over that kind of timescale.
pub const PROPOSAL_TTL_LEDGERS: u32 = 120_960;

/// One-time setup, called from the host contract's own `initialize`. The
/// signer set and threshold are immutable after this — there's no
/// signer-rotation flow in this version. Changing signers safely (without
/// letting a majority silently lock out a minority, or vice versa) is its
/// own governance problem; shipping it here would roughly double this
/// crate's attack surface for a capability this issue didn't ask for.
/// Left as a deliberate follow-up.
pub fn init_governance(env: &Env, init: GovernanceInit) -> Result<(), GovernanceError> {
    let GovernanceInit { signers, threshold } = init;

    if env.storage().instance().has(&GovernanceDataKey::Signers) {
        return Err(GovernanceError::AlreadyInitialized);
    }
    if threshold == 0 || threshold > signers.len() {
        return Err(GovernanceError::InvalidThreshold);
    }
    for i in 0..signers.len() {
        for j in (i + 1)..signers.len() {
            if signers.get_unchecked(i) == signers.get_unchecked(j) {
                return Err(GovernanceError::DuplicateSigner);
            }
        }
    }

    env.storage()
        .instance()
        .set(&GovernanceDataKey::Signers, &signers);
    env.storage()
        .instance()
        .set(&GovernanceDataKey::Threshold, &threshold);
    env.storage()
        .instance()
        .set(&GovernanceDataKey::StorageVersion, &1u32);
    Ok(())
}

/// Requires `caller` to have authorized this call and be one of the
/// configured signers. Exposed so host contracts can gate their own
/// governance-adjacent entrypoints (like `migrate`) the same way this
/// crate gates propose/approve/cancel, without duplicating the check.
pub fn require_signer(env: &Env, caller: &Address) -> Result<(), GovernanceError> {
    caller.require_auth();
    let signers = get_signers(env);
    if signers.is_empty() {
        return Err(GovernanceError::NotInitialized);
    }
    if !signers.contains(caller) {
        return Err(GovernanceError::NotASigner);
    }
    Ok(())
}

/// Opens a new upgrade proposal for `wasm_hash`, unconditionally discarding
/// whatever proposal was previously pending, if any — including its
/// approvals. This is not limited to a *different* hash or an *expired*
/// proposal: calling this twice in a row for the identical hash still
/// resets the approval count back to one (just the new call's proposer).
/// Every signer who wants the new proposal to succeed has to call
/// `approve_upgrade` again, even ones who already approved the exact same
/// hash under the proposal this replaced.
///
/// The proposer's approval counts immediately, so with a 1-of-N governance
/// setup this call alone reaches threshold — returns `Ok(true)` in that
/// case, exactly like [`approve_upgrade`] does when its approval is the
/// one that crosses the line. A 1-signer governance can't reach threshold
/// through a later distinct `approve_upgrade` call, since there's no
/// second signer left to place it, so this has to be checked here too.
pub fn propose_upgrade(
    env: &Env,
    proposer: Address,
    wasm_hash: BytesN<32>,
) -> Result<bool, GovernanceError> {
    require_signer(env, &proposer)?;

    let mut approvals = Vec::new(env);
    approvals.push_back(proposer);

    let threshold = get_threshold(env);
    let ready = approvals.len() >= threshold;

    if ready {
        env.storage()
            .instance()
            .remove(&GovernanceDataKey::PendingUpgrade);
    } else {
        let pending = PendingUpgrade {
            wasm_hash,
            approvals,
            proposed_at_ledger: env.ledger().sequence(),
        };
        env.storage()
            .instance()
            .set(&GovernanceDataKey::PendingUpgrade, &pending);
    }
    Ok(ready)
}

/// Approves the currently pending proposal, provided `wasm_hash` matches
/// it and it hasn't expired. Returns `Ok(true)` once this approval brings
/// the count to the configured threshold — the caller (the host contract)
/// is then responsible for actually swapping its own Wasm, since this
/// crate has no way to do that on the host's behalf.
///
/// Once the threshold is reached, the pending proposal is cleared, not
/// left sitting at "already met." That guards against a late-arriving nth
/// approval on a *different*, never-proposed hash being replayed against
/// a stale approvals list — there's nothing to replay against once it's
/// gone.
pub fn approve_upgrade(
    env: &Env,
    approver: Address,
    wasm_hash: BytesN<32>,
) -> Result<bool, GovernanceError> {
    require_signer(env, &approver)?;

    let mut pending: PendingUpgrade = env
        .storage()
        .instance()
        .get(&GovernanceDataKey::PendingUpgrade)
        .ok_or(GovernanceError::NoPendingUpgrade)?;

    if env.ledger().sequence() > pending.proposed_at_ledger + PROPOSAL_TTL_LEDGERS {
        env.storage()
            .instance()
            .remove(&GovernanceDataKey::PendingUpgrade);
        return Err(GovernanceError::ProposalExpired);
    }
    if pending.wasm_hash != wasm_hash {
        return Err(GovernanceError::HashMismatch);
    }
    if pending.approvals.contains(&approver) {
        return Err(GovernanceError::AlreadyApproved);
    }

    pending.approvals.push_back(approver);

    let threshold = get_threshold(env);
    let ready = pending.approvals.len() >= threshold;

    if ready {
        env.storage()
            .instance()
            .remove(&GovernanceDataKey::PendingUpgrade);
    } else {
        env.storage()
            .instance()
            .set(&GovernanceDataKey::PendingUpgrade, &pending);
    }
    Ok(ready)
}

/// Cancels the pending proposal outright. Any signer can do this — being
/// able to veto an in-flight upgrade is a strictly safer default than
/// requiring the same threshold to cancel as to approve, since a minority
/// of signers should always be able to stop a swap they didn't sign off
/// on from lingering, even if they can't force one through.
pub fn cancel_upgrade(env: &Env, caller: Address) -> Result<(), GovernanceError> {
    require_signer(env, &caller)?;
    if !env
        .storage()
        .instance()
        .has(&GovernanceDataKey::PendingUpgrade)
    {
        return Err(GovernanceError::NoPendingUpgrade);
    }
    env.storage()
        .instance()
        .remove(&GovernanceDataKey::PendingUpgrade);
    Ok(())
}

pub fn get_signers(env: &Env) -> Vec<Address> {
    env.storage()
        .instance()
        .get(&GovernanceDataKey::Signers)
        .unwrap_or_else(|| Vec::new(env))
}

pub fn get_threshold(env: &Env) -> u32 {
    env.storage()
        .instance()
        .get(&GovernanceDataKey::Threshold)
        .unwrap_or(0)
}

pub fn get_pending_upgrade(env: &Env) -> Option<PendingUpgrade> {
    env.storage()
        .instance()
        .get(&GovernanceDataKey::PendingUpgrade)
}

pub fn current_storage_version(env: &Env) -> u32 {
    env.storage()
        .instance()
        .get(&GovernanceDataKey::StorageVersion)
        .unwrap_or(1)
}

/// Records that migration to `to_version` has been applied. The host
/// contract must call this only *after* it has actually transformed
/// storage to match — this function has no way to verify that happened,
/// it just gates against running the same migration twice or going
/// backwards.
pub fn mark_migrated(env: &Env, to_version: u32) -> Result<(), GovernanceError> {
    if to_version <= current_storage_version(env) {
        return Err(GovernanceError::AlreadyMigrated);
    }
    env.storage()
        .instance()
        .set(&GovernanceDataKey::StorageVersion, &to_version);
    Ok(())
}

#[cfg(test)]
mod test;
