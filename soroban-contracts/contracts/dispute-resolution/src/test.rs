#![cfg(test)]

use super::*;
use soroban_sdk::testutils::{Address as _, Ledger};
use soroban_sdk::{token, Address, BytesN, Env};

const STAKE: i128 = 100;
const MIN_JURORS: u32 = 3;
const COMMIT_WINDOW: u32 = 100;
const REVEAL_WINDOW: u32 = 100;
const FUNDING: i128 = 10_000;

// Ledger checkpoints relative to a dispute opened at ledger 1_000:
//   commit_deadline = 1_100, reveal_deadline = 1_200.
const OPEN_AT: u32 = 1_000;
const COMMIT_AT: u32 = 1_050; // within commit phase
const REVEAL_AT: u32 = 1_150; // within reveal phase
const RESOLVE_AT: u32 = 1_201; // after reveal phase

struct Fixture<'a> {
    env: Env,
    contract: DisputeResolutionClient<'a>,
    reputation: MockReputationClient<'a>,
    token: token::Client<'a>,
    token_admin: token::StellarAssetClient<'a>,
    admin: Address,
    plaintiff: Address,
    defendant: Address,
}

#[soroban_sdk::contract]
pub struct MockReputation;

#[soroban_sdk::contractimpl]
impl MockReputation {
    pub fn get_reputation_score_x10000(env: Env, worker: Address) -> u64 {
        env.storage().instance().get(&worker).unwrap_or(0)
    }

    pub fn set_score(env: Env, worker: Address, score: u64) {
        env.storage().instance().set(&worker, &score);
    }
}

fn setup<'a>() -> Fixture<'a> {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let token_issuer = Address::generate(&env);
    let plaintiff = Address::generate(&env);
    let defendant = Address::generate(&env);

    let sac = env.register_stellar_asset_contract_v2(token_issuer.clone());
    let token_address = sac.address();
    let token = token::Client::new(&env, &token_address);
    let token_admin = token::StellarAssetClient::new(&env, &token_address);

    let reputation_id = env.register(MockReputation, ());
    let reputation = MockReputationClient::new(&env, &reputation_id);

    let contract_id = env.register(DisputeResolution, ());
    let contract = DisputeResolutionClient::new(&env, &contract_id);
    contract.initialize(
        &admin,
        &token_address,
        &Config {
            juror_stake: STAKE,
            min_jurors: MIN_JURORS,
            commit_window: COMMIT_WINDOW,
            reveal_window: REVEAL_WINDOW,
            reputation_contract: reputation_id.clone(),
            base_vote_weight: 10_000,
            close_call_margin_bps: 500,
        },
    );

    Fixture {
        env,
        contract,
        reputation,
        token,
        token_admin,
        admin,
        plaintiff,
        defendant,
    }
}

fn set_ledger(env: &Env, seq: u32) {
    env.ledger().with_mut(|l| l.sequence_number = seq);
}

fn new_juror(f: &Fixture) -> Address {
    let j = Address::generate(&f.env);
    f.token_admin.mint(&j, &FUNDING);
    j
}

fn salt(env: &Env, seed: u8) -> BytesN<32> {
    BytesN::from_array(env, &[seed; 32])
}

/// Commit `vote` for `juror` in dispute `id`, using `seed` to derive the salt.
/// The same `seed` must be passed to [`reveal`] to recompute the salt.
fn commit(f: &Fixture, id: u64, juror: &Address, vote: bool, seed: u8) {
    let s = salt(&f.env, seed);
    let commitment = f.contract.compute_commitment(juror, &vote, &s);
    f.contract.commit_vote(&id, juror, &commitment);
}

fn reveal(f: &Fixture, id: u64, juror: &Address, vote: bool, seed: u8) {
    let s = salt(&f.env, seed);
    f.contract.reveal_vote(&id, juror, &vote, &s);
}

fn open_default(f: &Fixture, id: u64) {
    set_ledger(&f.env, OPEN_AT);
    f.contract.open_dispute(&id, &f.plaintiff, &f.defendant);
}

// ---------------------------------------------------------------------------
// Initialization
// ---------------------------------------------------------------------------

