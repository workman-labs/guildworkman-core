use soroban_sdk::{
    contract, testutils::Address as _, testutils::Events as _, testutils::Ledger, Address, BytesN,
    Env, Vec,
};

use crate::{
    approve_signer_rotation, approve_upgrade, cancel_upgrade, current_storage_version,
    execute_signer_rotation, get_pending_rotation, get_pending_upgrade, get_signers, get_threshold,
    init_governance, mark_migrated, propose_signer_rotation, propose_upgrade, require_signer,
    GovernanceError, GovernanceInit, PendingRotation, PendingUpgrade, PROPOSAL_TTL_LEDGERS,
    ROTATION_TIMELOCK_LEDGERS,
};

// This crate has no #[contract] of its own — it's a library other contracts
// call from inside their own #[contractimpl] methods, which is what gives
// env.storage() a "current contract" to operate on. To test it the same way
// it's actually used, these tests register a bare stand-in contract and run
// every call through env.as_contract(&id, || ...), exactly like a real host
// contract's method body would.
#[contract]
struct TestHost;

fn new_host(env: &Env) -> Address {
    env.register(TestHost, ())
}

fn init(
    env: &Env,
    id: &Address,
    signers: Vec<Address>,
    threshold: u32,
) -> Result<(), GovernanceError> {
    env.as_contract(id, || {
        init_governance(env, GovernanceInit { signers, threshold })
    })
}

fn propose(
    env: &Env,
    id: &Address,
    proposer: Address,
    wasm_hash: BytesN<32>,
) -> Result<bool, GovernanceError> {
    env.as_contract(id, || propose_upgrade(env, proposer, wasm_hash))
}

fn approve(
    env: &Env,
    id: &Address,
    approver: Address,
    wasm_hash: BytesN<32>,
) -> Result<bool, GovernanceError> {
    env.as_contract(id, || approve_upgrade(env, approver, wasm_hash))
}

fn cancel(env: &Env, id: &Address, caller: Address) -> Result<(), GovernanceError> {
    env.as_contract(id, || cancel_upgrade(env, caller))
}

fn pending(env: &Env, id: &Address) -> Option<PendingUpgrade> {
    env.as_contract(id, || get_pending_upgrade(env))
}

fn version(env: &Env, id: &Address) -> u32 {
    env.as_contract(id, || current_storage_version(env))
}

fn migrate(env: &Env, id: &Address, to_version: u32) -> Result<(), GovernanceError> {
    env.as_contract(id, || mark_migrated(env, to_version))
}

fn signers_of(env: &Env, id: &Address) -> Vec<Address> {
    env.as_contract(id, || get_signers(env))
}

fn threshold_of(env: &Env, id: &Address) -> u32 {
    env.as_contract(id, || get_threshold(env))
}

fn check_signer(env: &Env, id: &Address, caller: Address) -> Result<(), GovernanceError> {
    env.as_contract(id, || require_signer(env, &caller))
}

fn hash(env: &Env, byte: u8) -> BytesN<32> {
    BytesN::from_array(env, &[byte; 32])
}

fn make_signers(env: &Env, n: u32) -> Vec<Address> {
    let mut signers = Vec::new(env);
    for _ in 0..n {
        signers.push_back(Address::generate(env));
    }
    signers
}

fn propose_rotation(
    env: &Env,
    id: &Address,
    proposer: Address,
    new_signers: Vec<Address>,
    new_threshold: u32,
) -> Result<bool, GovernanceError> {
    env.as_contract(id, || {
        propose_signer_rotation(env, proposer, new_signers, new_threshold)
    })
}

fn approve_rotation(
    env: &Env,
    id: &Address,
    approver: Address,
    new_signers: Vec<Address>,
    new_threshold: u32,
) -> Result<bool, GovernanceError> {
    env.as_contract(id, || {
        approve_signer_rotation(env, approver, new_signers, new_threshold)
    })
}

fn execute_rotation(env: &Env, id: &Address, caller: Address) -> Result<(), GovernanceError> {
    env.as_contract(id, || execute_signer_rotation(env, caller))
}

fn pending_rotation(env: &Env, id: &Address) -> Option<PendingRotation> {
    env.as_contract(id, || get_pending_rotation(env))
}

