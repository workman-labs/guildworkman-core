package com.guildworkman.api.signing;

import com.guildworkman.api.escrow.rpc.SorobanRpcClient;
import com.guildworkman.api.signing.model.ChannelAccount;
import com.guildworkman.api.signing.model.ChannelAccountStatus;
import com.guildworkman.api.signing.model.SubmissionStatus;
import com.guildworkman.api.signing.model.TransactionSubmission;
import com.guildworkman.api.signing.repository.ChannelAccountRepository;
import com.guildworkman.api.signing.repository.TransactionSubmissionRepository;
import com.guildworkman.api.signing.service.ChannelAccountBusyException;
import com.guildworkman.api.signing.service.ChannelAccountLeaseService;
import com.guildworkman.api.signing.service.ChannelAccountService;
import com.guildworkman.api.signing.service.NoChannelAccountAvailableException;
import com.guildworkman.api.signing.service.SequenceLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.stellar.sdk.KeyPair;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The concurrency contract the whole feature rests on: N submissions running
 * at once get N distinct sequence numbers, on N distinct accounts, with no
 * coordination beyond the database.
 *
 * <p>This runs against real PostgreSQL — {@code SELECT … FOR UPDATE SKIP
 * LOCKED} is the mechanism under test, so an in-memory database would test
 * nothing. Only the Soroban RPC is stubbed.
 */
@SpringBootTest(properties = {
        "stellar.signing.prepare-poll-delay-ms=3600000",
        "stellar.signing.broadcast-poll-delay-ms=3600000",
        "stellar.signing.confirm-poll-delay-ms=3600000",
        "stellar.signing.lease-sweep-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000",
        "escrow.orchestration.submit-poll-delay-ms=3600000",
        "escrow.orchestration.confirm-poll-delay-ms=3600000",
        "escrow.reconciliation.poll-delay-ms=3600000",
        "booking.expiry-sweep-delay-ms=3600000",
        "spring.task.scheduling.enabled=false"
})
class ChannelAccountLeaseIntegrationTest {

    private static final int POOL_SIZE = 8;

    /** keyRef → key pair, so the test can assert which account a lease actually landed on. */
    private static final Map<String, KeyPair> KEYS = new LinkedHashMap<>();

    @DynamicPropertySource
    static void localSigningKeys(DynamicPropertyRegistry registry) {
        for (int i = 0; i < POOL_SIZE; i++) {
            String keyRef = "leasetest" + i;
            KeyPair keyPair = KeyPair.random();
            KEYS.put(keyRef, keyPair);
            registry.add("stellar.signing.local.keys." + keyRef, () -> String.valueOf(keyPair.getSecretSeed()));
        }
    }

    @Autowired
    private ChannelAccountService channelAccountService;

    @Autowired
    private ChannelAccountLeaseService leases;

    @Autowired
    private ChannelAccountRepository channelAccounts;

    @Autowired
    private TransactionSubmissionRepository submissions;

    @MockBean
    private SorobanRpcClient sorobanRpcClient;

    /**
     * Real accounts have independent sequence numbers, so the stub gives each
     * one its own — a shared constant would make "distinct sequence numbers"
     * unprovable for reasons that have nothing to do with the leasing code.
     */
    private final Map<String, Long> onChainSequences = new HashMap<>();

    @BeforeEach
    void cleanSlate() {
        reset(sorobanRpcClient);
        submissions.deleteAll();
        channelAccounts.deleteAll();
        onChainSequences.clear();

        long base = 1_000_000L;
        for (KeyPair keyPair : KEYS.values()) {
            onChainSequences.put(keyPair.getAccountId(), base);
            base += 10_000L;
        }
        when(sorobanRpcClient.getAccountSequence(anyString()))
                .thenAnswer(invocation -> onChainSequences.get(invocation.getArgument(0, String.class)));
    }

    private List<ChannelAccount> registerPool(int size) {
        List<ChannelAccount> registered = new ArrayList<>();
        KEYS.keySet().stream().limit(size).forEach(keyRef -> registered.add(channelAccountService.register(keyRef)));
        return registered;
    }

    // --- the acceptance criterion -------------------------------------------