#[test]
fn initialize_stores_config() {
    let f = setup();
    let cfg = f.contract.get_config();
    assert_eq!(cfg.juror_stake, STAKE);
    assert_eq!(cfg.min_jurors, MIN_JURORS);
    assert_eq!(cfg.commit_window, COMMIT_WINDOW);
    assert_eq!(cfg.reveal_window, REVEAL_WINDOW);
    assert_eq!(cfg.base_vote_weight, 10_000);
    assert_eq!(cfg.close_call_margin_bps, 500);
    assert_eq!(f.contract.get_admin(), f.admin);
}

#[test]
fn double_initialize_fails() {
    let f = setup();
    let token = f.contract.get_token();
    let res = f.contract.try_initialize(
        &f.admin,
        &token,
        &Config {
            juror_stake: STAKE,
            min_jurors: MIN_JURORS,
            commit_window: COMMIT_WINDOW,
            reveal_window: REVEAL_WINDOW,
            reputation_contract: f.reputation.address.clone(),
            base_vote_weight: 10_000,
            close_call_margin_bps: 500,
        },
    );
    assert_eq!(res, Err(Ok(Error::AlreadyInitialized)));
}

#[test]
fn initialize_rejects_bad_config() {
    let env = Env::default();
    env.mock_all_auths();
    let admin = Address::generate(&env);
    let token = Address::generate(&env);
    let id = env.register(DisputeResolution, ());
    let contract = DisputeResolutionClient::new(&env, &id);

    // Zero stake.
    assert_eq!(
        contract.try_initialize(
            &admin,
            &token,
            &Config {
                juror_stake: 0,
                min_jurors: MIN_JURORS,
                commit_window: COMMIT_WINDOW,
                reveal_window: REVEAL_WINDOW,
                reputation_contract: Address::generate(&env),
                base_vote_weight: 10_000,
                close_call_margin_bps: 500,
            }
        ),
        Err(Ok(Error::InvalidConfig))
    );
    // Zero quorum.
    assert_eq!(
        contract.try_initialize(
            &admin,
            &token,
            &Config {
                juror_stake: STAKE,
                min_jurors: 0,
                commit_window: COMMIT_WINDOW,
                reveal_window: REVEAL_WINDOW,
                reputation_contract: Address::generate(&env),
                base_vote_weight: 10_000,
                close_call_margin_bps: 500,
            }
        ),
        Err(Ok(Error::InvalidConfig))
    );
}

// ---------------------------------------------------------------------------
// Opening disputes
// ---------------------------------------------------------------------------

#[test]
fn open_dispute_sets_deadlines() {
    let f = setup();
    open_default(&f, 1);
    let d = f.contract.get_dispute(&1);
    assert_eq!(d.commit_deadline, OPEN_AT + COMMIT_WINDOW);
    assert_eq!(d.reveal_deadline, OPEN_AT + COMMIT_WINDOW + REVEAL_WINDOW);
    assert_eq!(d.outcome, Outcome::Undecided);
    assert_eq!(d.juror_count, 0);
    assert_eq!(d.total_committed_weight, 0);
    assert!(!d.resolved);
}

#[test]
fn open_duplicate_dispute_fails() {
    let f = setup();
    open_default(&f, 1);
    let res = f.contract.try_open_dispute(&1, &f.plaintiff, &f.defendant);
    assert_eq!(res, Err(Ok(Error::DisputeExists)));
}

#[test]
fn open_same_parties_fails() {
    let f = setup();
    set_ledger(&f.env, OPEN_AT);
    let res = f.contract.try_open_dispute(&1, &f.plaintiff, &f.plaintiff);
    assert_eq!(res, Err(Ok(Error::SameParties)));
}

#[test]
fn commit_on_missing_dispute_fails() {
    let f = setup();
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    let c = f
        .contract
        .compute_commitment(&juror, &true, &salt(&f.env, 1));
    let res = f.contract.try_commit_vote(&99, &juror, &c);
    assert_eq!(res, Err(Ok(Error::DisputeNotFound)));
}

// ---------------------------------------------------------------------------
// Full lifecycle: happy paths
// ---------------------------------------------------------------------------

