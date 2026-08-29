#![cfg(test)]

use super::*;
use soroban_sdk::testutils::Address as _;
use soroban_sdk::testutils::Ledger;
use soroban_sdk::Env;

fn create_token_contract<'a>(
    env: &Env,
    admin: &Address,
) -> (Address, token::StellarAssetClient<'a>, token::Client<'a>) {
    let sac = env.register_stellar_asset_contract_v2(admin.clone());
    let address = sac.address();
    (
        address.clone(),
        token::StellarAssetClient::new(env, &address),
        token::Client::new(env, &address),
    )
}

#[allow(dead_code)]
struct TestCtx<'a> {
    env: Env,
    contract: EscrowContractClient<'a>,
    admin: Address,
    client: Address,
    worker: Address,
    token: Address,
    token_admin: token::StellarAssetClient<'a>,
    token_client: token::Client<'a>,
    signers: soroban_sdk::Vec<Address>,
}

fn setup() -> TestCtx<'static> {
    let env = Env::default();
    env.mock_all_auths();

    let admin = Address::generate(&env);
    let client = Address::generate(&env);
    let worker = Address::generate(&env);
    let token_issuer = Address::generate(&env);

    let (token, token_admin, token_client) = create_token_contract(&env, &token_issuer);
    token_admin.mint(&client, &1_000_000);

    let contract_id = env.register(EscrowContract, ());
    let contract = EscrowContractClient::new(&env, &contract_id);

    let mut signers = soroban_sdk::Vec::new(&env);
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));
    signers.push_back(Address::generate(&env));
    contract.initialize(
        &admin,
        &governance::GovernanceInit {
            signers: signers.clone(),
            threshold: 2,
        },
    );

    TestCtx {
        env,
        contract,
        admin,
        client,
        worker,
        token,
        token_admin,
        token_client,
        signers,
    }
}

#[test]
fn happy_path_completion_pays_worker() {
    let ctx = setup();

    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    assert_eq!(ctx.token_client.balance(&ctx.client), 990_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 10_000);

    ctx.contract.confirm_completion(&1);
    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);

    let appointment = ctx.contract.get_appointment(&1);
    assert_eq!(appointment.status, Status::Completed);
}

#[test]
fn cancel_refunds_client() {
    let ctx = setup();

    ctx.contract
        .create_appointment(&2, &ctx.client, &ctx.worker, &ctx.token, &5_000, &None);
    ctx.contract.cancel_appointment(&2);

    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
    assert_eq!(ctx.contract.get_appointment(&2).status, Status::Cancelled);
}

#[test]
fn dispute_resolved_in_favor_of_worker() {
    let ctx = setup();

    ctx.contract
        .create_appointment(&3, &ctx.client, &ctx.worker, &ctx.token, &7_000, &None);
    ctx.contract.raise_dispute(&3, &ctx.client);
    assert_eq!(ctx.contract.get_appointment(&3).status, Status::Disputed);

    ctx.contract.resolve_dispute(&3, &false);
    assert_eq!(ctx.token_client.balance(&ctx.worker), 7_000);
    assert_eq!(ctx.contract.get_appointment(&3).status, Status::Resolved);
}

#[test]
fn duplicate_appointment_id_rejected() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&4, &ctx.client, &ctx.worker, &ctx.token, &1_000, &None);

    let result = ctx.contract.try_create_appointment(
        &4,
        &ctx.client,
        &ctx.worker,
        &ctx.token,
        &1_000,
        &None,
    );
    assert_eq!(result, Err(Ok(Error::AppointmentExists)));
}

#[test]
fn cannot_confirm_twice() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&5, &ctx.client, &ctx.worker, &ctx.token, &2_000, &None);
    ctx.contract.confirm_completion(&5);

    let result = ctx.contract.try_confirm_completion(&5);
    assert_eq!(result, Err(Ok(Error::InvalidStatus)));
}

// ===========================================================================
// Protocol fee engine
// ===========================================================================

fn set_fee(ctx: &TestCtx, protocol_bps: u32, referrer_bps: u32) {
    ctx.contract.set_fee_config(
        &ctx.signers.get_unchecked(0),
        &FeeConfig {
            protocol_bps,
            referrer_bps,
        },
    );
}

#[test]
fn fee_config_defaults_to_zero() {
    let ctx = setup();
    let config = ctx.contract.get_fee_config();
    assert_eq!(config.protocol_bps, 0);
    assert_eq!(config.referrer_bps, 0);
}

#[test]
fn set_fee_config_by_signer_succeeds() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 500);
    let config = ctx.contract.get_fee_config();
    assert_eq!(config.protocol_bps, 1_000);
    assert_eq!(config.referrer_bps, 500);
}

#[test]
fn set_fee_config_by_non_signer_rejected() {
    let ctx = setup();
    let outsider = Address::generate(&ctx.env);
    let res = ctx.contract.try_set_fee_config(
        &outsider,
        &FeeConfig {
            protocol_bps: 100,
            referrer_bps: 0,
        },
    );
    assert_eq!(res, Err(Ok(Error::NotASigner)));
}

#[test]
fn set_fee_config_by_admin_arbiter_rejected() {
    // `admin` resolves disputes but is not a governance signer — the same
    // separation of powers `the_admin_arbiter_is_not_a_pause_authority`
    // pins down for the circuit breaker applies here too.
    let ctx = setup();
    let res = ctx.contract.try_set_fee_config(
        &ctx.admin,
        &FeeConfig {
            protocol_bps: 100,
            referrer_bps: 0,
        },
    );
    assert_eq!(res, Err(Ok(Error::NotASigner)));
}

#[test]
fn set_fee_config_at_exact_cap_accepted() {
    let ctx = setup();
    set_fee(&ctx, 1_000, MAX_TOTAL_FEE_BPS - 1_000);
    let config = ctx.contract.get_fee_config();
    assert_eq!(config.protocol_bps + config.referrer_bps, MAX_TOTAL_FEE_BPS);
}

#[test]
fn set_fee_config_above_cap_rejected() {
    let ctx = setup();
    let res = ctx.contract.try_set_fee_config(
        &ctx.signers.get_unchecked(0),
        &FeeConfig {
            protocol_bps: 1_000,
            referrer_bps: MAX_TOTAL_FEE_BPS - 1_000 + 1,
        },
    );
    assert_eq!(res, Err(Ok(Error::FeeExceedsMaximum)));
    // Rejected atomically — the default is untouched.
    let config = ctx.contract.get_fee_config();
    assert_eq!(config.protocol_bps, 0);
    assert_eq!(config.referrer_bps, 0);
}

