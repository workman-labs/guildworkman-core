#![no_std]

//! Decentralized dispute resolution for GuildWorkman.
//!
//! Models a dispute as an on-chain state machine resolved by a **staked jury**
//! using **commit-reveal** voting, with **reward distribution** to the majority
//! and **slashing** of the minority and no-shows. It is the decentralized
//! counterpart to `escrow`'s single-admin `resolve_dispute`: instead of one
//! arbiter unilaterally deciding, any number of jurors stake collateral, vote
//! secretly, and are paid out of — or slashed into — a shared pot based on
//! whether they sided with the eventual majority.
//!
//! ## Lifecycle
//!
//! ```text
//!   open_dispute        commit_vote        reveal_vote          resolve
//!        │                  │                   │                  │
//!        ▼                  ▼                   ▼                  ▼
//!    ┌────────┐  commit  ┌────────┐  reveal  ┌────────┐  tally  ┌──────────┐
//!    │  OPEN  │────────► │ COMMIT │────────► │ REVEAL │───────► │ RESOLVED │
//!    └────────┘  phase   └────────┘  phase   └────────┘         └──────────┘
//! ```
//!
//! There are two time-boxed phases, derived from the current ledger against the
//! dispute's two deadlines (not stored as an explicit enum, so they can't drift
//! out of sync with the clock):
//!
//! - **Commit phase** (`now <= commit_deadline`): a juror stakes `juror_stake`
//!   tokens and submits `commitment = sha256(salt || vote_byte || juror_xdr)` —
//!   a binding but hidden vote.
//! - **Reveal phase** (`commit_deadline < now <= reveal_deadline`): the juror
//!   discloses `(vote, salt)`; the contract recomputes the hash and, on a match,
//!   records the vote and bumps the running tally.
//! - **Resolution** (`now > reveal_deadline`): anyone calls `resolve`, which
//!   tallies revealed votes into an outcome and fixes the per-winner reward.
//!   Jurors then `withdraw` individually (a pull pattern, so resolution never
//!   loops over an unbounded juror set).
//!
//! ## Why commit-reveal
//!
//! If votes were cast in the clear, later jurors could copy the current leader
//! (bandwagon / herding) and an attacker could bribe or coerce for a *visible*
//! vote. Hiding the vote behind a salted hash until everyone has committed
//! removes both. Binding the commitment to the juror's own address
//! (`… || juror_xdr`) stops a copycat from front-running by replaying someone
//! else's commitment: the copied hash can never be revealed from the copycat's
//! address.
//!
//! ## Incentives (rewards & slashing)
//!
//! At resolution the side with more revealed votes wins. Each **winner** gets
//! their stake back plus an equal share of the **slashed pot** — the stakes of
//! the minority voters *and* of everyone who committed but never revealed (a
//! no-show is treated as a loser, so jurors are paid to actually show up). On a
//! **tie** or a **quorum failure** (fewer than `min_jurors` revealed) nobody is
//! slashed: the vote is inconclusive, so every juror who staked simply reclaims
//! their stake.

use soroban_sdk::{
    contract, contracterror, contractimpl, contracttype, token, xdr::ToXdr, Address, Bytes, BytesN,
    Env,
};

/// Storage keys. See the README "Storage layout" table for the durability of
/// each and what it holds.
#[contracttype]
pub enum DataKey {
    Admin,
    Token,
    Config,
    /// A dispute case, keyed by `dispute_id`.
    Dispute(u64),
    /// A juror's per-dispute state: `(dispute_id, juror) -> Juror`.
    Juror(u64, Address),
}

/// Immutable-after-initialize jury parameters.
#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Config {
    /// Collateral each juror must stake to vote, in `token` units.
    pub juror_stake: i128,
    /// Minimum number of *revealed* votes for the tally to be binding. Below
    /// this the dispute resolves as `QuorumFailed` and no one is slashed.
    pub min_jurors: u32,
    /// Length of the commit phase, in ledgers after `open_dispute`.
    pub commit_window: u32,
    /// Length of the reveal phase, in ledgers after the commit deadline.
    pub reveal_window: u32,
    /// Address of the reputation contract.
    pub reputation_contract: Address,
    /// Base vote weight for jurors with no reputation.
    pub base_vote_weight: u64,
    /// Margin threshold in basis points for close calls (e.g., 500 = 5%).
    pub close_call_margin_bps: u32,
}