#[test]
fn full_lifecycle_plaintiff_wins() {
    let f = setup();
    open_default(&f, 1);

    let j1 = new_juror(&f);
    let j2 = new_juror(&f);
    let j3 = new_juror(&f);

    // Commit phase: two for the plaintiff, one for the defendant.
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &j1, true, 11);
    commit(&f, 1, &j2, true, 12);
    commit(&f, 1, &j3, false, 13);
    // Each juror staked, contract holds the pooled collateral.
    assert_eq!(f.token.balance(&j1), FUNDING - STAKE);
    assert_eq!(f.token.balance(&f.contract.address), STAKE * 3);

    // Reveal phase.
    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &j1, true, 11);
    reveal(&f, 1, &j2, true, 12);
    reveal(&f, 1, &j3, false, 13);

    // Resolve: plaintiff wins 2-1. (Weights: 20k to 10k)
    // Diff is 10k, total is 30k. Margin is 33.3%, > 5% (500 bps), so not a close call.
    set_ledger(&f.env, RESOLVE_AT);
    assert_eq!(f.contract.resolve(&1), Outcome::Plaintiff);
    let d = f.contract.get_dispute(&1);
    assert!(!d.is_close_call);
    assert_eq!(d.total_slashed_pot, 100);

    // Winners reclaim stake + reward.
    // Total winning weight = 20,000. Each winner has 10,000 weight.
    // Reward each = (10_000 * 100) / 20_000 = 50.
    assert_eq!(f.contract.withdraw(&1, &j1), STAKE + 50);
    assert_eq!(f.contract.withdraw(&1, &j2), STAKE + 50);
    assert_eq!(
        f.contract.try_withdraw(&1, &j3),
        Err(Ok(Error::NothingToWithdraw))
    );

    assert_eq!(f.token.balance(&j1), FUNDING + 50);
    assert_eq!(f.token.balance(&j2), FUNDING + 50);
    assert_eq!(f.token.balance(&j3), FUNDING - STAKE); // slashed
    assert_eq!(f.token.balance(&f.contract.address), 0);
}

#[test]
fn full_lifecycle_defendant_wins() {
    let f = setup();
    open_default(&f, 1);

    let j1 = new_juror(&f);
    let j2 = new_juror(&f);
    let j3 = new_juror(&f);

    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &j1, false, 21);
    commit(&f, 1, &j2, false, 22);
    commit(&f, 1, &j3, true, 23);

    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &j1, false, 21);
    reveal(&f, 1, &j2, false, 22);
    reveal(&f, 1, &j3, true, 23);

    set_ledger(&f.env, RESOLVE_AT);
    assert_eq!(f.contract.resolve(&1), Outcome::Defendant);

    assert_eq!(f.contract.withdraw(&1, &j1), STAKE + 50);
    assert_eq!(f.contract.withdraw(&1, &j2), STAKE + 50);
    assert_eq!(
        f.contract.try_withdraw(&1, &j3),
        Err(Ok(Error::NothingToWithdraw))
    );
    assert_eq!(f.token.balance(&f.contract.address), 0);
}

#[test]
fn no_show_juror_is_slashed_and_pot_goes_to_winners() {
    let f = setup();
    open_default(&f, 1);

    let j1 = new_juror(&f);
    let j2 = new_juror(&f);
    let j3 = new_juror(&f);
    let j4 = new_juror(&f); // will commit but never reveal (no-show)

    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &j1, true, 31);
    commit(&f, 1, &j2, true, 32);
    commit(&f, 1, &j3, true, 33);
    commit(&f, 1, &j4, true, 34);

    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &j1, true, 31);
    reveal(&f, 1, &j2, true, 32);
    reveal(&f, 1, &j3, true, 33);
    // j4 never reveals.

    set_ledger(&f.env, RESOLVE_AT);
    assert_eq!(f.contract.resolve(&1), Outcome::Plaintiff);
    let d = f.contract.get_dispute(&1);
    // 3 winners, 1 no-show. Not a close call since 3-0.
    // Total winning weight = 30,000. Each winner has 10,000 weight.
    // Pot = 100 from no-show.
    // Reward each = (10_000 * 100) / 30_000 = 33.
    assert_eq!(d.total_slashed_pot, 100);

    assert_eq!(f.contract.withdraw(&1, &j1), STAKE + 33);
    assert_eq!(f.contract.withdraw(&1, &j2), STAKE + 33);
    assert_eq!(f.contract.withdraw(&1, &j3), STAKE + 33);
    // No-show is slashed.
    assert_eq!(
        f.contract.try_withdraw(&1, &j4),
        Err(Ok(Error::NothingToWithdraw))
    );
    // 1 unit of integer-division dust is retained by the contract.
    assert_eq!(f.token.balance(&f.contract.address), 1);
}