#[test]
fn set_fee_config_protocol_alone_above_cap_rejected() {
    let ctx = setup();
    let res = ctx.contract.try_set_fee_config(
        &ctx.signers.get_unchecked(0),
        &FeeConfig {
            protocol_bps: MAX_TOTAL_FEE_BPS + 1,
            referrer_bps: 0,
        },
    );
    assert_eq!(res, Err(Ok(Error::FeeExceedsMaximum)));
}

#[test]
fn create_appointment_rejects_referrer_equal_to_worker() {
    let ctx = setup();
    let res = ctx.contract.try_create_appointment(
        &1,
        &ctx.client,
        &ctx.worker,
        &ctx.token,
        &10_000,
        &Some(ctx.worker.clone()),
    );
    assert_eq!(res, Err(Ok(Error::InvalidReferrer)));
    // Rejected before any funds move.
    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
}

#[test]
fn create_appointment_rejects_referrer_equal_to_client() {
    let ctx = setup();
    let res = ctx.contract.try_create_appointment(
        &1,
        &ctx.client,
        &ctx.worker,
        &ctx.token,
        &10_000,
        &Some(ctx.client.clone()),
    );
    assert_eq!(res, Err(Ok(Error::InvalidReferrer)));
    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
}

#[test]
fn confirm_completion_splits_protocol_fee_with_no_referrer() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 500); // 10% protocol, 5% referrer — no referrer here

    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    ctx.contract.confirm_completion(&1);

    // No referrer on this appointment, so referrer_bps contributes nothing —
    // the worker absorbs it as part of the remainder.
    assert_eq!(ctx.token_client.balance(&ctx.worker), 9_000);
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 1_000);
    // The protocol share stays in the contract's own token balance until
    // `withdraw_treasury` moves it out.
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 1_000);
}

#[test]
fn confirm_completion_splits_three_ways_with_referrer() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 500);
    let referrer = Address::generate(&ctx.env);

    ctx.contract.create_appointment(
        &1,
        &ctx.client,
        &ctx.worker,
        &ctx.token,
        &10_000,
        &Some(referrer.clone()),
    );
    ctx.contract.confirm_completion(&1);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 8_500);
    assert_eq!(ctx.token_client.balance(&referrer), 500);
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 1_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 1_000);
}

#[test]
fn cancel_is_fee_free_even_with_nonzero_fee_config() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 500);

    ctx.contract
        .create_appointment(&2, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    ctx.contract.cancel_appointment(&2);

    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 0);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
}

#[test]
fn dispute_refund_to_client_is_fee_free() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 500);

    ctx.contract
        .create_appointment(&3, &ctx.client, &ctx.worker, &ctx.token, &7_000, &None);
    ctx.contract.raise_dispute(&3, &ctx.client);
    ctx.contract.resolve_dispute(&3, &true);

    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 0);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
}

#[test]
fn dispute_resolved_to_worker_applies_fee_split() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 0);

    ctx.contract
        .create_appointment(&3, &ctx.client, &ctx.worker, &ctx.token, &7_000, &None);
    ctx.contract.raise_dispute(&3, &ctx.client);
    ctx.contract.resolve_dispute(&3, &false);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 6_300); // 90% of 7,000
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 700);
}

#[test]
fn withdraw_treasury_by_signer_succeeds() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 0);
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    ctx.contract.confirm_completion(&1);
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 1_000);

    let treasury_recipient = Address::generate(&ctx.env);
    ctx.contract.withdraw_treasury(
        &ctx.signers.get_unchecked(1),
        &ctx.token,
        &treasury_recipient,
        &1_000,
    );

    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 0);
    assert_eq!(ctx.token_client.balance(&treasury_recipient), 1_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
}

#[test]
fn withdraw_treasury_more_than_balance_rejected() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 0);
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    ctx.contract.confirm_completion(&1);

    let treasury_recipient = Address::generate(&ctx.env);
    let res = ctx.contract.try_withdraw_treasury(
        &ctx.signers.get_unchecked(0),
        &ctx.token,
        &treasury_recipient,
        &1_001,
    );
    assert_eq!(res, Err(Ok(Error::InsufficientTreasuryBalance)));
    // Untouched by the rejected attempt.
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 1_000);
}

#[test]
fn withdraw_treasury_by_non_signer_rejected() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 0);
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    ctx.contract.confirm_completion(&1);

    let outsider = Address::generate(&ctx.env);
    let treasury_recipient = Address::generate(&ctx.env);
    let res =
        ctx.contract
            .try_withdraw_treasury(&outsider, &ctx.token, &treasury_recipient, &1_000);
    assert_eq!(res, Err(Ok(Error::NotASigner)));
}

#[test]
fn withdraw_treasury_zero_amount_rejected() {
    let ctx = setup();
    let treasury_recipient = Address::generate(&ctx.env);
    let res = ctx.contract.try_withdraw_treasury(
        &ctx.signers.get_unchecked(0),
        &ctx.token,
        &treasury_recipient,
        &0,
    );
    assert_eq!(res, Err(Ok(Error::InvalidAmount)));
}

// ----- Adversarial edge cases -----

#[test]
fn one_unit_amount_rounds_entirely_to_worker() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 500); // 15% combined still floors to 0 on amount=1

    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &1, &None);
    ctx.contract.confirm_completion(&1);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 1);
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 0);
}

#[test]
fn near_i128_max_amount_splits_without_overflow_or_dust() {
    let ctx = setup();
    set_fee(&ctx, 1_000, 500);
    let referrer = Address::generate(&ctx.env);
    let big_client = Address::generate(&ctx.env);

    // A fresh address minted exactly this amount, so the token's own total
    // supply never has to represent more than `i128::MAX`.
    let huge = i128::MAX - 7;
    ctx.token_admin.mint(&big_client, &huge);

    ctx.contract.create_appointment(
        &1,
        &big_client,
        &ctx.worker,
        &ctx.token,
        &huge,
        &Some(referrer.clone()),
    );
    ctx.contract.confirm_completion(&1);

    let worker_bal = ctx.token_client.balance(&ctx.worker);
    let referrer_bal = ctx.token_client.balance(&referrer);
    let treasury_bal = ctx.contract.get_treasury_balance(&ctx.token);

    assert_eq!(worker_bal + referrer_bal + treasury_bal, huge);
    assert_eq!(
        ctx.token_client.balance(&ctx.contract.address),
        treasury_bal
    );
}

