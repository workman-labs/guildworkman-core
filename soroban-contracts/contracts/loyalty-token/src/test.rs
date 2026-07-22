#![cfg(test)]

use super::*;
use soroban_sdk::testutils::Address as _;
use soroban_sdk::Env;

fn setup() -> (Env, LoyaltyTokenClient<'static>, Address, Address, Address) {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let minter = Address::generate(&env);
    let user = Address::generate(&env);

    let contract_id = env.register(LoyaltyToken, ());
    let contract = LoyaltyTokenClient::new(&env, &contract_id);

    let mut signers = soroban_sdk::Vec::new(&env);
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));

    contract.initialize(
        &admin,
        &minter,
        &2,
        &String::from_str(&env, "GuildWorkman Points"),
        &String::from_str(&env, "GWP"),
        &governance::GovernanceInit {
            signers,
            threshold: 2,
        },
    );

    (env, contract, admin, minter, user)
}

fn setup_with_signers() -> (Env, LoyaltyTokenClient<'static>, soroban_sdk::Vec<Address>) {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let minter = Address::generate(&env);

    let contract_id = env.register(LoyaltyToken, ());
    let contract = LoyaltyTokenClient::new(&env, &contract_id);

    let mut signers = soroban_sdk::Vec::new(&env);
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));

    contract.initialize(
        &admin,
        &minter,
        &2,
        &String::from_str(&env, "GuildWorkman Points"),
        &String::from_str(&env, "GWP"),
        &governance::GovernanceInit {
            signers: signers.clone(),
            threshold: 2,
        },
    );

    (env, contract, signers)
}

fn dummy_hash(env: &Env) -> soroban_sdk::BytesN<32> {
    soroban_sdk::BytesN::from_array(env, &[0u8; 32])
}

#[test]
fn mint_and_balance() {
    let (_env, contract, _admin, _minter, user) = setup();
    contract.mint(&user, &1_000);
    assert_eq!(contract.balance(&user), 1_000);
}

#[test]
fn transfer_moves_balance() {
    let (env, contract, _admin, _minter, user) = setup();
    let other = Address::generate(&env);

    contract.mint(&user, &500);
    contract.transfer(&user, &other, &200);

    assert_eq!(contract.balance(&user), 300);
    assert_eq!(contract.balance(&other), 200);
}

#[test]
fn transfer_more_than_balance_fails() {
    let (env, contract, _admin, _minter, user) = setup();
    let other = Address::generate(&env);
    contract.mint(&user, &100);

    let result = contract.try_transfer(&user, &other, &200);
    assert_eq!(result, Err(Ok(Error::InsufficientBalance)));
}

#[test]
fn approve_and_transfer_from() {
    let (env, contract, _admin, _minter, user) = setup();
    let spender = Address::generate(&env);
    let recipient = Address::generate(&env);

    contract.mint(&user, &1_000);
    contract.approve(&user, &spender, &300, &(env.ledger().sequence() + 1_000));
    assert_eq!(contract.allowance(&user, &spender), 300);

    contract.transfer_from(&spender, &user, &recipient, &300);
    assert_eq!(contract.balance(&recipient), 300);
    assert_eq!(contract.balance(&user), 700);
    assert_eq!(contract.allowance(&user, &spender), 0);
}

#[test]
fn burn_reduces_balance() {
    let (_env, contract, _admin, _minter, user) = setup();
    contract.mint(&user, &400);
    contract.burn(&user, &150);
    assert_eq!(contract.balance(&user), 250);
}

#[test]
fn admin_can_rotate_minter() {
    let (env, contract, _admin, _minter, user) = setup();
    let new_minter = Address::generate(&env);

    contract.set_minter(&new_minter);
    contract.mint(&user, &50);
    assert_eq!(contract.balance(&user), 50);
}

// ===========================================================================
// Upgrade governance — see the equivalent block in reputation/src/test.rs
// for why these all stop one approval short of the configured threshold.
// ===========================================================================

#[test]
fn initialize_stores_governance_config() {
    let (_env, contract, signers) = setup_with_signers();
    assert_eq!(contract.get_signers(), signers);
    assert_eq!(contract.get_upgrade_threshold(), 2);
    assert_eq!(contract.get_storage_version(), 1);
}

#[test]
fn propose_upgrade_by_non_signer_fails() {
    let (env, contract, _signers) = setup_with_signers();
    let outsider = Address::generate(&env);
    let result = contract.try_propose_upgrade(&outsider, &dummy_hash(&env));
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

#[test]
fn approve_upgrade_below_threshold_is_not_ready() {
    let (env, contract, signers) = setup_with_signers();
    let target = dummy_hash(&env);

    let ready = contract.propose_upgrade(&signers.get_unchecked(0), &target);
    assert!(!ready);

    let pending = contract.get_pending_upgrade().unwrap();
    assert_eq!(pending.approvals.len(), 1);
}

#[test]
fn cancel_upgrade_by_a_non_approving_signer_still_works() {
    let (env, contract, signers) = setup_with_signers();
    contract.propose_upgrade(&signers.get_unchecked(0), &dummy_hash(&env));

    contract.cancel_upgrade(&signers.get_unchecked(2));
    assert!(contract.get_pending_upgrade().is_none());
}

#[test]
fn migrate_with_nothing_to_migrate_fails() {
    let (_env, contract, signers) = setup_with_signers();
    let result = contract.try_migrate(&signers.get_unchecked(0));
    assert_eq!(result, Err(Ok(Error::NothingToMigrate)));
}

#[test]
fn migrate_by_non_signer_fails() {
    let (env, contract, _signers) = setup_with_signers();
    let outsider = Address::generate(&env);
    let result = contract.try_migrate(&outsider);
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}
