use soroban_sdk::{
    contract, testutils::Address as _, testutils::Ledger, Address, BytesN, Env, Vec,
};

use crate::{
    approve_upgrade, cancel_upgrade, current_storage_version, get_pending_upgrade, get_signers,
    get_threshold, init_governance, mark_migrated, propose_upgrade, require_signer,
    GovernanceError, GovernanceInit, PendingUpgrade, PROPOSAL_TTL_LEDGERS,
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