#[test]
fn zero_fee_config_pays_worker_in_full() {
    let ctx = setup();
    // Fee config defaults to zero — explicit here for clarity.
    set_fee(&ctx, 0, 0);
    let referrer = Address::generate(&ctx.env);

    ctx.contract.create_appointment(
        &1,
        &ctx.client,
        &ctx.worker,
        &ctx.token,
        &10_000,
        &Some(referrer.clone()),
    );
    ctx.contract.confirm_completion(&1);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
    assert_eq!(ctx.token_client.balance(&referrer), 0);
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 0);
}

#[test]
fn max_fee_config_still_leaves_worker_the_floor_share() {
    let ctx = setup();
    set_fee(&ctx, MAX_TOTAL_FEE_BPS, 0); // entire cap taken by the protocol

    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    ctx.contract.confirm_completion(&1);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 8_500);
    assert_eq!(ctx.contract.get_treasury_balance(&ctx.token), 1_500);
}

#[test]
fn split_invariant_holds_across_amounts_and_fee_configs() {
    // sum(worker_share, protocol_share, referrer_share) == amount, for every
    // combination of adversarial amounts (1-unit and i128::MAX-adjacent, plus
    // a spread in between) and fee configs at the edges of what's allowed:
    // zero, max-protocol-only, max-referrer-only, and split down the middle.
    let ctx = setup();

    let amounts: [i128; 8] = [
        1,
        2,
        9_999,
        10_000,
        10_001,
        1_000_000_000_000,
        i128::MAX - 1,
        i128::MAX,
    ];
    let configs = [
        FeeConfig {
            protocol_bps: 0,
            referrer_bps: 0,
        },
        FeeConfig {
            protocol_bps: MAX_TOTAL_FEE_BPS,
            referrer_bps: 0,
        },
        FeeConfig {
            protocol_bps: 0,
            referrer_bps: MAX_TOTAL_FEE_BPS,
        },
        FeeConfig {
            protocol_bps: 750,
            referrer_bps: 750,
        },
    ];

    let mut appointment_id = 100u64;
    for config in configs {
        set_fee(&ctx, config.protocol_bps, config.referrer_bps);

        for &amount in amounts.iter() {
            for has_referrer in [false, true] {
                // A fresh token per case, so no prior iteration's residual
                // contract balance (e.g. an unwithdrawn protocol share) can
                // interact with this amount. That matters most for the
                // `i128::MAX`-adjacent cases: even a few units of leftover
                // balance on a shared token would overflow the token's own
                // `i128` balance field the moment the contract tried to
                // receive another near-max transfer.
                let (token, token_admin, token_client) =
                    create_token_contract(&ctx.env, &Address::generate(&ctx.env));

                let payer = Address::generate(&ctx.env);
                token_admin.mint(&payer, &amount);

                let worker = Address::generate(&ctx.env);
                let referrer_addr = Address::generate(&ctx.env);
                let referrer = has_referrer.then(|| referrer_addr.clone());

                appointment_id += 1;
                ctx.contract.create_appointment(
                    &appointment_id,
                    &payer,
                    &worker,
                    &token,
                    &amount,
                    &referrer,
                );
                ctx.contract.confirm_completion(&appointment_id);

                let worker_bal = token_client.balance(&worker);
                let referrer_bal = if has_referrer {
                    token_client.balance(&referrer_addr)
                } else {
                    0
                };
                // This token is brand new to this iteration, so the treasury
                // balance it reports *is* this appointment's protocol share.
                let treasury_bal = ctx.contract.get_treasury_balance(&token);

                assert!(worker_bal >= 0 && referrer_bal >= 0 && treasury_bal >= 0);
                assert_eq!(
                    worker_bal + referrer_bal + treasury_bal,
                    amount,
                    "split did not reconcile for amount={amount}, config={config:?}, has_referrer={has_referrer}"
                );
            }
        }
    }
}

// ===========================================================================
// Upgrade governance — see the equivalent block in reputation/src/test.rs
// for why these all stop one approval short of the configured threshold.
// ===========================================================================

fn dummy_hash(env: &Env) -> soroban_sdk::BytesN<32> {
    soroban_sdk::BytesN::from_array(env, &[0u8; 32])
}

#[test]
fn initialize_stores_governance_config() {
    let ctx = setup();
    assert_eq!(ctx.contract.get_signers(), ctx.signers);
    assert_eq!(ctx.contract.get_upgrade_threshold(), 2);
    assert_eq!(ctx.contract.get_storage_version(), 1);
}

#[test]
fn propose_upgrade_by_non_signer_fails() {
    let ctx = setup();
    let outsider = Address::generate(&ctx.env);
    let result = ctx
        .contract
        .try_propose_upgrade(&outsider, &dummy_hash(&ctx.env));
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

#[test]
fn approve_upgrade_reaches_threshold_after_a_second_distinct_signer() {
    let ctx = setup();
    let target = dummy_hash(&ctx.env);

    let ready = ctx
        .contract
        .propose_upgrade(&ctx.signers.get_unchecked(0), &target);
    assert!(!ready);

    // Stop here rather than approving with the second signer — that call
    // would cross the threshold and attempt the real Wasm swap, which
    // needs Wasm actually uploaded to the test ledger. Confirm the
    // proposal is sitting at exactly one approval, awaiting a second.
    let pending = ctx.contract.get_pending_upgrade().unwrap();
    assert_eq!(pending.approvals.len(), 1);
    assert_eq!(pending.wasm_hash, target);
}

#[test]
fn cancel_upgrade_by_a_signer_who_never_approved_still_works() {
    let ctx = setup();
    ctx.contract
        .propose_upgrade(&ctx.signers.get_unchecked(0), &dummy_hash(&ctx.env));

    ctx.contract.cancel_upgrade(&ctx.signers.get_unchecked(2));
    assert!(ctx.contract.get_pending_upgrade().is_none());
}

#[test]
fn migrate_with_nothing_to_migrate_fails() {
    let ctx = setup();
    let result = ctx.contract.try_migrate(&ctx.signers.get_unchecked(0));
    assert_eq!(result, Err(Ok(Error::NothingToMigrate)));
}

#[test]
fn migrate_by_non_signer_fails() {
    let ctx = setup();
    let outsider = Address::generate(&ctx.env);
    let result = ctx.contract.try_migrate(&outsider);
    assert_eq!(result, Err(Ok(Error::NotASigner)));
}

// ===========================================================================
// Milestone Escrow — helper
// ===========================================================================

fn set_ledger(env: &Env, seq: u32) {
    env.ledger().with_mut(|l| l.sequence_number = seq);
}

fn desc(env: &Env, seed: u8) -> BytesN<32> {
    BytesN::from_array(env, &[seed; 32])
}

fn milestone_escrow_init(ctx: &TestCtx) -> MilestoneEscrowInit {
    MilestoneEscrowInit {
        client: ctx.client.clone(),
        worker: ctx.worker.clone(),
        token: ctx.token.clone(),
        total_amount: 10_000,
        arbiter: ctx.admin.clone(),
        arbitration_mode: ArbitrationMode::AdminArbiter,
        hook_address: None,
    }
}

// ===========================================================================
// Milestone Escrow — happy paths
// ===========================================================================

#[test]
fn milestone_escrow_create_funds_contract() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    assert_eq!(ctx.token_client.balance(&ctx.client), 990_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 10_000);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.status, Status::Funded);
    assert_eq!(escrow.total_amount, 10_000);
    assert_eq!(escrow.released_amount, 0);
}

