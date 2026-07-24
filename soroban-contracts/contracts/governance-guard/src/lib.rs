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
//!
//! ## Signer rotation
//!
//! The signer set and threshold are no longer frozen at
//! [`init_governance`]. They can be rotated through a three-step flow:
//! [`propose_signer_rotation`] → [`approve_signer_rotation`] →
//! [`execute_signer_rotation`]. The security model is deliberately stricter
//! than the upgrade flow's, because rotation is the one action that can
//! change *who* governs:
//!
//! * **Authorization — same threshold as an upgrade.** A rotation is
//!   approved by the *current* signer set at the *current* threshold. It is
//!   gated no more weakly than an upgrade, and no more strongly either:
//!   requiring a strictly-higher threshold is impossible once the threshold
//!   is already N-of-N, and the current set can in any case already replace
//!   *all* of a contract's code (governance included) through the upgrade
//!   flow. So the same threshold is the honest bound — rotation grants the
//!   signer set no power it didn't already have via upgrade.
//!
//! * **Minority protection — a mandatory timelock.** Reaching threshold does
//!   not execute the rotation; it *schedules* it,
//!   [`ROTATION_TIMELOCK_LEDGERS`] in the future. Only after that delay can
//!   [`execute_signer_rotation`] apply it. This guarantees a minority about
//!   to be removed a visible, on-chain window to react — no *silent,
//!   instant* lockout is possible.
//!
//! * **Majority protection — no unilateral veto.** Unlike an upgrade (which
//!   any single signer may [`cancel_upgrade`], because blocking a *change*
//!   is the safe default), a rotation cannot be cancelled by one signer.
//!   For rotation the status quo itself may be the threat — a lost or
//!   compromised key — so letting any single signer block a rotation would
//!   let a compromised signer entrench itself against its own removal.
//!   Aborting or amending a pending rotation therefore requires a fresh
//!   threshold agreement, expressed by proposing a superseding rotation
//!   (e.g. back to the current set); see [`propose_signer_rotation`].
//!
//! * **Cross-effect — a rotation clears any pending upgrade.** An in-flight
//!   upgrade's approvals were gathered under the *old* signer set; carrying
//!   them past a rotation could let a removed signer's approval count, or
//!   let a stale half-approved upgrade cross threshold under a set that
//!   never really approved it. So [`execute_signer_rotation`] discards any
//!   pending upgrade.

use soroban_sdk::{contracterror, contractevent, contracttype, Address, BytesN, Env, Vec};

#[contracttype]
pub enum GovernanceDataKey {
    Signers,
    Threshold,
    PendingUpgrade,
    StorageVersion,
    /// The single in-flight signer-rotation, if any. Distinct key from
    /// `PendingUpgrade` so a rotation and an upgrade can be pending at the
    /// same time without clobbering each other. Appended last to keep the
    /// existing keys' encodings stable for already-deployed contracts.
    PendingRotation,
}

#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PendingUpgrade {
    pub wasm_hash: BytesN<32>,
    pub approvals: Vec<Address>,
    pub proposed_at_ledger: u32,
}

/// An in-flight proposal to replace the whole signer set and threshold.
///
/// A rotation moves through two phases, tracked by `eta_ledger`:
///
/// * **Gathering** (`eta_ledger == 0`): approvals are still being collected
///   from the *current* signer set, exactly like an upgrade proposal. It
///   must reach `threshold` within [`PROPOSAL_TTL_LEDGERS`] of
///   `proposed_at_ledger` or it expires.
/// * **Scheduled / timelocked** (`eta_ledger != 0`): threshold was reached
///   and `eta_ledger` is the earliest ledger at which the swap may be
///   executed. This mandatory delay is the minority-protection guarantee —
///   see the module-level "Signer rotation" notes.
#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PendingRotation {
    /// The signer set that will replace the current one on execution.
    pub new_signers: Vec<Address>,
    /// The threshold that will apply to `new_signers` after execution.
    pub new_threshold: u32,
    /// Current signers who have approved *this* rotation. Gated by the
    /// *current* threshold, not `new_threshold` — the old set authorizes
    /// its own replacement.
    pub approvals: Vec<Address>,
    /// Ledger at which the rotation was (most recently) proposed. Bounds the
    /// approval-gathering phase via [`PROPOSAL_TTL_LEDGERS`].
    pub proposed_at_ledger: u32,
    /// `0` while approvals are still being gathered; once threshold is
    /// reached this becomes `now + `[`ROTATION_TIMELOCK_LEDGERS`], the
    /// earliest ledger [`execute_signer_rotation`] will act on.
    pub eta_ledger: u32,
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
    // --- Signer rotation (see the "Signer rotation" module notes) ---
    /// No rotation is currently pending.
    NoPendingRotation = 11,
    /// The `new_signers`/`new_threshold` passed to `approve_signer_rotation`
    /// don't match the pending rotation — the analogue of `HashMismatch`,
    /// so a signer can't approve a set different from the one they were
    /// shown.
    RotationMismatch = 12,
    /// `execute_signer_rotation` was called while the rotation is still
    /// gathering approvals (threshold not yet reached, so no timelock has
    /// started).
    RotationNotReady = 13,
    /// `execute_signer_rotation` was called after threshold but before the
    /// timelock (`eta_ledger`) elapsed.
    RotationTimelockActive = 14,
    /// The rotation timed out — either it never reached threshold within
    /// the approval window, or it was scheduled but not executed before its
    /// execution window closed.
    RotationExpired = 15,
    /// A rotation is already live (still gathering approvals, or scheduled
    /// and inside its execution window). Only one rotation may be in flight
    /// at a time — this is what stops a lone signer from resetting a
    /// rotation's approvals or displacing a scheduled one.
    RotationInProgress = 16,
}