/// Advance the ledger sequence by `by` ledgers.
fn advance(env: &Env, by: u32) {
    env.ledger().with_mut(|l| {
        l.sequence_number += by;
    });
}

#[test]
fn init_stores_signers_and_threshold() {
    let env = Env::default();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();

    assert_eq!(signers_of(&env, &id), signers);
    assert_eq!(threshold_of(&env, &id), 2);
    assert_eq!(version(&env, &id), 1);
}

#[test]
fn double_init_fails() {
    let env = Env::default();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 1).unwrap();
    let result = init(&env, &id, signers, 1);
    assert_eq!(result, Err(GovernanceError::AlreadyInitialized));
}

#[test]
fn zero_threshold_rejected() {
    let env = Env::default();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    let result = init(&env, &id, signers, 0);
    assert_eq!(result, Err(GovernanceError::InvalidThreshold));
}

#[test]
fn threshold_over_signer_count_rejected() {
    let env = Env::default();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    let result = init(&env, &id, signers, 3);
    assert_eq!(result, Err(GovernanceError::InvalidThreshold));
}

#[test]
fn duplicate_signer_rejected() {
    let env = Env::default();
    let id = new_host(&env);
    let a = Address::generate(&env);
    let mut signers = Vec::new(&env);
    signers.push_back(a.clone());
    signers.push_back(a);
    let result = init(&env, &id, signers, 1);
    assert_eq!(result, Err(GovernanceError::DuplicateSigner));
}

#[test]
fn propose_by_non_signer_fails() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers, 2).unwrap();

    let outsider = Address::generate(&env);
    let result = propose(&env, &id, outsider, hash(&env, 1));
    assert_eq!(result, Err(GovernanceError::NotASigner));
}

#[test]
fn single_signer_threshold_one_is_ready_immediately_on_propose() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 1);
    init(&env, &id, signers.clone(), 1).unwrap();

    // With threshold 1, the proposer's own approval already satisfies it —
    // there's no second signer to place a distinct approve() call, so
    // propose() itself has to report ready.
    let ready = propose(&env, &id, signers.get_unchecked(0), hash(&env, 7)).unwrap();
    assert!(ready);
    assert!(pending(&env, &id).is_none());
}

#[test]
fn two_of_three_threshold_ready_only_after_second_distinct_approval() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let target = hash(&env, 42);

    let ready = propose(&env, &id, signers.get_unchecked(0), target.clone()).unwrap();
    assert!(!ready);

    let p = pending(&env, &id).unwrap();
    assert_eq!(p.approvals.len(), 1);

    let ready = approve(&env, &id, signers.get_unchecked(1), target).unwrap();
    assert!(ready);

    // Proposal is cleared once threshold is met, not left dangling.
    assert!(pending(&env, &id).is_none());
}

#[test]
fn non_signer_cannot_approve() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 2).unwrap();
    let target = hash(&env, 3);
    propose(&env, &id, signers.get_unchecked(0), target.clone()).unwrap();

    let outsider = Address::generate(&env);
    let result = approve(&env, &id, outsider, target);
    assert_eq!(result, Err(GovernanceError::NotASigner));
}

#[test]
fn same_signer_cannot_approve_twice() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 3).unwrap();
    let target = hash(&env, 3);
    propose(&env, &id, signers.get_unchecked(0), target.clone()).unwrap();

    let result = approve(&env, &id, signers.get_unchecked(0), target);
    assert_eq!(result, Err(GovernanceError::AlreadyApproved));
}

#[test]
fn approving_the_wrong_hash_is_rejected() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 2).unwrap();
    propose(&env, &id, signers.get_unchecked(0), hash(&env, 1)).unwrap();

    let result = approve(&env, &id, signers.get_unchecked(1), hash(&env, 2));
    assert_eq!(result, Err(GovernanceError::HashMismatch));
}

#[test]
fn approving_with_no_pending_proposal_fails() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 2).unwrap();

    let result = approve(&env, &id, signers.get_unchecked(0), hash(&env, 1));
    assert_eq!(result, Err(GovernanceError::NoPendingUpgrade));
}