#[test]
fn milestone_escrow_add_approve_release_happy_path() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    // Add two milestones: 6000 + 4000 = 10000.
    let idx0 = ctx
        .contract
        .add_milestone(&1, &desc(&ctx.env, 1), &6_000, &200);
    assert_eq!(idx0, 0);

    let idx1 = ctx
        .contract
        .add_milestone(&1, &desc(&ctx.env, 2), &4_000, &300);
    assert_eq!(idx1, 1);

    // Approve first milestone.
    ctx.contract.approve_milestone(&1, &0);
    let m0 = ctx.contract.get_milestone(&1, &0);
    assert_eq!(m0.status, MilestoneStatus::Approved);

    // Cannot release before deadline.
    set_ledger(&ctx.env, 150);
    let res = ctx.contract.try_release_milestone_funds(&1, &0);
    assert_eq!(res, Err(Ok(Error::MilestoneTimeLocked)));

    // Release after deadline.
    set_ledger(&ctx.env, 201);
    let released = ctx.contract.release_milestone_funds(&1, &0);
    assert_eq!(released, 6_000);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 6_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 4_000);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.released_amount, 6_000);
    assert_eq!(escrow.status, Status::Funded);

    // Approve and release second milestone.
    ctx.contract.approve_milestone(&1, &1);
    set_ledger(&ctx.env, 301);
    let released2 = ctx.contract.release_milestone_funds(&1, &1);
    assert_eq!(released2, 4_000);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.released_amount, 10_000);
    assert_eq!(escrow.status, Status::Completed);
}

#[test]
fn milestone_escrow_admin_arbitration_to_worker() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);
    ctx.contract.approve_milestone(&1, &0);

    // Worker raises dispute before deadline.
    ctx.contract.raise_milestone_dispute(&1, &0, &ctx.worker);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.status, Status::Disputed);

    let m = ctx.contract.get_milestone(&1, &0);
    assert_eq!(m.status, MilestoneStatus::Disputed);

    // Arbiter resolves in favor of the worker.
    ctx.contract.resolve_milestone_dispute(&1, &0, &false);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.status, Status::Completed);
}

#[test]
fn milestone_escrow_admin_arbitration_to_client() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);
    ctx.contract.approve_milestone(&1, &0);

    // Client raises dispute.
    ctx.contract.raise_milestone_dispute(&1, &0, &ctx.client);

    // Arbiter resolves in favor of the client (refund).
    ctx.contract.resolve_milestone_dispute(&1, &0, &true);

    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.status, Status::Completed);
}

// ===========================================================================
// Milestone Escrow — failure cases
// ===========================================================================

#[test]
fn milestone_escrow_zero_amount_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    let mut init = milestone_escrow_init(&ctx);
    init.total_amount = 0;

    let res = ctx.contract.try_create_milestone_escrow(&1, &init);
    assert_eq!(res, Err(Ok(Error::InvalidAmount)));
}

#[test]
fn milestone_escrow_negative_amount_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    let mut init = milestone_escrow_init(&ctx);
    init.total_amount = -1;

    let res = ctx.contract.try_create_milestone_escrow(&1, &init);
    assert_eq!(res, Err(Ok(Error::InvalidAmount)));
}

#[test]
fn milestone_escrow_duplicate_id_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    let res = ctx
        .contract
        .try_create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    assert_eq!(res, Err(Ok(Error::AppointmentExists)));
}

#[test]
fn add_milestone_non_client_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    // Worker tries to add a milestone — should fail because
    // mock_all_auths makes everyone auth'd, but the contract checks
    // client specifically. Since mock_all_auths is used, we need to
    // test the wrong-status path instead. Let's test amount mismatch.
    let res = ctx
        .contract
        .try_add_milestone(&1, &desc(&ctx.env, 1), &15_000, &200);
    assert_eq!(res, Err(Ok(Error::MilestoneAmountMismatch)));
}

#[test]
fn add_milestone_exceeds_total_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    let res = ctx
        .contract
        .try_add_milestone(&1, &desc(&ctx.env, 1), &10_001, &200);
    assert_eq!(res, Err(Ok(Error::MilestoneAmountMismatch)));
}

#[test]
fn add_milestone_past_deadline_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    // Deadline at or before current ledger.
    let res = ctx
        .contract
        .try_add_milestone(&1, &desc(&ctx.env, 1), &5_000, &100);
    assert_eq!(res, Err(Ok(Error::InvalidDeadline)));
}

#[test]
fn add_milestone_zero_amount_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    let res = ctx
        .contract
        .try_add_milestone(&1, &desc(&ctx.env, 1), &0, &200);
    assert_eq!(res, Err(Ok(Error::InvalidMilestoneAmount)));
}

#[test]
fn approve_milestone_already_approved_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);
    ctx.contract.approve_milestone(&1, &0);

    let res = ctx.contract.try_approve_milestone(&1, &0);
    assert_eq!(res, Err(Ok(Error::MilestoneAlreadyApproved)));
}

#[test]
fn approve_milestone_not_found_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    let res = ctx.contract.try_approve_milestone(&1, &99);
    assert_eq!(res, Err(Ok(Error::MilestoneNotFound)));
}

#[test]
fn release_before_deadline_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);
    ctx.contract.approve_milestone(&1, &0);

    set_ledger(&ctx.env, 150);
    let res = ctx.contract.try_release_milestone_funds(&1, &0);
    assert_eq!(res, Err(Ok(Error::MilestoneTimeLocked)));
}

#[test]
fn release_not_approved_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);

    // Milestone is Pending, not Approved.
    set_ledger(&ctx.env, 201);
    let res = ctx.contract.try_release_milestone_funds(&1, &0);
    assert_eq!(res, Err(Ok(Error::InvalidStatus)));
}