    @Test
    void concurrentAcquisitionsGetDistinctAccountsAndDistinctSequenceNumbers() throws Exception {
        registerPool(POOL_SIZE);

        // A barrier makes the threads actually contend, rather than trickling
        // through one at a time and passing for the wrong reason.
        CyclicBarrier start = new CyclicBarrier(POOL_SIZE);
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        List<Callable<SequenceLease>> tasks = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            long submissionId = 1000L + i;
            tasks.add(() -> {
                start.await(30, TimeUnit.SECONDS);
                return leases.acquire(submissionId);
            });
        }

        List<Future<SequenceLease>> futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
        List<SequenceLease> acquired = new ArrayList<>();
        for (Future<SequenceLease> future : futures) {
            acquired.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(acquired).hasSize(POOL_SIZE);
        assertThat(acquired).extracting(SequenceLease::channelAccountId).doesNotHaveDuplicates();
        assertThat(acquired).extracting(SequenceLease::accountId).doesNotHaveDuplicates();
        assertThat(acquired).extracting(SequenceLease::sequenceNumber).doesNotHaveDuplicates();

        // Each lease carries exactly the chain's sequence + 1 for its account.
        for (SequenceLease lease : acquired) {
            assertThat(lease.sequenceNumber()).isEqualTo(onChainSequences.get(lease.accountId()) + 1);
        }
        assertThat(channelAccounts.countByStatus(ChannelAccountStatus.LEASED)).isEqualTo(POOL_SIZE);
    }

    /**
     * With more contenders than accounts, the surplus must be turned away
     * cleanly — never handed a duplicate sequence number, which is the failure
     * mode that would put two conflicting transactions on the network.
     */
    @Test
    void anExhaustedPoolRefusesRatherThanReusingAnAccount() throws Exception {
        int accounts = 3;
        int contenders = POOL_SIZE;
        registerPool(accounts);

        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        List<Callable<SequenceLease>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            long submissionId = 2000L + i;
            tasks.add(() -> {
                try {
                    return leases.acquire(submissionId);
                } catch (NoChannelAccountAvailableException ex) {
                    return null;
                }
            });
        }