/// The final verdict of a dispute, set once by `resolve`.
#[contracttype]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Outcome {
    /// Not yet resolved.
    Undecided,
    /// Majority sided with the plaintiff (`vote == true`).
    Plaintiff,
    /// Majority sided with the defendant (`vote == false`).
    Defendant,
    /// Equal revealed votes on each side — inconclusive, stakes refunded.
    Tie,
    /// Fewer than `min_jurors` revealed — inconclusive, stakes refunded.
    QuorumFailed,
}

/// A single dispute case and its running tally.
#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Dispute {
    /// The party claiming they are owed the outcome (`vote == true` favors them).
    pub plaintiff: Address,
    /// The counterparty (`vote == false` favors them).
    pub defendant: Address,
    /// Last ledger on which `commit_vote` is accepted.
    pub commit_deadline: u32,
    /// Last ledger on which `reveal_vote` is accepted.
    pub reveal_deadline: u32,
    /// Jurors who committed (and staked).
    pub juror_count: u32,
    /// Jurors who have revealed their votes.
    pub revealed_count: u32,
    /// Total voting weight of all committed jurors.
    pub total_committed_weight: u64,
    /// Revealed voting weight favoring the plaintiff (`vote == true`).
    pub yes_weight: u64,
    /// Revealed voting weight favoring the defendant (`vote == false`).
    pub no_weight: u64,
    /// Number of revealed voters favoring the plaintiff.
    pub yes_count: u32,
    /// Number of revealed voters favoring the defendant.
    pub no_count: u32,
    /// Verdict; `Undecided` until `resolve`.
    pub outcome: Outcome,
    /// Indicates if the margin was below `close_call_margin_bps`.
    pub is_close_call: bool,
    /// The total amount of staked tokens from slashed jurors.
    pub total_slashed_pot: i128,
    /// The total voting weight of the winning side.
    pub winning_weight: u64,
    /// `true` once `resolve` has run.
    pub resolved: bool,
}

/// A juror's participation in one dispute.
#[contracttype]
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Juror {
    /// `sha256(salt || vote_byte || juror_xdr)` submitted during commit.
    pub commitment: BytesN<32>,
    /// The voting power assigned to this juror based on reputation.
    pub weight: u64,
    /// Set once the commitment has been successfully revealed.
    pub revealed: bool,
    /// The revealed vote; meaningful only when `revealed`.
    pub vote: bool,
    /// Set once the juror has withdrawn (or been slashed) — blocks double spend.
    pub withdrawn: bool,
}

#[contracterror]
#[derive(Copy, Clone, Debug, Eq, PartialEq, PartialOrd, Ord)]
pub enum Error {
    AlreadyInitialized = 1,
    NotInitialized = 2,
    InvalidConfig = 3,
    DisputeExists = 4,
    DisputeNotFound = 5,
    NotCommitPhase = 6,
    NotRevealPhase = 7,
    AlreadyCommitted = 8,
    NotCommitted = 9,
    AlreadyRevealed = 10,
    InvalidReveal = 11,
    NotResolvable = 12,
    AlreadyResolved = 13,
    NotResolved = 14,
    AlreadyWithdrawn = 15,
    NothingToWithdraw = 16,
    SameParties = 17,
}

const DAY_IN_LEDGERS: u32 = 17_280; // ~1 day at ~5s/ledger
const INSTANCE_BUMP_AMOUNT: u32 = DAY_IN_LEDGERS * 60;
const INSTANCE_LIFETIME_THRESHOLD: u32 = DAY_IN_LEDGERS * 30;
const DISPUTE_BUMP_AMOUNT: u32 = DAY_IN_LEDGERS * 30;
const DISPUTE_LIFETIME_THRESHOLD: u32 = DAY_IN_LEDGERS * 29;