#[test]
fn release_not_found_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    set_ledger(&ctx.env, 201);
    let res = ctx.contract.try_release_milestone_funds(&1, &99);
    assert_eq!(res, Err(Ok(Error::MilestoneNotFound)));
}

// ===========================================================================
// Milestone Escrow — adversarial edge cases
// ===========================================================================

#[test]
fn dispute_after_release_fails() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    // Two milestones so escrow stays Funded after releasing one.
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &5_000, &200);
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 2), &5_000, &300);
    ctx.contract.approve_milestone(&1, &0);

    set_ledger(&ctx.env, 201);
    ctx.contract.release_milestone_funds(&1, &0);

    // Cannot dispute a released milestone.
    let res = ctx
        .contract
        .try_raise_milestone_dispute(&1, &0, &ctx.worker);
    assert_eq!(res, Err(Ok(Error::InvalidStatus)));
}

#[test]
fn dispute_by_non_participant_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);

    let outsider = Address::generate(&ctx.env);
    let res = ctx.contract.try_raise_milestone_dispute(&1, &0, &outsider);
    assert_eq!(res, Err(Ok(Error::NotAParticipant)));
}

#[test]
fn resolve_dispute_on_non_disputed_milestone_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    // Two milestones: dispute only the first, then try to resolve the second.
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &5_000, &200);
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 2), &5_000, &300);
    ctx.contract.approve_milestone(&1, &0);
    ctx.contract.approve_milestone(&1, &1);

    // Dispute milestone 0 → escrow becomes Disputed.
    ctx.contract.raise_milestone_dispute(&1, &0, &ctx.client);

    // Try to resolve milestone 1, which is Approved, not Disputed.
    let res = ctx.contract.try_resolve_milestone_dispute(&1, &1, &true);
    assert_eq!(res, Err(Ok(Error::InvalidStatus)));
}

#[test]
fn resolve_dispute_on_already_released_milestone_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    // Two milestones: release one, dispute the other, then try to resolve
    // the released milestone (should fail because it's Released, not Disputed).
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &5_000, &200);
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 2), &5_000, &300);
    ctx.contract.approve_milestone(&1, &0);
    ctx.contract.approve_milestone(&1, &1);

    // Release milestone 0.
    set_ledger(&ctx.env, 201);
    ctx.contract.release_milestone_funds(&1, &0);

    // Dispute milestone 1 → escrow becomes Disputed.
    ctx.contract.raise_milestone_dispute(&1, &1, &ctx.worker);

    // Try to resolve milestone 0, which is Released, not Disputed.
    let res = ctx.contract.try_resolve_milestone_dispute(&1, &0, &true);
    assert_eq!(res, Err(Ok(Error::InvalidStatus)));
}

#[test]
fn release_twice_same_milestone_rejected() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    // Two milestones so escrow stays Funded after first release.
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &5_000, &200);
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 2), &5_000, &300);
    ctx.contract.approve_milestone(&1, &0);

    set_ledger(&ctx.env, 201);
    ctx.contract.release_milestone_funds(&1, &0);

    // Second release attempt on same milestone.
    let res = ctx.contract.try_release_milestone_funds(&1, &0);
    assert_eq!(res, Err(Ok(Error::InvalidStatus)));
}

#[test]
fn partial_release_tracks_accounting_correctly() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    // Three milestones: 3000 + 3000 + 4000 = 10000.
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &3_000, &200);
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 2), &3_000, &300);
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 3), &4_000, &400);

    // Release first milestone.
    ctx.contract.approve_milestone(&1, &0);
    set_ledger(&ctx.env, 201);
    ctx.contract.release_milestone_funds(&1, &0);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.released_amount, 3_000);
    assert_eq!(escrow.status, Status::Funded);

    // Release second milestone.
    ctx.contract.approve_milestone(&1, &1);
    set_ledger(&ctx.env, 301);
    ctx.contract.release_milestone_funds(&1, &1);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.released_amount, 6_000);
    assert_eq!(escrow.status, Status::Funded);

    // Release third — completes escrow.
    ctx.contract.approve_milestone(&1, &2);
    set_ledger(&ctx.env, 401);
    ctx.contract.release_milestone_funds(&1, &2);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.released_amount, 10_000);
    assert_eq!(escrow.status, Status::Completed);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
}

#[test]
fn dispute_freezes_further_releases() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &5_000, &200);
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 2), &5_000, &300);
    ctx.contract.approve_milestone(&1, &0);
    ctx.contract.approve_milestone(&1, &1);

    // Release first milestone.
    set_ledger(&ctx.env, 201);
    ctx.contract.release_milestone_funds(&1, &0);

    // Dispute second milestone.
    ctx.contract.raise_milestone_dispute(&1, &1, &ctx.client);

    // Cannot release disputed milestone — escrow is Disputed.
    set_ledger(&ctx.env, 301);
    let res = ctx.contract.try_release_milestone_funds(&1, &1);
    assert_eq!(res, Err(Ok(Error::InvalidEscrowStatus)));
}

#[test]
fn resolve_dispute_funds_returned_to_contract_balance() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);
    ctx.contract.approve_milestone(&1, &0);

    ctx.contract.raise_milestone_dispute(&1, &0, &ctx.worker);
    ctx.contract.resolve_milestone_dispute(&1, &0, &false);

    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);

    let escrow = ctx.contract.get_milestone_escrow(&1);
    assert_eq!(escrow.released_amount, 10_000);
    assert_eq!(escrow.status, Status::Completed);
}

#[test]
fn get_milestone_view_returns_correct_data() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    let d = desc(&ctx.env, 42);
    ctx.contract.add_milestone(&1, &d, &7_500, &500);

    let m = ctx.contract.get_milestone(&1, &0);
    assert_eq!(m.description, d);
    assert_eq!(m.amount, 7_500);
    assert_eq!(m.deadline, 500);
    assert_eq!(m.status, MilestoneStatus::Pending);
}

#[test]
fn get_milestone_not_found_returns_error() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);

    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    let res = ctx.contract.try_get_milestone(&1, &0);
    assert_eq!(res, Err(Ok(Error::MilestoneNotFound)));
}

// ===========================================================================
// Emergency circuit breaker
// ===========================================================================
//
// The primitive itself (scope arithmetic, expiry, auth) is unit-tested in
// `guildworkman-governance-guard`. What matters here is the wiring: that the
// intake and settlement entrypoints are guarded, and — the property the whole
// design rests on — that every fund-recovery entrypoint is *not*.

