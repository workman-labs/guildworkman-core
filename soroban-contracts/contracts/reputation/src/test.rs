#![cfg(test)]

use super::*;
use soroban_sdk::testutils::Address as _;
use soroban_sdk::testutils::Ledger;
use soroban_sdk::{BytesN, Env};

fn default_config() -> Config {
    Config {
        window: 100,
        reviewer_cap: 5,
        global_cap: 20,
        min_stake: 0,
        decay_rate_bps: 1,
        max_age_ledgers: 10_000,
    }
}

fn single_signer(env: &Env) -> (soroban_sdk::Vec<Address>, Address) {
    let signer = Address::generate(env);
    let mut signers = soroban_sdk::Vec::new(env);
    signers.push_back(signer.clone());
    (signers, signer)
}

fn gov_init(signers: soroban_sdk::Vec<Address>, threshold: u32) -> governance::GovernanceInit {
    governance::GovernanceInit { signers, threshold }
}

fn setup() -> (Env, ReputationContractClient<'static>, Address, Address) {
    let env = Env::default();
    env.mock_all_auths();

    let client = Address::generate(&env);
    let worker = Address::generate(&env);

    let contract_id = env.register(ReputationContract, ());
    let contract = ReputationContractClient::new(&env, &contract_id);

    let (signers, _signer) = single_signer(&env);
    contract.initialize(
        &Address::generate(&env),
        &default_config(),
        &gov_init(signers, 1),
    );

    (env, contract, client, worker)
}

fn setup_with_admin() -> (
    Env,
    ReputationContractClient<'static>,
    Address,
    Address,
    Address,
) {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let client = Address::generate(&env);
    let worker = Address::generate(&env);

    let contract_id = env.register(ReputationContract, ());
    let contract = ReputationContractClient::new(&env, &contract_id);

    let (signers, _signer) = single_signer(&env);
    contract.initialize(&admin, &default_config(), &gov_init(signers, 1));

    (env, contract, admin, client, worker)
}

/// Same as `setup_with_admin`, but also hands back the governance signer
/// and the raw wasm hash used across the upgrade-governance tests.
fn setup_with_governance() -> (
    Env,
    ReputationContractClient<'static>,
    Address,
    soroban_sdk::Vec<Address>,
) {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let contract_id = env.register(ReputationContract, ());
    let contract = ReputationContractClient::new(&env, &contract_id);

    let mut signers = soroban_sdk::Vec::new(&env);
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));

    contract.initialize(&admin, &default_config(), &gov_init(signers.clone(), 2));

    (env, contract, admin, signers)
}

fn dummy_hash(env: &Env) -> BytesN<32> {
    BytesN::from_array(env, &[0u8; 32])
}

fn other_hash(env: &Env) -> BytesN<32> {
    BytesN::from_array(env, &[7u8; 32])
}

fn set_ledger(env: &Env, seq: u32) {
    env.ledger().with_mut(|l| l.sequence_number = seq);
}

// ===========================================================================
// Success paths
// ===========================================================================

#[test]
fn submit_attestation_updates_accumulator() {
    let (env, contract, client, worker) = setup();

    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));
    contract.submit_attestation(&2, &client, &worker, &3, &dummy_hash(&env));

    let acc = contract.get_weighted_score(&worker);
    assert_eq!(acc.count, 2);
    assert_eq!(contract.get_attestation_count(&worker), 2);

    // Both at same ledger, no decay. Stake=0 -> treated as 1.
    // weighted_sum = 5*1*10000 + 3*1*10000 = 80000
    // weight_sum = 1*10000 + 1*10000 = 20000
    // score = (80000 * 10000) / 20000 = 40000 = 4.0000
    let score = contract.get_reputation_score_x10000(&worker);
    assert_eq!(score, 40000);
}

#[test]
fn get_reputation_score_x10000_basic() {
    let (env, contract, client, worker) = setup();

    contract.submit_attestation(&1, &client, &worker, &4, &dummy_hash(&env));

    // Single attestation, rating=4, stake=0->1, decay=10000
    // weighted_sum = 4 * 1 * 10000 = 40000
    // weight_sum = 1 * 10000 = 10000
    // score = (40000 * 10000) / 10000 = 40000 = 4.0000
    let score = contract.get_reputation_score_x10000(&worker);
    assert_eq!(score, 40000);
}