#[test]
fn a_new_proposal_replaces_the_old_ones_approvals() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 3).unwrap();

    propose(&env, &id, signers.get_unchecked(0), hash(&env, 1)).unwrap();
    approve(&env, &id, signers.get_unchecked(1), hash(&env, 1)).unwrap();

    // Someone proposes a *different* hash before threshold was reached —
    // approvals collected for the old hash must not silently carry over
    // and count toward the new one.
    propose(&env, &id, signers.get_unchecked(2), hash(&env, 2)).unwrap();
    let p = pending(&env, &id).unwrap();
    assert_eq!(p.wasm_hash, hash(&env, 2));
    assert_eq!(p.approvals.len(), 1);

    // The signer who already approved hash 1 approving hash 2 counts as a
    // fresh, distinct approval, not a replay — they haven't approved *this*
    // proposal yet.
    let ready = approve(&env, &id, signers.get_unchecked(1), hash(&env, 2)).unwrap();
    assert!(!ready); // still needs a third for threshold 3
}

#[test]
fn expired_proposal_cannot_be_approved() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 2).unwrap();
    let target = hash(&env, 5);
    propose(&env, &id, signers.get_unchecked(0), target.clone()).unwrap();

    env.ledger().with_mut(|l| {
        l.sequence_number += PROPOSAL_TTL_LEDGERS + 1;
    });

    let result = approve(&env, &id, signers.get_unchecked(1), target.clone());
    assert_eq!(result, Err(GovernanceError::ProposalExpired));

    // Expiry clears the stale proposal rather than leaving it approvable
    // forever once someone notices it timed out.
    assert!(pending(&env, &id).is_none());

    // Re-proposing the same hash after expiry starts a clean slate.
    propose(&env, &id, signers.get_unchecked(0), target.clone()).unwrap();
    let ready = approve(&env, &id, signers.get_unchecked(1), target).unwrap();
    assert!(ready);
}

#[test]
fn any_signer_can_cancel_a_pending_proposal() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 3).unwrap();
    propose(&env, &id, signers.get_unchecked(0), hash(&env, 9)).unwrap();
    approve(&env, &id, signers.get_unchecked(1), hash(&env, 9)).unwrap();

    // A signer who never approved can still cancel outright — a minority
    // can block an in-flight upgrade even if it can't force one through.
    cancel(&env, &id, signers.get_unchecked(2)).unwrap();
    assert!(pending(&env, &id).is_none());

    let result = approve(&env, &id, signers.get_unchecked(2), hash(&env, 9));
    assert_eq!(result, Err(GovernanceError::NoPendingUpgrade));
}

#[test]
fn cancel_by_non_signer_fails() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 2).unwrap();
    propose(&env, &id, signers.get_unchecked(0), hash(&env, 1)).unwrap();

    let outsider = Address::generate(&env);
    let result = cancel(&env, &id, outsider);
    assert_eq!(result, Err(GovernanceError::NotASigner));
}

#[test]
fn cancel_with_nothing_pending_fails() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 2).unwrap();

    let result = cancel(&env, &id, signers.get_unchecked(0));
    assert_eq!(result, Err(GovernanceError::NoPendingUpgrade));
}

#[test]
fn require_signer_accepts_a_configured_signer_and_rejects_an_outsider() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 1).unwrap();

    assert!(check_signer(&env, &id, signers.get_unchecked(0)).is_ok());

    let outsider = Address::generate(&env);
    assert_eq!(
        check_signer(&env, &id, outsider),
        Err(GovernanceError::NotASigner)
    );
}

#[test]
fn mark_migrated_advances_version() {
    let env = Env::default();
    let id = new_host(&env);
    let signers = make_signers(&env, 1);
    init(&env, &id, signers, 1).unwrap();
    assert_eq!(version(&env, &id), 1);

    migrate(&env, &id, 2).unwrap();
    assert_eq!(version(&env, &id), 2);
}

#[test]
fn mark_migrated_rejects_same_or_earlier_version() {
    let env = Env::default();
    let id = new_host(&env);
    let signers = make_signers(&env, 1);
    init(&env, &id, signers, 1).unwrap();
    migrate(&env, &id, 2).unwrap();

    assert_eq!(migrate(&env, &id, 2), Err(GovernanceError::AlreadyMigrated));
    assert_eq!(migrate(&env, &id, 1), Err(GovernanceError::AlreadyMigrated));
    // Version must never move backwards even if someone passes a lower
    // number than what's already recorded.
    assert_eq!(version(&env, &id), 2);
}