#[test]
fn close_call_refunds_minority() {
    let f = setup();
    open_default(&f, 1);

    let j1 = new_juror(&f);
    let j2 = new_juror(&f);
    let j3 = new_juror(&f);
    let j4 = new_juror(&f); // No-show

    f.reputation.set_score(&j1, &20_000); // 20k, Plaintiff
    f.reputation.set_score(&j2, &19_000); // 19k, Defendant
    f.reputation.set_score(&j3, &0); // 10k (base), Plaintiff
    f.reputation.set_score(&j4, &0); // 10k (base), no-show

    // Total Plaintiff: 30k
    // Total Defendant: 19k
    // Wait, 30k - 19k = 11k margin. 11k / 49k = 22% (not close).
    // Let's make it close!
    // J1: 20k, Plaintiff
    // J2: 19k, Defendant
    // J3: 1k, Defendant -> total Defendant = 20k.
    // Wait, Plaintiff 20k, Defendant 20k is a tie.
    // Let's do J1: 20k (Plaintiff), J2: 18k (Defendant), J3: 1k (Defendant). Plaintiff wins 20k vs 19k. Margin 1k / 39k = 2.5%.
    f.reputation.set_score(&j1, &20_000);
    f.reputation.set_score(&j2, &18_000);
    f.reputation.set_score(&j3, &1_000);
    f.reputation.set_score(&j4, &10_000); // No-show

    // Note: Since base_vote_weight is 10,000, J3's weight will be max(1000, 10000) = 10000!
    // J2's weight = max(18000, 10000) = 18000.
    // Defendant total = 18000 + 10000 = 28000.
    // Plaintiff total (J1) = 20000.
    // Then Defendant wins 28k to 20k. Margin 8k / 48k = 16.6% (Not close).
    // Let's set J1: 30_000 (Plaintiff), J2: 29_000 (Defendant), J3: 10_000 (Defendant).
    // Plaintiff: 30k. Defendant: 39k. Defendant wins by 9k. 9k / 69k = 13% (Not close).
    // Let's set close_call_margin_bps to a higher value for this test? No, it's set in Config to 500 (5%).
    // Let's just adjust weights:
    // J1: 40_000 Plaintiff
    // J2: 29_000 Defendant
    // J3: 10_000 Defendant
    // Plaintiff: 40k. Defendant: 39k. Plaintiff wins! Margin = 1k. Total = 79k. Margin = 1k / 79k = 1.2%. This is close!
    f.reputation.set_score(&j1, &40_000);
    f.reputation.set_score(&j2, &29_000);
    f.reputation.set_score(&j3, &10_000);

    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &j1, true, 81);
    commit(&f, 1, &j2, false, 82);
    commit(&f, 1, &j3, false, 83);
    commit(&f, 1, &j4, false, 84); // No show

    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &j1, true, 81);
    reveal(&f, 1, &j2, false, 82);
    reveal(&f, 1, &j3, false, 83);

    set_ledger(&f.env, RESOLVE_AT);
    assert_eq!(f.contract.resolve(&1), Outcome::Plaintiff);

    let d = f.contract.get_dispute(&1);
    assert!(d.is_close_call);
    // Slashed pot should ONLY be J3's stake (100)
    assert_eq!(d.total_slashed_pot, 100);

    // J1 is the winner, gets stake + reward (100 pot / 1 winner = 100)
    assert_eq!(f.contract.withdraw(&1, &j1), STAKE + 100);
    // J2 and J3 are the minority, get refunded because of close call
    assert_eq!(f.contract.withdraw(&1, &j2), STAKE);
    assert_eq!(f.contract.withdraw(&1, &j3), STAKE);
    // J4 is slashed
    assert_eq!(
        f.contract.try_withdraw(&1, &j4),
        Err(Ok(Error::NothingToWithdraw))
    );
}

#[test]
fn tie_refunds_all_stakers() {
    let f = setup();
    open_default(&f, 1);

    let j1 = new_juror(&f);
    let j2 = new_juror(&f);
    let j3 = new_juror(&f);
    let j4 = new_juror(&f);

    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &j1, true, 41);
    commit(&f, 1, &j2, true, 42);
    commit(&f, 1, &j3, false, 43);
    commit(&f, 1, &j4, false, 44);

    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &j1, true, 41);
    reveal(&f, 1, &j2, true, 42);
    reveal(&f, 1, &j3, false, 43);
    reveal(&f, 1, &j4, false, 44);

    set_ledger(&f.env, RESOLVE_AT);
    assert_eq!(f.contract.resolve(&1), Outcome::Tie);

    // Everyone reclaims exactly their stake; no slashing.
    for j in [&j1, &j2, &j3, &j4] {
        assert_eq!(f.contract.withdraw(&1, j), STAKE);
        assert_eq!(f.token.balance(j), FUNDING);
    }
    assert_eq!(f.token.balance(&f.contract.address), 0);
}