#[test]
fn stake_weighting_favors_higher_stake() {
    let (env, contract, _admin, client_a, worker) = setup_with_admin();
    let client_b = Address::generate(&env);

    // Client A: stake=1000, rating=5
    // Client B: stake=100, rating=4
    contract.set_stake(&client_a, &1000);
    contract.set_stake(&client_b, &100);

    contract.submit_attestation(&1, &client_a, &worker, &5, &dummy_hash(&env));
    contract.submit_attestation(&2, &client_b, &worker, &4, &dummy_hash(&env));

    // weighted_sum = 5*1000*10000 + 4*100*10000 = 50000000 + 4000000 = 54000000
    // weight_sum = 1000*10000 + 100*10000 = 10000000 + 1000000 = 11000000
    // score = (54000000 * 10000) / 11000000 = 49090 (approx 4.9090)
    let score = contract.get_reputation_score_x10000(&worker);
    assert!(
        score > 45000,
        "score should be closer to 5 than 4, got {score}"
    );
    assert!(score < 50000, "score should be less than 5, got {score}");
}

#[test]
fn admin_set_stake_works() {
    let (env, contract, _admin, client, worker) = setup_with_admin();

    contract.set_stake(&client, &500);
    assert_eq!(contract.get_stake(&client), 500);

    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));
    let att = contract.get_attestation(&worker, &0).unwrap();
    assert_eq!(att.stake_at_time, 500);
}

// ===========================================================================
// Failure paths
// ===========================================================================

#[test]
fn cannot_review_same_appointment_twice() {
    let (env, contract, client, worker) = setup();

    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));
    let result = contract.try_submit_attestation(&1, &client, &worker, &4, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::AlreadyReviewed)));
}

#[test]
fn rejects_out_of_range_rating() {
    let (env, contract, client, worker) = setup();

    let result = contract.try_submit_attestation(&1, &client, &worker, &6, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::InvalidRating)));

    let result = contract.try_submit_attestation(&1, &client, &worker, &0, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::InvalidRating)));
}

#[test]
fn self_dealing_rejected() {
    let (env, contract, client, _worker) = setup();

    let result = contract.try_submit_attestation(&1, &client, &client, &5, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::SelfDealing)));
}

#[test]
fn insufficient_stake_rejected() {
    let (env, contract, _admin, client, worker) = setup_with_admin();

    // Update config to require min_stake
    let mut config = default_config();
    config.min_stake = 100;
    contract.update_config(&config);

    // Client has no stake
    let result = contract.try_submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::InsufficientStake)));

    // Set stake below minimum
    contract.set_stake(&client, &50);
    let result = contract.try_submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::InsufficientStake)));

    // Set stake at minimum — should succeed
    contract.set_stake(&client, &100);
    let result = contract.try_submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));
    assert!(result.is_ok());
}

#[test]
fn reviewer_rate_limited() {
    let (env, contract, client, _worker) = setup();

    // reviewer_cap is 5, submit 5
    for i in 1..=5 {
        let w = Address::generate(&env);
        contract.submit_attestation(&(i as u64), &client, &w, &4, &dummy_hash(&env));
    }

    // 6th should fail
    let w = Address::generate(&env);
    let result = contract.try_submit_attestation(&6, &client, &w, &4, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::ReviewerRateLimited)));
}

#[test]
fn global_rate_limited() {
    let (env, contract, _admin, _client, _worker) = setup_with_admin();

    // Set low global cap
    let mut config = default_config();
    config.global_cap = 3;
    config.reviewer_cap = 100;
    contract.update_config(&config);

    // 3 different reviewers fill the global cap
    for i in 1..=3 {
        let c = Address::generate(&env);
        let w = Address::generate(&env);
        contract.submit_attestation(&(i as u64), &c, &w, &4, &dummy_hash(&env));
    }

    // 4th should fail
    let c = Address::generate(&env);
    let w = Address::generate(&env);
    let result = contract.try_submit_attestation(&4, &c, &w, &4, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::GlobalRateLimited)));
}

#[test]
fn double_initialize_fails() {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let contract_id = env.register(ReputationContract, ());
    let contract = ReputationContractClient::new(&env, &contract_id);

    let (signers, _signer) = single_signer(&env);
    contract.initialize(&admin, &default_config(), &gov_init(signers.clone(), 1));
    let result = contract.try_initialize(&admin, &default_config(), &gov_init(signers, 1));
    assert_eq!(result, Err(Ok(Error::AlreadyInitialized)));
}