// ---------------------------------------------------------------------------
// Signer rotation
// ---------------------------------------------------------------------------

#[test]
fn rotation_by_non_signer_fails() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers, 2).unwrap();

    let outsider = Address::generate(&env);
    let new_set = make_signers(&env, 2);
    assert_eq!(
        propose_rotation(&env, &id, outsider, new_set, 1),
        Err(GovernanceError::NotASigner)
    );
}

#[test]
fn rotation_rejects_invalid_new_set() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let proposer = signers.get_unchecked(0);
    let new_set = make_signers(&env, 2);

    // threshold 0
    assert_eq!(
        propose_rotation(&env, &id, proposer.clone(), new_set.clone(), 0),
        Err(GovernanceError::InvalidThreshold)
    );
    // threshold > new set size
    assert_eq!(
        propose_rotation(&env, &id, proposer.clone(), new_set.clone(), 3),
        Err(GovernanceError::InvalidThreshold)
    );
    // empty set can never satisfy threshold >= 1
    assert_eq!(
        propose_rotation(&env, &id, proposer.clone(), Vec::new(&env), 1),
        Err(GovernanceError::InvalidThreshold)
    );
    // duplicate in new set
    let mut dup = Vec::new(&env);
    let a = Address::generate(&env);
    dup.push_back(a.clone());
    dup.push_back(a);
    assert_eq!(
        propose_rotation(&env, &id, proposer, dup, 1),
        Err(GovernanceError::DuplicateSigner)
    );

    // None of the rejected attempts left a rotation behind.
    assert!(pending_rotation(&env, &id).is_none());
}

#[test]
fn single_signer_rotation_schedules_then_executes_after_timelock() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 1);
    init(&env, &id, signers.clone(), 1).unwrap();

    // 1-of-1: the proposer's own approval reaches threshold, so the rotation
    // is scheduled immediately — but still timelocked, never applied inline.
    let new_set = make_signers(&env, 2);
    let scheduled =
        propose_rotation(&env, &id, signers.get_unchecked(0), new_set.clone(), 2).unwrap();
    assert!(scheduled);

    let p = pending_rotation(&env, &id).unwrap();
    assert_ne!(p.eta_ledger, 0);
    // Signer set is unchanged until execution.
    assert_eq!(signers_of(&env, &id), signers);

    // Executing before the timelock elapses is refused.
    assert_eq!(
        execute_rotation(&env, &id, signers.get_unchecked(0)),
        Err(GovernanceError::RotationTimelockActive)
    );

    advance(&env, ROTATION_TIMELOCK_LEDGERS);
    execute_rotation(&env, &id, signers.get_unchecked(0)).unwrap();

    assert_eq!(signers_of(&env, &id), new_set);
    assert_eq!(threshold_of(&env, &id), 2);
    assert!(pending_rotation(&env, &id).is_none());
}

#[test]
fn multi_sig_rotation_full_flow() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let new_set = make_signers(&env, 2);

    // First approval (the proposer) does not reach the 2-of-3 threshold.
    let scheduled =
        propose_rotation(&env, &id, signers.get_unchecked(0), new_set.clone(), 1).unwrap();
    assert!(!scheduled);
    let p = pending_rotation(&env, &id).unwrap();
    assert_eq!(p.approvals.len(), 1);
    assert_eq!(p.eta_ledger, 0);

    // Not executable while still gathering approvals.
    assert_eq!(
        execute_rotation(&env, &id, signers.get_unchecked(0)),
        Err(GovernanceError::RotationNotReady)
    );

    // Second distinct approval crosses threshold and schedules it.
    let scheduled =
        approve_rotation(&env, &id, signers.get_unchecked(1), new_set.clone(), 1).unwrap();
    assert!(scheduled);
    let p = pending_rotation(&env, &id).unwrap();
    assert_ne!(p.eta_ledger, 0);

    advance(&env, ROTATION_TIMELOCK_LEDGERS);
    // Any current signer can execute the ratified rotation — even one who
    // never approved it.
    execute_rotation(&env, &id, signers.get_unchecked(2)).unwrap();

    assert_eq!(signers_of(&env, &id), new_set);
    assert_eq!(threshold_of(&env, &id), 1);
}