#[soroban_sdk::contractclient(name = "ReputationClient")]
pub trait Reputation {
    fn get_reputation_score_x10000(env: Env, worker: Address) -> u64;
}

#[contract]
pub struct DisputeResolution;

#[contractimpl]
impl DisputeResolution {
    /// One-time setup.
    ///
    /// - `admin` opens disputes and is the trust root for scheduling them.
    /// - `token` is the staking token jurors post collateral in and are paid
    ///   from (a standard SEP-41 / Stellar Asset Contract).
    /// - `config` fixes the stake, quorum, and phase windows for the contract's
    ///   life.
    pub fn initialize(
        env: Env,
        admin: Address,
        token: Address,
        config: Config,
    ) -> Result<(), Error> {
        if env.storage().instance().has(&DataKey::Admin) {
            return Err(Error::AlreadyInitialized);
        }
        admin.require_auth();
        Self::validate_config(&config)?;

        env.storage().instance().set(&DataKey::Admin, &admin);
        env.storage().instance().set(&DataKey::Token, &token);
        env.storage().instance().set(&DataKey::Config, &config);
        env.storage()
            .instance()
            .extend_ttl(INSTANCE_LIFETIME_THRESHOLD, INSTANCE_BUMP_AMOUNT);
        Ok(())
    }

    /// Admin-only: open a new dispute between `plaintiff` and `defendant`.
    ///
    /// Starts the commit phase immediately: `commit_deadline = now +
    /// commit_window` and `reveal_deadline = commit_deadline + reveal_window`.
    pub fn open_dispute(
        env: Env,
        dispute_id: u64,
        plaintiff: Address,
        defendant: Address,
    ) -> Result<(), Error> {
        let admin = Self::require_admin(&env)?;
        admin.require_auth();

        if plaintiff == defendant {
            return Err(Error::SameParties);
        }

        let key = DataKey::Dispute(dispute_id);
        if env.storage().persistent().has(&key) {
            return Err(Error::DisputeExists);
        }

        let config = Self::read_config(&env);
        let now = env.ledger().sequence();
        let commit_deadline = now.saturating_add(config.commit_window);
        let reveal_deadline = commit_deadline.saturating_add(config.reveal_window);

        let dispute = Dispute {
            plaintiff,
            defendant,
            commit_deadline,
            reveal_deadline,
            juror_count: 0,
            revealed_count: 0,
            total_committed_weight: 0,
            yes_weight: 0,
            no_weight: 0,
            yes_count: 0,
            no_count: 0,
            outcome: Outcome::Undecided,
            is_close_call: false,
            total_slashed_pot: 0,
            winning_weight: 0,
            resolved: false,
        };
        env.storage().persistent().set(&key, &dispute);
        Self::bump_dispute(&env, &key);
        Self::bump_instance(&env);
        Ok(())
    }

    /// Juror-authorized: stake collateral and submit a hidden vote.
    ///
    /// `commitment` must be `sha256(salt || vote_byte || juror_xdr)`, where
    /// `vote_byte` is `1` to favor the plaintiff or `0` to favor the defendant,
    /// `salt` is a 32-byte secret, and `juror_xdr` is the juror's own address
    /// XDR (see [`compute_commitment`]). Transfers `juror_stake` from the juror
    /// into the contract. One commitment per juror per dispute.
    pub fn commit_vote(
        env: Env,
        dispute_id: u64,
        juror: Address,
        commitment: BytesN<32>,
    ) -> Result<(), Error> {
        juror.require_auth();

        let dispute_key = DataKey::Dispute(dispute_id);
        let mut dispute = Self::read_dispute(&env, &dispute_key)?;

        // Commit phase: on or before the commit deadline.
        if env.ledger().sequence() > dispute.commit_deadline {
            return Err(Error::NotCommitPhase);
        }

        let juror_key = DataKey::Juror(dispute_id, juror.clone());
        if env.storage().persistent().has(&juror_key) {
            return Err(Error::AlreadyCommitted);
        }

        let config = Self::read_config(&env);
        let token = Self::read_token(&env);
        token::Client::new(&env, &token).transfer(
            &juror,
            env.current_contract_address(),
            &config.juror_stake,
        );

        let rep_client = ReputationClient::new(&env, &config.reputation_contract);
        let rep_score = match rep_client.try_get_reputation_score_x10000(&juror) {
            Ok(Ok(score)) => score,
            _ => 0,
        };
        let weight = core::cmp::max(rep_score, config.base_vote_weight);

        let record = Juror {
            commitment,
            weight,
            revealed: false,
            vote: false,
            withdrawn: false,
        };
        env.storage().persistent().set(&juror_key, &record);
        Self::bump_dispute(&env, &juror_key);

        dispute.juror_count = dispute.juror_count.saturating_add(1);
        dispute.total_committed_weight = dispute.total_committed_weight.saturating_add(weight);
        env.storage().persistent().set(&dispute_key, &dispute);
        Self::bump_dispute(&env, &dispute_key);
        Self::bump_instance(&env);
        Ok(())
    }