#[test]
fn no_reviews_error() {
    let (_env, contract, _client, worker) = setup();

    let result = contract.try_get_reputation_score_x10000(&worker);
    assert_eq!(result, Err(Ok(Error::NoReviews)));
}

#[test]
fn invalid_config_rejected() {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let contract_id = env.register(ReputationContract, ());
    let contract = ReputationContractClient::new(&env, &contract_id);
    let (signers, _signer) = single_signer(&env);

    let mut config = default_config();
    config.window = 0;
    let result = contract.try_initialize(&admin, &config, &gov_init(signers.clone(), 1));
    assert_eq!(result, Err(Ok(Error::InvalidConfig)));

    config = default_config();
    config.reviewer_cap = 0;
    let result = contract.try_initialize(&admin, &config, &gov_init(signers.clone(), 1));
    assert_eq!(result, Err(Ok(Error::InvalidConfig)));

    config = default_config();
    config.decay_rate_bps = 0;
    let result = contract.try_initialize(&admin, &config, &gov_init(signers, 1));
    assert_eq!(result, Err(Ok(Error::InvalidConfig)));
}

// ===========================================================================
// Time decay tests
// ===========================================================================

#[test]
fn time_decay_reduces_old_attestation_weight() {
    let (env, contract, client, worker) = setup();

    // Submit at ledger 1000
    set_ledger(&env, 1000);
    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));

    // The accumulator was computed at submission time with age=0
    let acc = contract.get_weighted_score(&worker);
    assert_eq!(acc.weighted_sum, 50000);
    assert_eq!(acc.weight_sum, 10000);

    // After recalculation at ledger 6000:
    set_ledger(&env, 6000);
    contract.recalculate_score(&worker);
    let acc = contract.get_weighted_score(&worker);
    // age = 6000 - 1000 = 5000, decay = 10000 - 5000*1 = 5000
    // weighted_sum = 5 * 1 * 5000 = 25000
    // weight_sum = 1 * 5000 = 5000
    assert_eq!(acc.weighted_sum, 25000);
    assert_eq!(acc.weight_sum, 5000);

    // Score should still be 5.0 (single attestation, just decayed equally)
    let score = contract.get_reputation_score_x10000(&worker);
    assert_eq!(score, 50000);
}

#[test]
fn fully_decayed_attestation_yields_no_reviews_after_recalculate() {
    let (env, contract, client, worker) = setup();

    // Submit at ledger 100
    set_ledger(&env, 100);
    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));

    // Advance past max_age_ledgers (10000)
    set_ledger(&env, 10200);

    // Recalculate — attestation fully decayed
    contract.recalculate_score(&worker);
    let acc = contract.get_weighted_score(&worker);
    assert_eq!(acc.weight_sum, 0);

    // Score query should return NoReviews
    let result = contract.try_get_reputation_score_x10000(&worker);
    assert_eq!(result, Err(Ok(Error::NoReviews)));
}

#[test]
fn decay_rate_boundary_immediate_decay() {
    let (env, contract, _admin, client, worker) = setup_with_admin();

    // Set decay_rate_bps = 10000, max_age = 2
    let mut config = default_config();
    config.decay_rate_bps = 10_000;
    config.max_age_ledgers = 2;
    contract.update_config(&config);

    set_ledger(&env, 1000);
    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));

    // At ledger 1001 (age=1): decay = 10000 - 1*10000 = 0
    set_ledger(&env, 1001);
    contract.recalculate_score(&worker);
    let acc = contract.get_weighted_score(&worker);
    assert_eq!(acc.weight_sum, 0);
}

// ===========================================================================
// Config update test
// ===========================================================================