#[test]
fn quorum_failure_refunds_including_non_revealers() {
    let f = setup();
    open_default(&f, 1);

    let j1 = new_juror(&f);
    let j2 = new_juror(&f);
    let j3 = new_juror(&f); // commits but never reveals

    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &j1, true, 51);
    commit(&f, 1, &j2, false, 52);
    commit(&f, 1, &j3, true, 53);

    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &j1, true, 51);
    reveal(&f, 1, &j2, false, 52);
    // j3 does not reveal -> only 2 revealed, below the quorum of 3.

    set_ledger(&f.env, RESOLVE_AT);
    assert_eq!(f.contract.resolve(&1), Outcome::QuorumFailed);

    // All three, including the non-revealer, get their stake back.
    for j in [&j1, &j2, &j3] {
        assert_eq!(f.contract.withdraw(&1, j), STAKE);
        assert_eq!(f.token.balance(j), FUNDING);
    }
    assert_eq!(f.token.balance(&f.contract.address), 0);
}

// ---------------------------------------------------------------------------
// Phase / state-machine enforcement
// ---------------------------------------------------------------------------

#[test]
fn commit_after_deadline_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, OPEN_AT + COMMIT_WINDOW + 1); // past commit deadline
    let c = f
        .contract
        .compute_commitment(&juror, &true, &salt(&f.env, 1));
    assert_eq!(
        f.contract.try_commit_vote(&1, &juror, &c),
        Err(Ok(Error::NotCommitPhase))
    );
}

#[test]
fn reveal_during_commit_phase_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &juror, true, 1);
    // Still in the commit phase.
    assert_eq!(
        f.contract
            .try_reveal_vote(&1, &juror, &true, &salt(&f.env, 1)),
        Err(Ok(Error::NotRevealPhase))
    );
}

#[test]
fn reveal_after_deadline_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &juror, true, 1);
    set_ledger(&f.env, RESOLVE_AT); // past reveal deadline
    assert_eq!(
        f.contract
            .try_reveal_vote(&1, &juror, &true, &salt(&f.env, 1)),
        Err(Ok(Error::NotRevealPhase))
    );
}

#[test]
fn double_commit_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &juror, true, 1);
    let c = f
        .contract
        .compute_commitment(&juror, &true, &salt(&f.env, 1));
    assert_eq!(
        f.contract.try_commit_vote(&1, &juror, &c),
        Err(Ok(Error::AlreadyCommitted))
    );
}

#[test]
fn double_reveal_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &juror, true, 1);
    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &juror, true, 1);
    assert_eq!(
        f.contract
            .try_reveal_vote(&1, &juror, &true, &salt(&f.env, 1)),
        Err(Ok(Error::AlreadyRevealed))
    );
}

#[test]
fn reveal_without_commit_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, REVEAL_AT);
    assert_eq!(
        f.contract
            .try_reveal_vote(&1, &juror, &true, &salt(&f.env, 1)),
        Err(Ok(Error::NotCommitted))
    );
}

#[test]
fn resolve_before_reveal_deadline_fails() {
    let f = setup();
    open_default(&f, 1);
    set_ledger(&f.env, OPEN_AT + COMMIT_WINDOW + REVEAL_WINDOW); // == reveal_deadline
    assert_eq!(f.contract.try_resolve(&1), Err(Ok(Error::NotResolvable)));
}

#[test]
fn double_resolve_fails() {
    let f = setup();
    open_default(&f, 1);
    set_ledger(&f.env, RESOLVE_AT);
    f.contract.resolve(&1);
    assert_eq!(f.contract.try_resolve(&1), Err(Ok(Error::AlreadyResolved)));
}

#[test]
fn withdraw_before_resolve_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &juror, true, 1);
    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &juror, true, 1);
    assert_eq!(
        f.contract.try_withdraw(&1, &juror),
        Err(Ok(Error::NotResolved))
    );
}