    /// Juror-authorized: reveal a previously committed vote.
    ///
    /// Recomputes `sha256(salt || vote_byte || juror_xdr)` and requires it to
    /// equal the stored commitment, then records the vote and bumps the running
    /// tally. Accepted only during the reveal phase, once per juror.
    pub fn reveal_vote(
        env: Env,
        dispute_id: u64,
        juror: Address,
        vote: bool,
        salt: BytesN<32>,
    ) -> Result<(), Error> {
        juror.require_auth();

        let dispute_key = DataKey::Dispute(dispute_id);
        let mut dispute = Self::read_dispute(&env, &dispute_key)?;

        // Reveal phase: strictly after the commit deadline, up to the reveal one.
        let now = env.ledger().sequence();
        if now <= dispute.commit_deadline || now > dispute.reveal_deadline {
            return Err(Error::NotRevealPhase);
        }

        let juror_key = DataKey::Juror(dispute_id, juror.clone());
        let mut record: Juror = env
            .storage()
            .persistent()
            .get(&juror_key)
            .ok_or(Error::NotCommitted)?;
        if record.revealed {
            return Err(Error::AlreadyRevealed);
        }

        let expected = Self::commitment_hash(&env, &juror, vote, &salt);
        if expected != record.commitment {
            return Err(Error::InvalidReveal);
        }

        record.revealed = true;
        record.vote = vote;
        env.storage().persistent().set(&juror_key, &record);
        Self::bump_dispute(&env, &juror_key);

        if vote {
            dispute.yes_weight = dispute.yes_weight.saturating_add(record.weight);
            dispute.yes_count = dispute.yes_count.saturating_add(1);
        } else {
            dispute.no_weight = dispute.no_weight.saturating_add(record.weight);
            dispute.no_count = dispute.no_count.saturating_add(1);
        }
        dispute.revealed_count = dispute.revealed_count.saturating_add(1);
        env.storage().persistent().set(&dispute_key, &dispute);
        Self::bump_dispute(&env, &dispute_key);
        Self::bump_instance(&env);
        Ok(())
    }