#[test]
fn config_update_affects_new_submissions() {
    let (env, contract, _admin, client, worker) = setup_with_admin();

    // Submit with original config (decay_rate_bps=1)
    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));

    // Update config to aggressive decay
    let mut config = default_config();
    config.decay_rate_bps = 100;
    config.max_age_ledgers = 50;
    contract.update_config(&config);

    // Advance 30 ledgers and submit another
    set_ledger(&env, 30);
    let client2 = Address::generate(&env);
    contract.submit_attestation(&2, &client2, &worker, &3, &dummy_hash(&env));

    // Recalculate at ledger 30
    contract.recalculate_score(&worker);
    let acc = contract.get_weighted_score(&worker);

    // First attestation: age=30, decay = 10000 - 30*100 = 7000
    // contribution = 5 * 1 * 7000 = 35000
    // Second attestation: age=0, decay = 10000
    // contribution = 3 * 1 * 10000 = 30000
    assert_eq!(acc.weighted_sum, 65000);
    assert_eq!(acc.weight_sum, 17000);
}

// ===========================================================================
// Recalculate score test
// ===========================================================================

#[test]
fn recalculate_score_fixes_drift() {
    let (env, contract, _admin, client, worker) = setup_with_admin();

    // Submit attestation at ledger 1000
    set_ledger(&env, 1000);
    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));

    // Without recalculate, accumulator reflects submission-time values
    let acc = contract.get_weighted_score(&worker);
    assert_eq!(acc.weighted_sum, 50000);

    // Advance 2000 ledgers and recalculate
    set_ledger(&env, 3000);
    contract.recalculate_score(&worker);
    let acc = contract.get_weighted_score(&worker);

    // age=2000, decay = 10000 - 2000*1 = 8000
    // weighted_sum = 5 * 1 * 8000 = 40000
    // weight_sum = 1 * 8000 = 8000
    assert_eq!(acc.weighted_sum, 40000);
    assert_eq!(acc.weight_sum, 8000);
    assert_eq!(acc.count, 1);
}

// ===========================================================================
// Legacy compatibility
// ===========================================================================

#[test]
fn zero_stake_legacy_treated_as_one() {
    let (env, contract, client, worker) = setup();

    // Submit without setting stake (stake=0, treated as 1)
    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));

    let acc = contract.get_weighted_score(&worker);
    // effective_stake=1, decay=10000
    // weighted_sum = 5 * 1 * 10000 = 50000
    // weight_sum = 1 * 10000 = 10000
    assert_eq!(acc.weighted_sum, 50000);
    assert_eq!(acc.weight_sum, 10000);
}

// ===========================================================================
// Rate limit window reset
// ===========================================================================

#[test]
fn fresh_window_allows_more_submissions() {
    let (env, contract, client, _worker) = setup();

    // reviewer_cap is 5, fill it
    for i in 1..=5 {
        let w = Address::generate(&env);
        contract.submit_attestation(&(i as u64), &client, &w, &4, &dummy_hash(&env));
    }

    // Should be rate limited
    let w = Address::generate(&env);
    let result = contract.try_submit_attestation(&6, &client, &w, &4, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::ReviewerRateLimited)));

    // Advance to next window (window=100)
    set_ledger(&env, 100);

    // Should work now
    let w = Address::generate(&env);
    let result = contract.try_submit_attestation(&7, &client, &w, &4, &dummy_hash(&env));
    assert!(result.is_ok());
}

// ===========================================================================
// View functions
// ===========================================================================

#[test]
fn get_attestation_returns_correct_data() {
    let (env, contract, client, worker) = setup();

    let hash = BytesN::from_array(&env, &[42u8; 32]);
    contract.submit_attestation(&1, &client, &worker, &3, &hash);

    let att = contract.get_attestation(&worker, &0).unwrap();
    assert_eq!(att.client, client);
    assert_eq!(att.worker, worker);
    assert_eq!(att.appointment_id, 1);
    assert_eq!(att.rating, 3);
    assert_eq!(att.attestation_hash, hash);
}

#[test]
fn get_attestation_count_tracks_correctly() {
    let (env, contract, client, worker) = setup();

    assert_eq!(contract.get_attestation_count(&worker), 0);

    contract.submit_attestation(&1, &client, &worker, &5, &dummy_hash(&env));
    assert_eq!(contract.get_attestation_count(&worker), 1);

    let client2 = Address::generate(&env);
    contract.submit_attestation(&2, &client2, &worker, &4, &dummy_hash(&env));
    assert_eq!(contract.get_attestation_count(&worker), 2);
}

#[test]
fn get_admin_returns_admin() {
    let (_env, contract, admin, _client, _worker) = setup_with_admin();

    assert_eq!(contract.get_admin(), admin);
}