#[test]
fn double_withdraw_fails() {
    let f = setup();
    open_default(&f, 1);

    let j1 = new_juror(&f);
    let j2 = new_juror(&f);
    let j3 = new_juror(&f);

    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &j1, true, 61);
    commit(&f, 1, &j2, true, 62);
    commit(&f, 1, &j3, true, 63);
    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &j1, true, 61);
    reveal(&f, 1, &j2, true, 62);
    reveal(&f, 1, &j3, true, 63);
    set_ledger(&f.env, RESOLVE_AT);
    f.contract.resolve(&1);

    f.contract.withdraw(&1, &j1);
    assert_eq!(
        f.contract.try_withdraw(&1, &j1),
        Err(Ok(Error::AlreadyWithdrawn))
    );
}

// ---------------------------------------------------------------------------
// Adversarial: commit-reveal integrity & sybil/front-running
// ---------------------------------------------------------------------------

#[test]
fn reveal_with_wrong_vote_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &juror, true, 1); // committed to `true`
    set_ledger(&f.env, REVEAL_AT);
    // Try to reveal the opposite vote with the right salt -> hash mismatch.
    assert_eq!(
        f.contract
            .try_reveal_vote(&1, &juror, &false, &salt(&f.env, 1)),
        Err(Ok(Error::InvalidReveal))
    );
}

#[test]
fn reveal_with_wrong_salt_fails() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &juror, true, 1);
    set_ledger(&f.env, REVEAL_AT);
    assert_eq!(
        f.contract
            .try_reveal_vote(&1, &juror, &true, &salt(&f.env, 99)),
        Err(Ok(Error::InvalidReveal))
    );
}

#[test]
fn copycat_commitment_cannot_be_revealed() {
    // A front-runner copies a victim's commitment hash and submits it as their
    // own. Because the hash binds the committer's address, the copycat can never
    // produce a matching reveal from their own address.
    let f = setup();
    open_default(&f, 1);

    let victim = new_juror(&f);
    let copycat = new_juror(&f);

    set_ledger(&f.env, COMMIT_AT);
    let victim_salt = salt(&f.env, 1);
    let victim_commitment = f.contract.compute_commitment(&victim, &true, &victim_salt);
    f.contract.commit_vote(&1, &victim, &victim_commitment);
    // Copycat replays the exact same commitment bytes.
    f.contract.commit_vote(&1, &copycat, &victim_commitment);

    set_ledger(&f.env, REVEAL_AT);
    // Victim reveals fine.
    f.contract.reveal_vote(&1, &victim, &true, &victim_salt);
    // Copycat tries the victim's (vote, salt) but from a different address.
    assert_eq!(
        f.contract
            .try_reveal_vote(&1, &copycat, &true, &victim_salt),
        Err(Ok(Error::InvalidReveal))
    );
}

#[test]
fn losing_juror_cannot_drain_via_repeated_withdraw() {
    // Adversarial: a slashed juror repeatedly calling withdraw must never pay
    // out, and winners' withdrawals stay bounded by the pool.
    let f = setup();
    open_default(&f, 1);

    let j1 = new_juror(&f);
    let j2 = new_juror(&f);
    let j3 = new_juror(&f);

    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &j1, true, 71);
    commit(&f, 1, &j2, true, 72);
    commit(&f, 1, &j3, false, 73);
    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &j1, true, 71);
    reveal(&f, 1, &j2, true, 72);
    reveal(&f, 1, &j3, false, 73);
    set_ledger(&f.env, RESOLVE_AT);
    f.contract.resolve(&1);

    // The slashed loser gets nothing, no matter how many times they try.
    for _ in 0..5 {
        assert_eq!(
            f.contract.try_withdraw(&1, &j3),
            Err(Ok(Error::NothingToWithdraw))
        );
    }
    // Winners can still withdraw their fair share afterwards.
    assert_eq!(f.contract.withdraw(&1, &j1), STAKE + 50);
    assert_eq!(f.contract.withdraw(&1, &j2), STAKE + 50);
    assert_eq!(f.token.balance(&f.contract.address), 0);
}

#[test]
fn get_juror_reflects_commit_and_reveal() {
    let f = setup();
    open_default(&f, 1);
    let juror = new_juror(&f);
    set_ledger(&f.env, COMMIT_AT);
    commit(&f, 1, &juror, true, 1);

    let before = f.contract.get_juror(&1, &juror);
    assert!(!before.revealed);
    assert!(!before.withdrawn);

    set_ledger(&f.env, REVEAL_AT);
    reveal(&f, 1, &juror, true, 1);
    let after = f.contract.get_juror(&1, &juror);
    assert!(after.revealed);
    assert!(after.vote);
}