/// How long a proposal stays open for approval before it must be
/// re-proposed. ~7 days at 5s/ledger. A stale, half-approved proposal
/// shouldn't be approvable indefinitely — signer keys or intent can change
/// over that kind of timescale.
pub const PROPOSAL_TTL_LEDGERS: u32 = 120_960;

/// Mandatory delay between a signer rotation reaching threshold and it
/// becoming executable. ~3 days at 5s/ledger.
///
/// This is the core minority-protection mechanism (see the "Signer
/// rotation" module notes): once the current threshold agrees to a new
/// signer set, the change does not take effect immediately. Every current
/// signer — including any minority about to be removed — gets a guaranteed,
/// publicly visible on-chain window in which the pending rotation can be
/// observed and reacted to before it lands. It cannot prevent a determined
/// threshold from eventually rotating (that power is inherent to any M-of-N
/// set, which can already swap all the code), but it makes a *silent,
/// instant* lockout impossible.
///
/// Chosen long enough to be noticed and acted on, short enough to remain
/// usable when responding to a genuinely lost or compromised key.
pub const ROTATION_TIMELOCK_LEDGERS: u32 = 51_840;

/// Validates a `(signers, threshold)` pair for both initial setup and
/// rotation. An empty signer set is rejected here too: with zero signers the
/// only valid threshold would be zero, which the `threshold == 0` check
/// already forbids, so an empty set can never pass. Enforcing these same
/// rules on rotation is what stops a rotation from bricking governance (a
/// set no one can meet) or unlocking it (threshold below what the set can
/// satisfy).
fn validate_signer_set(signers: &Vec<Address>, threshold: u32) -> Result<(), GovernanceError> {
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
    Ok(())
}

/// One-time setup, called from the host contract's own `initialize`. The
/// signer set and threshold are configured here and can afterwards only be
/// changed through the timelocked rotation flow
/// ([`propose_signer_rotation`] and friends) — never rewritten directly.
pub fn init_governance(env: &Env, init: GovernanceInit) -> Result<(), GovernanceError> {
    let GovernanceInit { signers, threshold } = init;

    if env.storage().instance().has(&GovernanceDataKey::Signers) {
        return Err(GovernanceError::AlreadyInitialized);
    }
    validate_signer_set(&signers, threshold)?;

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

// ---------------------------------------------------------------------------
// Signer rotation
// ---------------------------------------------------------------------------

/// Emitted when a new rotation is opened. Topics: `["gov_rot", "proposed",
/// proposer]`; data carries the threshold the new set will run at.
#[contractevent(topics = ["gov_rot", "proposed"])]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RotationProposed {
    #[topic]
    pub proposer: Address,
    pub new_threshold: u32,
}

/// Emitted on each approval of the pending rotation. `approvals` is the
/// running count after this approval. Topics: `["gov_rot", "approved",
/// approver]`.
#[contractevent(topics = ["gov_rot", "approved"])]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RotationApproved {
    #[topic]
    pub approver: Address,
    pub approvals: u32,
}

/// Emitted the moment a rotation reaches threshold and its timelock starts.
/// `eta_ledger` is the earliest ledger it can be executed at. Topics:
/// `["gov_rot", "scheduled"]`.
#[contractevent(topics = ["gov_rot", "scheduled"])]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RotationScheduled {
    pub eta_ledger: u32,
}