    /// Permissionless: tally the revealed votes into a final `Outcome` once the
    /// reveal phase has ended.
    ///
    /// Computes the winning side, the number of winners, and the per-winner
    /// slashed-pot share. On a tie or below-quorum turnout nobody is slashed and
    /// every staker can reclaim their stake. Idempotent guard: a dispute can
    /// only be resolved once.
    pub fn resolve(env: Env, dispute_id: u64) -> Result<Outcome, Error> {
        let dispute_key = DataKey::Dispute(dispute_id);
        let mut dispute = Self::read_dispute(&env, &dispute_key)?;

        if dispute.resolved {
            return Err(Error::AlreadyResolved);
        }
        if env.ledger().sequence() <= dispute.reveal_deadline {
            return Err(Error::NotResolvable);
        }

        let config = Self::read_config(&env);
        let total_revealed_weight = dispute.yes_weight.saturating_add(dispute.no_weight);

        // Calculate margin (scaled by 10,000 for basis points)
        let margin_bps = if total_revealed_weight == 0 {
            0
        } else {
            let diff = dispute.yes_weight.abs_diff(dispute.no_weight);
            (diff as u128 * 10_000 / total_revealed_weight as u128) as u32
        };

        let is_close_call = margin_bps <= config.close_call_margin_bps;

        let outcome = if dispute.revealed_count < config.min_jurors {
            Outcome::QuorumFailed
        } else if dispute.yes_weight > dispute.no_weight {
            Outcome::Plaintiff
        } else if dispute.no_weight > dispute.yes_weight {
            Outcome::Defendant
        } else {
            Outcome::Tie
        };

        let winning_weight = match outcome {
            Outcome::Plaintiff => dispute.yes_weight,
            Outcome::Defendant => dispute.no_weight,
            _ => 0,
        };

        let winning_count = match outcome {
            Outcome::Plaintiff => dispute.yes_count,
            Outcome::Defendant => dispute.no_count,
            _ => 0,
        };

        let mut total_slashed_pot: i128 = 0;
        if winning_weight > 0 {
            let no_shows_count = dispute.juror_count.saturating_sub(dispute.revealed_count) as i128;
            total_slashed_pot += no_shows_count.saturating_mul(config.juror_stake);

            if !is_close_call {
                // Not a close call: minority is also slashed
                let minority_count = dispute.revealed_count.saturating_sub(winning_count) as i128;
                total_slashed_pot += minority_count.saturating_mul(config.juror_stake);
            }
        }

        dispute.outcome = outcome;
        dispute.is_close_call = is_close_call;
        dispute.total_slashed_pot = total_slashed_pot;
        dispute.winning_weight = winning_weight;
        dispute.resolved = true;
        env.storage().persistent().set(&dispute_key, &dispute);
        Self::bump_dispute(&env, &dispute_key);
        Self::bump_instance(&env);
        Ok(outcome)
    }

    /// Juror-authorized: settle a juror's position after resolution.
    ///
    /// - Decided (`Plaintiff`/`Defendant`): a juror who revealed a vote for the
    ///   winning side gets `juror_stake + reward_per_winner`; a minority voter or
    ///   a no-show is slashed and this errors with `NothingToWithdraw`.
    /// - `Tie`/`QuorumFailed`: every staker reclaims exactly `juror_stake`.
    ///
    /// Marks the juror `withdrawn` before transferring (checks-effects-
    /// interactions), so it can never pay out twice. Returns the amount paid.
    pub fn withdraw(env: Env, dispute_id: u64, juror: Address) -> Result<i128, Error> {
        juror.require_auth();

        let dispute_key = DataKey::Dispute(dispute_id);
        let dispute = Self::read_dispute(&env, &dispute_key)?;
        if !dispute.resolved {
            return Err(Error::NotResolved);
        }

        let juror_key = DataKey::Juror(dispute_id, juror.clone());
        let mut record: Juror = env
            .storage()
            .persistent()
            .get(&juror_key)
            .ok_or(Error::NotCommitted)?;
        if record.withdrawn {
            return Err(Error::AlreadyWithdrawn);
        }

        let config = Self::read_config(&env);
        let payout = match dispute.outcome {
            Outcome::Plaintiff | Outcome::Defendant => {
                let winning_vote = dispute.outcome == Outcome::Plaintiff;
                if record.revealed {
                    if record.vote == winning_vote {
                        // Winner: gets stake + proportional share of slashed pot
                        let reward = if dispute.winning_weight > 0 {
                            (record.weight as i128 * dispute.total_slashed_pot)
                                / dispute.winning_weight as i128
                        } else {
                            0
                        };
                        config.juror_stake + reward
                    } else {
                        // Minority voter
                        if dispute.is_close_call {
                            // Refunded
                            config.juror_stake
                        } else {
                            // Slashed
                            return Err(Error::NothingToWithdraw);
                        }
                    }
                } else {
                    // No-show is always slashed
                    return Err(Error::NothingToWithdraw);
                }
            }
            // Inconclusive: refund every staker their collateral, no slashing.
            Outcome::Tie | Outcome::QuorumFailed => config.juror_stake,
            Outcome::Undecided => return Err(Error::NotResolved),
        };

        // Effects before interaction: mark settled, then transfer.
        record.withdrawn = true;
        env.storage().persistent().set(&juror_key, &record);
        Self::bump_dispute(&env, &juror_key);

        let token = Self::read_token(&env);
        token::Client::new(&env, &token).transfer(&env.current_contract_address(), &juror, &payout);
        Self::bump_instance(&env);
        Ok(payout)
    }