#[test]
fn approve_rotation_mismatched_set_is_rejected() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let new_set = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), new_set, 1).unwrap();

    // Approving a *different* set than the one pending must be rejected, so
    // an approver can't be redirected onto a set they didn't review.
    let other_set = make_signers(&env, 2);
    assert_eq!(
        approve_rotation(&env, &id, signers.get_unchecked(1), other_set, 1),
        Err(GovernanceError::RotationMismatch)
    );
}

#[test]
fn approve_rotation_wrong_threshold_is_rejected() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let new_set = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), new_set.clone(), 1).unwrap();

    // Same set, different threshold — still a mismatch.
    assert_eq!(
        approve_rotation(&env, &id, signers.get_unchecked(1), new_set, 2),
        Err(GovernanceError::RotationMismatch)
    );
}

#[test]
fn approve_rotation_by_non_signer_or_twice_fails() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 3).unwrap();
    let new_set = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), new_set.clone(), 1).unwrap();

    let outsider = Address::generate(&env);
    assert_eq!(
        approve_rotation(&env, &id, outsider, new_set.clone(), 1),
        Err(GovernanceError::NotASigner)
    );

    // Proposer already approved; approving again is rejected.
    assert_eq!(
        approve_rotation(&env, &id, signers.get_unchecked(0), new_set, 1),
        Err(GovernanceError::AlreadyApproved)
    );
}

#[test]
fn approving_a_scheduled_rotation_is_rejected() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let new_set = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), new_set.clone(), 1).unwrap();
    approve_rotation(&env, &id, signers.get_unchecked(1), new_set.clone(), 1).unwrap();

    // Threshold already reached (scheduled) — the approval stage is over.
    assert_eq!(
        approve_rotation(&env, &id, signers.get_unchecked(2), new_set, 1),
        Err(GovernanceError::RotationTimelockActive)
    );
}

#[test]
fn lone_signer_cannot_reset_a_gathering_rotation() {
    // Majority-protection: a single signer must not be able to blow away an
    // in-progress rotation's approvals by proposing a competing one. If they
    // could, they could stop any rotation from ever reaching threshold.
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let wanted = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), wanted.clone(), 1).unwrap();

    // A different signer trying to start a competing rotation is refused
    // while one is live — the original proposal's approvals are untouched.
    let competing = make_signers(&env, 2);
    assert_eq!(
        propose_rotation(&env, &id, signers.get_unchecked(2), competing, 1),
        Err(GovernanceError::RotationInProgress)
    );
    let p = pending_rotation(&env, &id).unwrap();
    assert_eq!(p.new_signers, wanted);
    assert_eq!(p.approvals.len(), 1);
}

#[test]
fn lone_signer_cannot_veto_a_scheduled_rotation() {
    // The other half of majority-protection: once a rotation is scheduled, a
    // single signer (e.g. one about to be removed) cannot displace it by
    // proposing something else.
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let new_set = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), new_set.clone(), 1).unwrap();
    approve_rotation(&env, &id, signers.get_unchecked(1), new_set.clone(), 1).unwrap();

    // Scheduled now. A signer proposing a replacement is refused.
    let escape = make_signers(&env, 2);
    assert_eq!(
        propose_rotation(&env, &id, signers.get_unchecked(2), escape, 1),
        Err(GovernanceError::RotationInProgress)
    );

    // The original scheduled rotation still stands and executes on schedule.
    advance(&env, ROTATION_TIMELOCK_LEDGERS);
    execute_rotation(&env, &id, signers.get_unchecked(0)).unwrap();
    assert_eq!(signers_of(&env, &id), new_set);
}

#[test]
fn gathering_rotation_expires_and_frees_the_slot() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let stale = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), stale.clone(), 1).unwrap();

    advance(&env, PROPOSAL_TTL_LEDGERS + 1);

    // Approving an expired gathering rotation reports expiry and clears it.
    assert_eq!(
        approve_rotation(&env, &id, signers.get_unchecked(1), stale, 1),
        Err(GovernanceError::RotationExpired)
    );
    assert!(pending_rotation(&env, &id).is_none());

    // Slot is free again: a fresh proposal succeeds.
    let fresh = make_signers(&env, 2);
    let scheduled =
        propose_rotation(&env, &id, signers.get_unchecked(0), fresh.clone(), 1).unwrap();
    assert!(!scheduled);
    assert_eq!(pending_rotation(&env, &id).unwrap().new_signers, fresh);
}