        List<SequenceLease> acquired = new ArrayList<>();
        for (Future<SequenceLease> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
            SequenceLease lease = future.get(10, TimeUnit.SECONDS);
            if (lease != null) {
                acquired.add(lease);
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(acquired).hasSize(accounts);
        assertThat(acquired).extracting(SequenceLease::channelAccountId).doesNotHaveDuplicates();
    }

    // --- the correction path -------------------------------------------------

    @Test
    void aConsumedSequenceLetsTheAccountBeReusedWithTheNextNumber() {
        ChannelAccount account = registerPool(1).get(0);
        long onChain = onChainSequences.get(account.getAccountId());

        SequenceLease first = leases.acquire(1L);
        leases.release(first.channelAccountId(), true);

        assertThat(channelAccounts.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(ChannelAccountStatus.AVAILABLE);

        SequenceLease second = leases.acquire(2L);

        assertThat(first.sequenceNumber()).isEqualTo(onChain + 1);
        // No resync needed, so the counter simply advances.
        assertThat(second.sequenceNumber()).isEqualTo(onChain + 2);
    }

    /**
     * The subtle one. A transaction that never landed leaves the pool's counter
     * one ahead of the chain; reusing it unchanged would earn a
     * {@code txBAD_SEQ} on every subsequent transaction from that account.
     * Releasing unconsumed must therefore force a re-read.
     */
    @Test
    void anUnconsumedSequenceForcesAResyncAndRewindsTheCounter() {
        ChannelAccount account = registerPool(1).get(0);
        long onChain = onChainSequences.get(account.getAccountId());

        SequenceLease first = leases.acquire(1L);
        leases.release(first.channelAccountId(), false);

        assertThat(channelAccounts.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);

        SequenceLease second = leases.acquire(2L);

        assertThat(second.sequenceNumber()).isEqualTo(onChain + 1).isEqualTo(first.sequenceNumber());
    }

    @Test
    void releasingAnAccountThatIsNotLeasedIsANoOp() {
        ChannelAccount account = registerPool(1).get(0);
        leases.release(account.getId(), true);
        leases.release(null, true);

        assertThat(channelAccounts.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);
    }

    @Test
    void anAccountThatIsNotOnChainCannotBeLeased() {
        ChannelAccount account = registerPool(1).get(0);
        onChainSequences.remove(account.getAccountId());

        assertThatThrownBy(() -> leases.acquire(1L))
                .isInstanceOf(NoChannelAccountAvailableException.class)
                .hasMessageContaining("fund it");
    }

    // --- the sweeper ---------------------------------------------------------

    /**
     * Crash recovery. A lease with no live submission behind it is an orphan:
     * the process died between committing the lease and committing the row
     * that would have used it.
     */
    @Test
    void theSweeperReclaimsAnOrphanedLease() {
        ChannelAccount account = registerPool(1).get(0);
        leases.acquire(999L);
        expireLease(account.getId());

        leases.sweepExpiredLeases();

        ChannelAccount swept = channelAccounts.findById(account.getId()).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);
        assertThat(swept.getLeasedBySubmissionId()).isNull();
    }

    /**
     * And the conservative half: a lease whose transaction may still be in the
     * mempool is extended, not reclaimed. Reusing its sequence number would
     * race the transaction it belongs to.
     */
    @Test
    void theSweeperExtendsALeaseWhoseSubmissionIsStillInFlight() {
        ChannelAccount account = registerPool(1).get(0);
        SequenceLease lease = leases.acquire(1L);

        TransactionSubmission inFlight = new TransactionSubmission();
        inFlight.setIdempotencyKey("sweep-" + System.nanoTime());
        inFlight.setUnsignedTransactionXdr(StellarTestFixtures.unsignedEnvelope());
        inFlight.setStatus(SubmissionStatus.BROADCAST);
        inFlight.setChannelAccountId(lease.channelAccountId());
        inFlight.setNextAttemptAt(Instant.now());
        submissions.saveAndFlush(inFlight);

        expireLease(account.getId());
        leases.sweepExpiredLeases();

        ChannelAccount swept = channelAccounts.findById(account.getId()).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(ChannelAccountStatus.LEASED);
        assertThat(swept.getLeaseExpiresAt()).isAfter(Instant.now());
    }

    /**
     * The recovery chain end to end, from a crash to the next usable sequence
     * number: a holder dies mid-submission → its lease expires → the sweeper
     * reclaims it to {@code NEEDS_RESYNC} → the next lease <b>re-reads the
     * chain before allocating</b> → the number handed out is the chain's, not
     * the local counter's.
     *
     * <p>The last link is the one worth asserting explicitly, and it's the
     * reason the chain's sequence is moved between the two leases here. If the
     * resync were skipped the second lease would hand out the counter's own
     * next value and the assertion would fail on a number that <em>looks</em>
     * perfectly plausible — an off-by-one nobody notices until every
     * transaction from this account starts coming back {@code txBAD_SEQ}.
     */
    @Test
    void aCrashedHolderIsSweptAndTheNextLeaseReReadsTheChainBeforeAllocating() {
        ChannelAccount account = registerPool(1).get(0);
        long onChainAtCrashTime = onChainSequences.get(account.getAccountId());

        // A submission takes the account and the process dies: nothing ever
        // releases the lease, and we can't know whether its transaction landed.
        SequenceLease orphaned = leases.acquire(999L);
        assertThat(orphaned.sequenceNumber()).isEqualTo(onChainAtCrashTime + 1);

        expireLease(account.getId());
        leases.sweepExpiredLeases();
        assertThat(channelAccounts.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);

        // While we were away the account moved on — the crashed transaction
        // landed after all, and something else used the account too.
        long onChainNow = onChainAtCrashTime + 5;
        onChainSequences.put(account.getAccountId(), onChainNow);

        SequenceLease afterRecovery = leases.acquire(1000L);

        verify(sorobanRpcClient, atLeastOnce()).getAccountSequence(account.getAccountId());
        assertThat(afterRecovery.sequenceNumber())
                .withFailMessage("the lease after a resync used the local counter instead of the chain")
                .isEqualTo(onChainNow + 1);
        assertThat(channelAccounts.findById(account.getId()).orElseThrow().getLastSyncedAt()).isNotNull();
    }

    /**
     * The same guarantee for the ordinary (non-crash) unconsumed release —
     * a fee-ceiling failure, a dead-letter, an expired envelope. The chain is
     * re-read, and the account's counter follows the network down as well as
     * up.
     */
    @Test
    void anUnconsumedReleaseMakesTheNextLeaseReReadRatherThanTrustTheCounter() {
        ChannelAccount account = registerPool(1).get(0);

        SequenceLease first = leases.acquire(1L);
        leases.release(first.channelAccountId(), false);
        reset(sorobanRpcClient);
        when(sorobanRpcClient.getAccountSequence(anyString()))
                .thenAnswer(invocation -> onChainSequences.get(invocation.getArgument(0, String.class)));

        // The chain is now *behind* where the local counter was left.
        long rewound = onChainSequences.get(account.getAccountId()) - 3;
        onChainSequences.put(account.getAccountId(), rewound);

        SequenceLease second = leases.acquire(2L);

        verify(sorobanRpcClient).getAccountSequence(account.getAccountId());
        assertThat(second.sequenceNumber()).isEqualTo(rewound + 1).isLessThan(first.sequenceNumber());
    }

    /**
     * And the negative: a <em>consumed</em> release must not re-read. The
     * transaction reached a ledger, so the local counter is right by
     * construction, and an extra round trip per transaction on the hot path is
     * a cost with nothing to buy.
     */
    @Test
    void aConsumedReleaseCostsNoNetworkRoundTrip() {
        ChannelAccount account = registerPool(1).get(0);

        SequenceLease first = leases.acquire(1L);
        leases.release(first.channelAccountId(), true);
        reset(sorobanRpcClient);

        SequenceLease second = leases.acquire(2L);

        verify(sorobanRpcClient, never()).getAccountSequence(anyString());
        assertThat(second.sequenceNumber()).isEqualTo(first.sequenceNumber() + 1);
        assertThat(account.getId()).isEqualTo(second.channelAccountId());
    }

    // --- operator management -------------------------------------------------

    @Test
    void registeringDerivesTheAccountFromCustodyAndRefusesDuplicates() {
        String keyRef = KEYS.keySet().iterator().next();
        ChannelAccount registered = channelAccountService.register(keyRef);

        assertThat(registered.getAccountId()).isEqualTo(KEYS.get(keyRef).getAccountId());
        assertThat(registered.getStatus()).isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);
        assertThatThrownBy(() -> channelAccountService.register(keyRef))
                .isInstanceOf(com.guildworkman.api.signing.service.ChannelAccountAlreadyRegisteredException.class);
    }