#[test]
fn get_config_returns_config() {
    let (_env, contract, _admin, _client, _worker) = setup_with_admin();

    let config = contract.get_config();
    assert_eq!(config.window, 100);
    assert_eq!(config.reviewer_cap, 5);
    assert_eq!(config.global_cap, 20);
}

// ===========================================================================
// Upgrade governance
//
// These stop one approval short of the configured threshold everywhere,
// deliberately. Crossing it calls env.deployer().update_current_contract_wasm,
// which requires the hash to correspond to Wasm actually uploaded on the
// ledger — there's no such artifact available inside a plain `cargo test`
// run. What's tested here is everything up to that point: signer checks,
// threshold math, replay/expiry protection, and the migration version gate,
// all exercised through the real contract entrypoints rather than by calling
// guildworkman-governance-guard directly (that crate's own 20 tests already
// cover the underlying logic in isolation).
// ===========================================================================

#[test]
fn initialize_stores_governance_config() {
    let (_env, contract, _admin, signers) = setup_with_governance();

    assert_eq!(contract.get_signers(), signers);
    assert_eq!(contract.get_upgrade_threshold(), 2);
    assert_eq!(contract.get_storage_version(), 1);
}

#[test]
fn propose_upgrade_by_non_signer_fails() {
    let (env, contract, _admin, _signers) = setup_with_governance();
    let outsider = Address::generate(&env);

    let result = contract.try_propose_upgrade(&outsider, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

#[test]
fn propose_upgrade_below_threshold_is_not_ready() {
    let (env, contract, _admin, signers) = setup_with_governance();

    let ready = contract.propose_upgrade(&signers.get_unchecked(0), &dummy_hash(&env));
    assert!(!ready);

    let pending = contract.get_pending_upgrade().unwrap();
    assert_eq!(pending.wasm_hash, dummy_hash(&env));
    assert_eq!(pending.approvals.len(), 1);
}

#[test]
fn approve_upgrade_by_non_signer_fails() {
    let (env, contract, _admin, signers) = setup_with_governance();
    contract.propose_upgrade(&signers.get_unchecked(0), &dummy_hash(&env));

    let outsider = Address::generate(&env);
    let result = contract.try_approve_upgrade(&outsider, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

#[test]
fn approve_upgrade_with_wrong_hash_fails() {
    let (env, contract, _admin, signers) = setup_with_governance();
    contract.propose_upgrade(&signers.get_unchecked(0), &dummy_hash(&env));

    let result = contract.try_approve_upgrade(&signers.get_unchecked(1), &other_hash(&env));
    assert_eq!(result, Err(Ok(Error::HashMismatch)));
}

#[test]
fn same_signer_approving_twice_fails() {
    let (env, contract, _admin, signers) = setup_with_governance();
    contract.propose_upgrade(&signers.get_unchecked(0), &dummy_hash(&env));

    let result = contract.try_approve_upgrade(&signers.get_unchecked(0), &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::AlreadyApproved)));
}

#[test]
fn cancel_upgrade_clears_the_pending_proposal() {
    let (env, contract, _admin, signers) = setup_with_governance();
    contract.propose_upgrade(&signers.get_unchecked(0), &dummy_hash(&env));
    assert!(contract.get_pending_upgrade().is_some());

    // The third signer, who never approved, can still cancel.
    contract.cancel_upgrade(&signers.get_unchecked(2));
    assert!(contract.get_pending_upgrade().is_none());
}

#[test]
fn cancel_upgrade_by_non_signer_fails() {
    let (env, contract, _admin, signers) = setup_with_governance();
    contract.propose_upgrade(&signers.get_unchecked(0), &dummy_hash(&env));

    let outsider = Address::generate(&env);
    let result = contract.try_cancel_upgrade(&outsider);
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

#[test]
fn migrate_by_non_signer_fails() {
    let (env, contract, _admin, _signers) = setup_with_governance();
    let outsider = Address::generate(&env);

    let result = contract.try_migrate(&outsider);
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

#[test]
fn migrate_with_nothing_to_migrate_fails() {
    let (_env, contract, _admin, signers) = setup_with_governance();

    // Fresh init already sits at CURRENT_STORAGE_VERSION — there's no v2
    // shipped yet, so there's nothing for a signer to migrate.
    let result = contract.try_migrate(&signers.get_unchecked(0));
    assert_eq!(result, Err(Ok(Error::NothingToMigrate)));
}