#[test]
fn expired_gathering_rotation_can_be_replaced_by_propose() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let stale = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), stale, 1).unwrap();

    advance(&env, PROPOSAL_TTL_LEDGERS + 1);

    // A new propose directly overwrites the expired (non-live) rotation
    // without needing anyone to touch the old one first.
    let fresh = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(1), fresh.clone(), 1).unwrap();
    let p = pending_rotation(&env, &id).unwrap();
    assert_eq!(p.new_signers, fresh);
    assert_eq!(p.approvals.get_unchecked(0), signers.get_unchecked(1));
}

#[test]
fn scheduled_rotation_execution_window_expires() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 1);
    init(&env, &id, signers.clone(), 1).unwrap();
    let new_set = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), new_set, 2).unwrap();

    // Past the timelock *and* the execution window that follows it.
    advance(&env, ROTATION_TIMELOCK_LEDGERS + PROPOSAL_TTL_LEDGERS + 1);
    assert_eq!(
        execute_rotation(&env, &id, signers.get_unchecked(0)),
        Err(GovernanceError::RotationExpired)
    );
    assert!(pending_rotation(&env, &id).is_none());
    // Original signer set is intact — the stale rotation never applied.
    assert_eq!(signers_of(&env, &id), signers);
}

#[test]
fn execute_with_no_pending_rotation_fails() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 2);
    init(&env, &id, signers.clone(), 1).unwrap();

    assert_eq!(
        execute_rotation(&env, &id, signers.get_unchecked(0)),
        Err(GovernanceError::NoPendingRotation)
    );
}

#[test]
fn executing_a_rotation_clears_a_pending_upgrade() {
    // An upgrade approved under the old signer set must not survive a
    // rotation — its approvals no longer represent the new set.
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();

    // Stage a half-approved upgrade (1 of 2).
    propose(&env, &id, signers.get_unchecked(0), hash(&env, 1)).unwrap();
    assert!(pending(&env, &id).is_some());

    // Rotate to a new set and execute.
    let new_set = make_signers(&env, 2);
    propose_rotation(&env, &id, signers.get_unchecked(0), new_set.clone(), 1).unwrap();
    approve_rotation(&env, &id, signers.get_unchecked(1), new_set, 1).unwrap();
    advance(&env, ROTATION_TIMELOCK_LEDGERS);
    execute_rotation(&env, &id, signers.get_unchecked(0)).unwrap();

    // The stale pending upgrade is gone.
    assert!(pending(&env, &id).is_none());
}

#[test]
fn after_rotation_only_new_signers_govern() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 3);
    init(&env, &id, signers.clone(), 2).unwrap();
    let new_set = make_signers(&env, 2);

    propose_rotation(&env, &id, signers.get_unchecked(0), new_set.clone(), 1).unwrap();
    approve_rotation(&env, &id, signers.get_unchecked(1), new_set.clone(), 1).unwrap();
    advance(&env, ROTATION_TIMELOCK_LEDGERS);
    execute_rotation(&env, &id, signers.get_unchecked(0)).unwrap();

    // An old signer who is not in the new set can no longer act.
    let removed = signers.get_unchecked(2);
    assert_eq!(
        check_signer(&env, &id, removed),
        Err(GovernanceError::NotASigner)
    );
    // A new signer can.
    assert!(check_signer(&env, &id, new_set.get_unchecked(0)).is_ok());
}

#[test]
fn rotation_emits_lifecycle_events() {
    let env = Env::default();
    env.mock_all_auths();
    let id = new_host(&env);
    let signers = make_signers(&env, 1);
    init(&env, &id, signers.clone(), 1).unwrap();
    let new_set = make_signers(&env, 2);

    propose_rotation(&env, &id, signers.get_unchecked(0), new_set, 2).unwrap();
    // A 1-of-1 propose reaches threshold, so it emits both "proposed" and
    // "scheduled".
    assert!(env.events().all().events().len() >= 2);

    advance(&env, ROTATION_TIMELOCK_LEDGERS);
    execute_rotation(&env, &id, signers.get_unchecked(0)).unwrap();
    // Execution emits "executed".
    assert!(!env.events().all().events().is_empty());
}