    @Test
    void aLeasedAccountCanBeNeitherDisabledNorResynced() {
        ChannelAccount account = registerPool(1).get(0);
        leases.acquire(1L);

        assertThatThrownBy(() -> channelAccountService.disable(account.getId()))
                .isInstanceOf(ChannelAccountBusyException.class);
        assertThatThrownBy(() -> leases.resync(account.getId()))
                .isInstanceOf(ChannelAccountBusyException.class);
    }

    @Test
    void aDisabledAccountIsSkippedByTheLeaseQuery() {
        List<ChannelAccount> pool = registerPool(2);
        channelAccountService.disable(pool.get(0).getId());

        SequenceLease first = leases.acquire(1L);
        assertThat(first.channelAccountId()).isEqualTo(pool.get(1).getId());

        assertThatThrownBy(() -> leases.acquire(2L))
                .isInstanceOf(NoChannelAccountAvailableException.class);
    }

    @Test
    void reEnablingAnAccountRequiresAResyncBeforeItIsTrustedAgain() {
        ChannelAccount account = registerPool(1).get(0);
        channelAccountService.disable(account.getId());

        assertThat(channelAccountService.enable(account.getId()).getStatus())
                .isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);
    }

    /** Backdates the lease so the sweeper considers it expired, without sleeping. */
    private void expireLease(Long channelAccountId) {
        ChannelAccount account = channelAccounts.findById(channelAccountId).orElseThrow();
        account.setLeaseExpiresAt(Instant.now().minusSeconds(60));
        channelAccounts.saveAndFlush(account);
    }
}