/// Emitted when a rotation is applied and the signer set is swapped. Topics:
/// `["gov_rot", "executed"]`; data carries the new set's size and threshold.
#[contractevent(topics = ["gov_rot", "executed"])]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RotationExecuted {
    pub new_signer_count: u32,
    pub new_threshold: u32,
}

/// A rotation is "live" — occupying the single rotation slot — while it is
/// still gathering approvals within its approval window, or scheduled and
/// still inside its post-timelock execution window. Once either window has
/// closed it is expired: it can no longer be acted on and may be replaced by
/// a fresh proposal.
fn rotation_is_live(pending: &PendingRotation, now: u32) -> bool {
    if pending.eta_ledger == 0 {
        // Gathering: live until the approval window closes.
        now <= pending.proposed_at_ledger + PROPOSAL_TTL_LEDGERS
    } else {
        // Scheduled: live until the execution window (after the timelock)
        // closes. It is still "live" during the timelock even though it
        // isn't executable yet — the slot is taken.
        now <= pending.eta_ledger + PROPOSAL_TTL_LEDGERS
    }
}

/// Opens a proposal to replace the *entire* signer set and threshold with
/// `new_signers` / `new_threshold`, authorized by the current signer set at
/// the current threshold. The proposer's approval counts immediately.
///
/// Only one rotation may be in flight at a time. Unlike [`propose_upgrade`],
/// this does **not** silently discard an existing live proposal: if a
/// rotation is still gathering approvals or is scheduled, this returns
/// [`GovernanceError::RotationInProgress`]. That single-slot rule is
/// deliberate and load-bearing for the majority-protection property — if a
/// fresh proposal reset the approval count, a lone signer could call this
/// repeatedly to stop any rotation from ever reaching threshold, and if it
/// could displace a *scheduled* rotation, a signer about to be removed could
/// veto their own removal. An expired rotation (either window closed) is not
/// live and is cleared and replaced here.
///
/// Returns `Ok(true)` if this call alone reached threshold (a 1-of-N guard),
/// in which case the rotation is now *scheduled* behind the timelock — it is
/// still not applied until [`execute_signer_rotation`] is called after
/// [`ROTATION_TIMELOCK_LEDGERS`] have passed.
pub fn propose_signer_rotation(
    env: &Env,
    proposer: Address,
    new_signers: Vec<Address>,
    new_threshold: u32,
) -> Result<bool, GovernanceError> {
    require_signer(env, &proposer)?;
    validate_signer_set(&new_signers, new_threshold)?;

    let now = env.ledger().sequence();
    if let Some(existing) = get_pending_rotation(env) {
        if rotation_is_live(&existing, now) {
            return Err(GovernanceError::RotationInProgress);
        }
        // Otherwise it's expired — fall through and overwrite it.
    }

    let mut approvals = Vec::new(env);
    approvals.push_back(proposer.clone());

    let threshold = get_threshold(env);
    let scheduled = approvals.len() >= threshold;
    let eta_ledger = if scheduled {
        now + ROTATION_TIMELOCK_LEDGERS
    } else {
        0
    };

    let pending = PendingRotation {
        new_signers,
        new_threshold,
        approvals,
        proposed_at_ledger: now,
        eta_ledger,
    };
    env.storage()
        .instance()
        .set(&GovernanceDataKey::PendingRotation, &pending);

    RotationProposed {
        proposer,
        new_threshold,
    }
    .publish(env);
    if scheduled {
        RotationScheduled { eta_ledger }.publish(env);
    }
    Ok(scheduled)
}