    // --- views ---

    /// Recompute the commitment for a `(juror, vote, salt)` triple. Exposed so
    /// off-chain callers can build their commitment with the exact same domain
    /// separation the contract enforces on reveal.
    pub fn compute_commitment(
        env: Env,
        juror: Address,
        vote: bool,
        salt: BytesN<32>,
    ) -> BytesN<32> {
        Self::commitment_hash(&env, &juror, vote, &salt)
    }

    pub fn get_dispute(env: Env, dispute_id: u64) -> Result<Dispute, Error> {
        Self::read_dispute(&env, &DataKey::Dispute(dispute_id))
    }

    pub fn get_juror(env: Env, dispute_id: u64, juror: Address) -> Result<Juror, Error> {
        env.storage()
            .persistent()
            .get(&DataKey::Juror(dispute_id, juror))
            .ok_or(Error::NotCommitted)
    }

    pub fn get_config(env: Env) -> Result<Config, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Config)
            .ok_or(Error::NotInitialized)
    }

    pub fn get_admin(env: Env) -> Result<Address, Error> {
        Self::require_admin(&env)
    }

    pub fn get_token(env: Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Token)
            .ok_or(Error::NotInitialized)
    }

    // --- internal helpers ---

    fn validate_config(config: &Config) -> Result<(), Error> {
        if config.juror_stake <= 0
            || config.min_jurors == 0
            || config.commit_window == 0
            || config.reveal_window == 0
        {
            return Err(Error::InvalidConfig);
        }
        Ok(())
    }

    /// `sha256(salt || vote_byte || juror_xdr)`. Binding the juror's own address
    /// into the preimage stops a copycat from replaying someone else's
    /// commitment: the copied hash can never be revealed from a different
    /// address.
    fn commitment_hash(env: &Env, juror: &Address, vote: bool, salt: &BytesN<32>) -> BytesN<32> {
        let mut preimage = Bytes::new(env);
        let salt_bytes: Bytes = salt.clone().into();
        preimage.append(&salt_bytes);
        preimage.push_back(if vote { 1u8 } else { 0u8 });
        preimage.append(&juror.clone().to_xdr(env));
        env.crypto().sha256(&preimage).to_bytes()
    }

    fn require_admin(env: &Env) -> Result<Address, Error> {
        env.storage()
            .instance()
            .get(&DataKey::Admin)
            .ok_or(Error::NotInitialized)
    }

    fn read_token(env: &Env) -> Address {
        env.storage()
            .instance()
            .get(&DataKey::Token)
            .expect("not initialized")
    }

    fn read_config(env: &Env) -> Config {
        env.storage()
            .instance()
            .get(&DataKey::Config)
            .expect("not initialized")
    }

    fn read_dispute(env: &Env, key: &DataKey) -> Result<Dispute, Error> {
        env.storage()
            .persistent()
            .get(key)
            .ok_or(Error::DisputeNotFound)
    }

    fn bump_dispute(env: &Env, key: &DataKey) {
        env.storage()
            .persistent()
            .extend_ttl(key, DISPUTE_LIFETIME_THRESHOLD, DISPUTE_BUMP_AMOUNT);
    }

    fn bump_instance(env: &Env) {
        env.storage()
            .instance()
            .extend_ttl(INSTANCE_LIFETIME_THRESHOLD, INSTANCE_BUMP_AMOUNT);
    }
}

#[cfg(test)]
mod test;