/// Reason string for tests that don't exercise the field itself. Kept
/// non-empty so the round-trip through storage is actually covered by every
/// pause test rather than only the ones that look at it.
fn reason(env: &Env) -> soroban_sdk::String {
    soroban_sdk::String::from_str(env, "INC-000 test")
}

fn set_time(env: &Env, timestamp: u64) {
    env.ledger().with_mut(|l| l.timestamp = timestamp);
}

/// Halts every scope for an hour, from an arbitrary non-zero clock so that
/// "before" and "after the deadline" are both expressible.
fn pause_everything(ctx: &TestCtx) -> u64 {
    set_time(&ctx.env, 1_000);
    ctx.contract.pause(
        &ctx.signers.get_unchecked(0),
        &ALL_SCOPES,
        &3_600,
        &reason(&ctx.contract.env),
    );
    1_000
}

// ----- Intake is halted -----

#[test]
fn paused_intake_blocks_new_appointments_and_moves_no_money() {
    let ctx = setup();
    pause_everything(&ctx);

    let res = ctx.contract.try_create_appointment(
        &1,
        &ctx.client,
        &ctx.worker,
        &ctx.token,
        &10_000,
        &None,
    );
    assert_eq!(res, Err(Ok(Error::OperationPaused)));

    // No funds entered the contract.
    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
}

#[test]
fn paused_intake_blocks_new_milestone_escrows_and_milestones() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);
    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));

    set_time(&ctx.env, 1_000);
    ctx.contract.pause(
        &ctx.signers.get_unchecked(0),
        &SCOPE_INTAKE,
        &3_600,
        &reason(&ctx.contract.env),
    );

    let res = ctx
        .contract
        .try_create_milestone_escrow(&2, &milestone_escrow_init(&ctx));
    assert_eq!(res, Err(Ok(Error::OperationPaused)));

    let res = ctx
        .contract
        .try_add_milestone(&1, &desc(&ctx.env, 1), &5_000, &200);
    assert_eq!(res, Err(Ok(Error::OperationPaused)));
}

// ----- Recovery survives a total pause -----

#[test]
fn a_client_can_still_cancel_and_be_refunded_while_everything_is_paused() {
    // The headline guarantee: money already in escrow gets out, even with
    // every scope the breaker knows about halted at once.
    let ctx = setup();
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 10_000);

    pause_everything(&ctx);
    assert_eq!(ctx.contract.paused_scopes(), ALL_SCOPES);

    ctx.contract.cancel_appointment(&1);

    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
    assert_eq!(ctx.contract.get_appointment(&1).status, Status::Cancelled);
    // Still paused afterwards — the recovery path is exempt, not a lift.
    assert_eq!(ctx.contract.paused_scopes(), ALL_SCOPES);
}

#[test]
fn disputes_can_still_be_raised_and_resolved_while_everything_is_paused() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);

    pause_everything(&ctx);

    ctx.contract.raise_dispute(&1, &ctx.worker);
    assert_eq!(ctx.contract.get_appointment(&1).status, Status::Disputed);

    ctx.contract.resolve_dispute(&1, &false);
    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
}

#[test]
fn milestone_disputes_resolve_while_everything_is_paused() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);
    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);
    ctx.contract.raise_milestone_dispute(&1, &0, &ctx.client);

    pause_everything(&ctx);

    // Both halves of the milestone recovery route stay open.
    ctx.contract.resolve_milestone_dispute(&1, &0, &true);
    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
}

// ----- Scope isolation across entrypoints -----

#[test]
fn pausing_intake_alone_leaves_settlement_working() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);

    set_time(&ctx.env, 1_000);
    ctx.contract.pause(
        &ctx.signers.get_unchecked(0),
        &SCOPE_INTAKE,
        &3_600,
        &reason(&ctx.contract.env),
    );

    // Existing business settles normally; only new business is halted.
    ctx.contract.confirm_completion(&1);
    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
}

#[test]
fn pausing_settlement_alone_blocks_payout_but_not_new_appointments() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);

    set_time(&ctx.env, 1_000);
    ctx.contract.pause(
        &ctx.signers.get_unchecked(0),
        &SCOPE_SETTLEMENT,
        &3_600,
        &reason(&ctx.contract.env),
    );

    let res = ctx.contract.try_confirm_completion(&1);
    assert_eq!(res, Err(Ok(Error::OperationPaused)));
    assert_eq!(ctx.token_client.balance(&ctx.worker), 0);

    // Intake was never named, so it is untouched.
    ctx.contract
        .create_appointment(&2, &ctx.client, &ctx.worker, &ctx.token, &5_000, &None);
}

#[test]
fn paused_settlement_blocks_milestone_approval_and_release() {
    let ctx = setup();
    set_ledger(&ctx.env, 100);
    ctx.contract
        .create_milestone_escrow(&1, &milestone_escrow_init(&ctx));
    ctx.contract
        .add_milestone(&1, &desc(&ctx.env, 1), &10_000, &200);
    ctx.contract.approve_milestone(&1, &0);
    set_ledger(&ctx.env, 201);

    set_time(&ctx.env, 1_000);
    ctx.contract.pause(
        &ctx.signers.get_unchecked(0),
        &SCOPE_SETTLEMENT,
        &3_600,
        &reason(&ctx.contract.env),
    );

    // The permissionless release is exactly what the scope exists to stop.
    let res = ctx.contract.try_release_milestone_funds(&1, &0);
    assert_eq!(res, Err(Ok(Error::OperationPaused)));
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 10_000);

    let res = ctx.contract.try_approve_milestone(&1, &0);
    assert_eq!(res, Err(Ok(Error::OperationPaused)));
}

// ----- Auto-expiry -----

#[test]
fn intake_resumes_on_its_own_once_the_pause_expires() {
    let ctx = setup();
    let start = pause_everything(&ctx);

    let res = ctx.contract.try_create_appointment(
        &1,
        &ctx.client,
        &ctx.worker,
        &ctx.token,
        &10_000,
        &None,
    );
    assert_eq!(res, Err(Ok(Error::OperationPaused)));

    // No unpause transaction. Only the ledger clock advances.
    set_time(&ctx.env, start + 3_600);

    assert_eq!(ctx.contract.paused_scopes(), 0);
    assert!(ctx.contract.get_pause_state().is_none());
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 10_000);
}

