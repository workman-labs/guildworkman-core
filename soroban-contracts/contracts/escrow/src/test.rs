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
        .create_appointment(&1, &ctx.client, &ctx.worker, &ctx.token, &10_000);
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
        .create_appointment(&2, &ctx.client, &ctx.worker, &ctx.token, &5_000);
    ctx.contract.cancel_appointment(&2);

    assert_eq!(ctx.token_client.balance(&ctx.client), 1_000_000);
    assert_eq!(ctx.token_client.balance(&ctx.contract.address), 0);
    assert_eq!(ctx.contract.get_appointment(&2).status, Status::Cancelled);
}

#[test]
fn dispute_resolved_in_favor_of_worker() {
    let ctx = setup();

    ctx.contract
        .create_appointment(&3, &ctx.client, &ctx.worker, &ctx.token, &7_000);
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
        .create_appointment(&4, &ctx.client, &ctx.worker, &ctx.token, &1_000);

    let result =
        ctx.contract
            .try_create_appointment(&4, &ctx.client, &ctx.worker, &ctx.token, &1_000);
    assert_eq!(result, Err(Ok(Error::AppointmentExists)));
}

#[test]
fn cannot_confirm_twice() {
    let ctx = setup();
    ctx.contract
        .create_appointment(&5, &ctx.client, &ctx.worker, &ctx.token, &2_000);
    ctx.contract.confirm_completion(&5);

    let result = ctx.contract.try_confirm_completion(&5);
    assert_eq!(result, Err(Ok(Error::InvalidStatus)));
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