/// Approves the pending rotation, which must still be gathering approvals and
/// must match the `new_signers` / `new_threshold` the caller passes — the
/// same-args check is the rotation analogue of [`approve_upgrade`]'s hash
/// match, so a signer can't be tricked into approving a different set than
/// the one they reviewed.
///
/// Returns `Ok(true)` when this approval brings the count to the current
/// threshold, which *schedules* the rotation (starts the timelock) rather
/// than applying it. Approving a rotation that is already scheduled returns
/// [`GovernanceError::RotationTimelockActive`] — the approval stage is over.
pub fn approve_signer_rotation(
    env: &Env,
    approver: Address,
    new_signers: Vec<Address>,
    new_threshold: u32,
) -> Result<bool, GovernanceError> {
    require_signer(env, &approver)?;

    let mut pending: PendingRotation = env
        .storage()
        .instance()
        .get(&GovernanceDataKey::PendingRotation)
        .ok_or(GovernanceError::NoPendingRotation)?;

    let now = env.ledger().sequence();

    if pending.eta_ledger != 0 {
        // Already past threshold and into its timelock — no more approvals.
        return Err(GovernanceError::RotationTimelockActive);
    }
    if now > pending.proposed_at_ledger + PROPOSAL_TTL_LEDGERS {
        env.storage()
            .instance()
            .remove(&GovernanceDataKey::PendingRotation);
        return Err(GovernanceError::RotationExpired);
    }
    if pending.new_signers != new_signers || pending.new_threshold != new_threshold {
        return Err(GovernanceError::RotationMismatch);
    }
    if pending.approvals.contains(&approver) {
        return Err(GovernanceError::AlreadyApproved);
    }

    pending.approvals.push_back(approver.clone());
    let approvals_len = pending.approvals.len();

    let threshold = get_threshold(env);
    let scheduled = approvals_len >= threshold;
    if scheduled {
        pending.eta_ledger = now + ROTATION_TIMELOCK_LEDGERS;
    }
    env.storage()
        .instance()
        .set(&GovernanceDataKey::PendingRotation, &pending);

    RotationApproved {
        approver,
        approvals: approvals_len,
    }
    .publish(env);
    if scheduled {
        RotationScheduled {
            eta_ledger: pending.eta_ledger,
        }
        .publish(env);
    }
    Ok(scheduled)
}

/// Applies a rotation that has reached threshold and cleared its timelock,
/// swapping in the new signer set and threshold. Any current signer may
/// trigger it — executing an already-ratified decision needs no fresh
/// consensus, exactly like whichever approval crosses an upgrade's
/// threshold triggers the swap.
///
/// Fails if there's no pending rotation ([`GovernanceError::NoPendingRotation`]),
/// it's still gathering approvals ([`GovernanceError::RotationNotReady`]),
/// the timelock hasn't elapsed ([`GovernanceError::RotationTimelockActive`]),
/// or the execution window has closed
/// ([`GovernanceError::RotationExpired`], which also clears the stale entry).
///
/// On success it also clears any pending upgrade: that upgrade's approvals
/// came from the *old* signer set and must not carry into the new one.
pub fn execute_signer_rotation(env: &Env, caller: Address) -> Result<(), GovernanceError> {
    require_signer(env, &caller)?;

    let pending: PendingRotation = env
        .storage()
        .instance()
        .get(&GovernanceDataKey::PendingRotation)
        .ok_or(GovernanceError::NoPendingRotation)?;

    let now = env.ledger().sequence();

    if pending.eta_ledger == 0 {
        // Never reached threshold. Surface expiry distinctly from "not yet".
        if now > pending.proposed_at_ledger + PROPOSAL_TTL_LEDGERS {
            env.storage()
                .instance()
                .remove(&GovernanceDataKey::PendingRotation);
            return Err(GovernanceError::RotationExpired);
        }
        return Err(GovernanceError::RotationNotReady);
    }
    if now < pending.eta_ledger {
        return Err(GovernanceError::RotationTimelockActive);
    }
    if now > pending.eta_ledger + PROPOSAL_TTL_LEDGERS {
        env.storage()
            .instance()
            .remove(&GovernanceDataKey::PendingRotation);
        return Err(GovernanceError::RotationExpired);
    }

    // Apply the new set. It was validated at propose time, but the signer
    // set is small and re-validating here is cheap insurance against any
    // future path that could stage an invalid set.
    validate_signer_set(&pending.new_signers, pending.new_threshold)?;
    env.storage()
        .instance()
        .set(&GovernanceDataKey::Signers, &pending.new_signers);
    env.storage()
        .instance()
        .set(&GovernanceDataKey::Threshold, &pending.new_threshold);

    env.storage()
        .instance()
        .remove(&GovernanceDataKey::PendingRotation);
    // Any in-flight upgrade was approved by the old set — drop it.
    env.storage()
        .instance()
        .remove(&GovernanceDataKey::PendingUpgrade);

    RotationExecuted {
        new_signer_count: pending.new_signers.len(),
        new_threshold: pending.new_threshold,
    }
    .publish(env);
    Ok(())
}

pub fn get_pending_rotation(env: &Env) -> Option<PendingRotation> {
    env.storage()
        .instance()
        .get(&GovernanceDataKey::PendingRotation)
}

#[cfg(test)]
mod test;