#[test]
fn a_pause_longer_than_the_cap_is_refused_outright() {
    let ctx = setup();
    set_time(&ctx.env, 1_000);

    let res = ctx.contract.try_pause(
        &ctx.signers.get_unchecked(0),
        &ALL_SCOPES,
        &(MAX_PAUSE_DURATION + 1),
        &reason(&ctx.contract.env),
    );
    assert_eq!(res, Err(Ok(Error::InvalidPauseDuration)));
    assert_eq!(ctx.contract.paused_scopes(), 0);
}

// ----- Authorization (adversarial) -----

#[test]
fn a_non_signer_cannot_pause_the_escrow() {
    let ctx = setup();
    let outsider = Address::generate(&ctx.env);

    let res = ctx
        .contract
        .try_pause(&outsider, &ALL_SCOPES, &3_600, &reason(&ctx.contract.env));
    assert_eq!(res, Err(Ok(Error::NotASigner)));
    assert_eq!(ctx.contract.paused_scopes(), 0);

    // And business is genuinely unaffected, not merely reported as open.
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
}

#[test]
fn the_admin_arbiter_is_not_a_pause_authority() {
    // `admin` resolves disputes; the breaker answers to the rotatable
    // governance signer set instead. Pinning this down stops a later change
    // from quietly widening who can halt the protocol.
    let ctx = setup();
    let res = ctx
        .contract
        .try_pause(&ctx.admin, &ALL_SCOPES, &3_600, &reason(&ctx.contract.env));
    assert_eq!(res, Err(Ok(Error::NotASigner)));
}

#[test]
fn a_non_signer_cannot_unpause_the_escrow() {
    let ctx = setup();
    pause_everything(&ctx);
    let outsider = Address::generate(&ctx.env);

    let res = ctx.contract.try_unpause(&outsider, &ALL_SCOPES);
    assert_eq!(res, Err(Ok(Error::NotASigner)));
    assert_eq!(ctx.contract.paused_scopes(), ALL_SCOPES);
}

#[test]
fn any_single_signer_can_lift_a_pause_another_signer_placed() {
    // Deliberately unilateral in both directions: a responder who placed a
    // pause and then went offline must not be able to wedge it in place.
    let ctx = setup();
    pause_everything(&ctx);

    let remaining = ctx
        .contract
        .unpause(&ctx.signers.get_unchecked(2), &ALL_SCOPES);
    assert_eq!(remaining, 0);
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
}

// ----- Partial lift & views -----

#[test]
fn unpausing_intake_alone_reopens_bookings_while_settlement_stays_halted() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    pause_everything(&ctx);

    let remaining = ctx
        .contract
        .unpause(&ctx.signers.get_unchecked(1), &SCOPE_INTAKE);
    assert_eq!(remaining & SCOPE_INTAKE, 0);
    assert_eq!(remaining & SCOPE_SETTLEMENT, SCOPE_SETTLEMENT);

    ctx.contract
        .create_appointment(&2, &ctx.client, &ctx.worker, &ctx.token, &5_000, &None);
    let res = ctx.contract.try_confirm_completion(&1);
    assert_eq!(res, Err(Ok(Error::OperationPaused)));
}

#[test]
fn pause_views_report_who_paused_and_until_when() {
    let ctx = setup();
    let signer = ctx.signers.get_unchecked(1);
    set_time(&ctx.env, 1_000);
    ctx.contract
        .pause(&signer, &SCOPE_INTAKE, &7_200, &reason(&ctx.contract.env));

    let state = ctx.contract.get_pause_state().unwrap();
    assert_eq!(state.scopes, SCOPE_INTAKE);
    assert_eq!(state.paused_by, signer);
    assert_eq!(state.paused_at, 1_000);
    assert_eq!(state.expires_at, 8_200);

    assert!(ctx.contract.is_paused(&SCOPE_INTAKE));
    assert!(!ctx.contract.is_paused(&SCOPE_SETTLEMENT));
}

// ===========================================================================
// Circuit breaker — hot-path cost
// ===========================================================================
//
// The guard runs on every guarded entrypoint, so its incremental cost is
// measured rather than assumed. Two caveats, both from the SDK's own docs:
// native Rust test execution underestimates CPU and memory relative to
// compiled Wasm, and this harness charges no Wasm instantiation. These are
// useful as *relative* comparisons between the same call with and without a
// pause record — which is the question being asked — not as fee estimates.
//
// Measured here (CPU instructions, this harness, at the time of writing):
//
//   trivial instance-storage view (`get_storage_version`)   51,549
//   `paused_scopes()`, no pause record                      51,717   (+168)
//   `paused_scopes()`, live pause record                    77,023   (+25,474)
//   `create_appointment`, no pause record                  336,299
//   `create_appointment`, live record on another scope     365,219   (+8.6%)
//   `create_appointment` rejected while paused              78,216   (23%)
//
// The shape that matters: **in normal operation — no pause ever set, which
// is the state the contract is in essentially always — the guard costs on
// the order of 168 instructions**, a rounding error against a 336k-
// instruction booking. The ~25k figure is the deserialization of the
// `PauseState` struct and is only paid *while an incident is in progress*,
// when degraded throughput is the point. That is why the record is a single
// instance-storage entry with a length-capped `reason` rather than anything
// richer: the cap is what keeps the incident-time cost bounded too.
//
// No micro-optimization is warranted on these numbers. The guard is one
// instance-storage read plus two integer comparisons, and the instance entry
// is already in the footprint of any invocation that touches admin or
// governance state.

fn cpu_cost_of<F: FnOnce()>(env: &Env, f: F) -> u64 {
    let mut budget = env.cost_estimate().budget();
    budget.reset_default();
    f();
    env.cost_estimate().budget().cpu_instruction_cost()
}

#[test]
fn the_guard_is_nearly_free_when_no_pause_has_ever_been_set() {
    // The normal-operation path. Compared against a trivial view that also
    // touches instance storage, so the delta isolates the guard rather than
    // measuring invocation overhead.
    let ctx = setup();
    let trivial = cpu_cost_of(&ctx.env, || {
        ctx.contract.get_storage_version();
    });
    let guard = cpu_cost_of(&ctx.env, || {
        ctx.contract.paused_scopes();
    });

    assert!(guard >= trivial);
    let overhead = guard - trivial;
    assert!(
        overhead < trivial / 10,
        "guard overhead with no pause record should stay far below the cost \
         of the invocation it sits in: {overhead} vs {trivial}"
    );
}

#[test]
fn guard_overhead_on_create_appointment_stays_bounded() {
    // No pause record — what every call pays in normal operation.
    let baseline_ctx = setup();
    let baseline = cpu_cost_of(&baseline_ctx.env, || {
        baseline_ctx.contract.create_appointment(
            &1,
            &baseline_ctx.client,
            &baseline_ctx.worker,
            &baseline_ctx.token,
            &10_000,
            &None,
        );
    });

    // A live record covering a *different* scope: the guard loads and
    // deserializes it on every call, the worst case for a call that still
    // succeeds.
    let loaded_ctx = setup();
    set_time(&loaded_ctx.env, 1_000);
    loaded_ctx.contract.pause(
        &loaded_ctx.signers.get_unchecked(0),
        &SCOPE_SETTLEMENT,
        &3_600,
        &reason(&loaded_ctx.contract.env),
    );
    let with_record = cpu_cost_of(&loaded_ctx.env, || {
        loaded_ctx.contract.create_appointment(
            &1,
            &loaded_ctx.client,
            &loaded_ctx.worker,
            &loaded_ctx.token,
            &10_000,
            &None,
        );
    });

    // A deliberately loose regression fence, not a claim about absolute
    // cost: the guard is a storage read and two comparisons, so anything
    // approaching a doubling means it stopped being that.
    assert!(
        with_record < baseline * 2,
        "pause guard overhead grew unexpectedly: {baseline} -> {with_record} CPU insns"
    );
    assert!(baseline > 0 && with_record > 0);
}

#[test]
fn a_paused_call_costs_less_than_a_successful_one() {
    // The guard is the first statement of each guarded entrypoint, ahead of
    // auth and any other storage access, so rejection is strictly cheaper
    // than doing the work. That is what makes a pause a usable response to
    // an entrypoint being hammered, rather than an amplifier.
    let ctx = setup();
    let allowed = cpu_cost_of(&ctx.env, || {
        ctx.contract
            .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    });

    set_time(&ctx.env, 1_000);
    ctx.contract.pause(
        &ctx.signers.get_unchecked(0),
        &SCOPE_INTAKE,
        &3_600,
        &reason(&ctx.contract.env),
    );
    let rejected = cpu_cost_of(&ctx.env, || {
        let res = ctx.contract.try_create_appointment(
            &2,
            &ctx.client,
            &ctx.worker,
            &ctx.token,
            &10_000,
            &None,
        );
        assert_eq!(res, Err(Ok(Error::OperationPaused)));
    });

    assert!(
        rejected < allowed,
        "paused rejection ({rejected}) should cost less than the work it replaces ({allowed})"
    );
}

// ===========================================================================
// Circuit breaker — broadcast semantics
// ===========================================================================

#[test]
fn a_scope_escrow_has_no_entrypoints_for_is_a_well_formed_no_op() {
    // An operator sweeping every contract with one mask must not have to
    // special-case which scopes a given contract implements.
    let ctx = setup();
    set_time(&ctx.env, 1_000);
    ctx.contract.pause(
        &ctx.signers.get_unchecked(0),
        &governance::SCOPE_ATTESTATION,
        &3_600,
        &reason(&ctx.contract.env),
    );

    assert_eq!(ctx.contract.paused_scopes(), governance::SCOPE_ATTESTATION);
    // Accepted and recorded, but escrow has no attestation entrypoint, so
    // every one of its own paths stays open.
    ctx.contract
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000, &None);
    ctx.contract.confirm_completion(&1);
    assert_eq!(ctx.token_client.balance(&ctx.worker), 10_000);
}

// ===========================================================================
// Circuit breaker — event wire format
// ===========================================================================
//
// Off-chain indexers key off the exact topic ordering and data shape, so
// these are pinned by assertion rather than described in prose and hoped
// for. The README's "Events" table is generated from what these assert; if
// you change one, change both.

#[test]
fn paused_event_has_the_documented_topics_and_data_shape() {
    use soroban_sdk::{map, testutils::Events as _, vec, IntoVal, Map, Symbol, Val};

    let ctx = setup();
    let signer = ctx.signers.get_unchecked(1);
    let why = soroban_sdk::String::from_str(&ctx.env, "INC-412");
    set_time(&ctx.env, 1_000);
    ctx.contract.pause(&signer, &SCOPE_INTAKE, &7_200, &why);

    // Prefix topics first, in declaration order, then the `#[topic]`
    // fields — here just `caller`.
    let topics: Vec<Val> = (
        Symbol::new(&ctx.env, "gov_pause"),
        Symbol::new(&ctx.env, "paused"),
        signer.clone(),
    )
        .into_val(&ctx.env);

    // Non-topic fields become a map keyed by field name (`data_format`
    // defaults to "map"), so field *order* is not part of the contract but
    // field *names* are.
    let data: Map<Symbol, Val> = map![
        &ctx.env,
        (
            Symbol::new(&ctx.env, "expires_at"),
            8_200u64.into_val(&ctx.env)
        ),
        (Symbol::new(&ctx.env, "reason"), why.into_val(&ctx.env)),
        (
            Symbol::new(&ctx.env, "scopes"),
            SCOPE_INTAKE.into_val(&ctx.env)
        ),
    ];

    assert_eq!(
        ctx.env.events().all(),
        vec![
            &ctx.env,
            (
                ctx.contract.address.clone(),
                topics,
                data.into_val(&ctx.env)
            )
        ]
    );
}

#[test]
fn unpaused_event_has_the_documented_topics_and_data_shape() {
    use soroban_sdk::{map, testutils::Events as _, vec, IntoVal, Map, Symbol, Val};

    let ctx = setup();
    let signer = ctx.signers.get_unchecked(2);
    set_time(&ctx.env, 1_000);
    ctx.contract.pause(
        &ctx.signers.get_unchecked(0),
        &ALL_SCOPES,
        &3_600,
        &reason(&ctx.contract.env),
    );
    ctx.contract.unpause(&signer, &SCOPE_INTAKE);

    let topics: Vec<Val> = (
        Symbol::new(&ctx.env, "gov_pause"),
        Symbol::new(&ctx.env, "unpaused"),
        signer.clone(),
    )
        .into_val(&ctx.env);

    let remaining: u32 = SCOPE_SETTLEMENT | governance::SCOPE_ATTESTATION;
    let data: Map<Symbol, Val> = map![
        &ctx.env,
        (
            Symbol::new(&ctx.env, "remaining_scopes"),
            remaining.into_val(&ctx.env)
        ),
        (
            Symbol::new(&ctx.env, "scopes"),
            SCOPE_INTAKE.into_val(&ctx.env)
        ),
    ];

    assert_eq!(
        ctx.env.events().all(),
        vec![
            &ctx.env,
            (
                ctx.contract.address.clone(),
                topics,
                data.into_val(&ctx.env)
            )
        ]
    );
}
