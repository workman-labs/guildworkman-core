package com.guildworkman.api.signing;

import com.guildworkman.api.escrow.rpc.GetTransactionResult;
import com.guildworkman.api.escrow.rpc.SendTransactionResult;
import com.guildworkman.api.escrow.rpc.SimulateTransactionResult;
import com.guildworkman.api.escrow.rpc.SorobanRpcClient;
import com.guildworkman.api.escrow.rpc.SorobanRpcException;
import com.guildworkman.api.signing.api.SubmitTransactionRequest;
import com.guildworkman.api.signing.model.ChannelAccount;
import com.guildworkman.api.signing.model.ChannelAccountStatus;
import com.guildworkman.api.signing.model.SubmissionFailureReason;
import com.guildworkman.api.signing.model.SubmissionStatus;
import com.guildworkman.api.signing.model.TransactionSubmission;
import com.guildworkman.api.signing.repository.ChannelAccountRepository;
import com.guildworkman.api.signing.repository.TransactionSubmissionRepository;
import com.guildworkman.api.signing.service.ChannelAccountService;
import com.guildworkman.api.signing.service.SubmissionNotFoundException;
import com.guildworkman.api.signing.service.TransactionAssemblyException;
import com.guildworkman.api.signing.service.TransactionSubmissionService;
import com.guildworkman.api.signing.custody.UnknownKeyReferenceException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.stellar.sdk.AbstractTransaction;
import org.stellar.sdk.FeeBumpTransaction;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.Network;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.xdr.TransactionResultCode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The submission pipeline end to end, against real PostgreSQL and a stubbed
 * Soroban RPC.
 *
 * <p>The workers are driven by hand rather than by their schedulers, so each
 * test states exactly which phase it is exercising and the assertions are
 * about state transitions rather than about timing.
 */
@SpringBootTest(properties = {
        "stellar.signing.prepare-poll-delay-ms=3600000",
        "stellar.signing.broadcast-poll-delay-ms=3600000",
        "stellar.signing.confirm-poll-delay-ms=3600000",
        "stellar.signing.lease-sweep-delay-ms=3600000",
        "stellar.signing.fee.base-stroops=100",
        "stellar.signing.fee.max-total-stroops=1000",
        "stellar.signing.retry.max-attempts=3",
        "stellar.signing.retry.base-delay=PT0.001S",
        "stellar.signing.retry.max-delay=PT0.002S",
        "stellar.signing.stall-after=PT30S",
        "chain.events.poll-delay-ms=3600000",
        "escrow.orchestration.submit-poll-delay-ms=3600000",
        "escrow.orchestration.confirm-poll-delay-ms=3600000",
        "escrow.reconciliation.poll-delay-ms=3600000",
        "booking.expiry-sweep-delay-ms=3600000",
        "spring.task.scheduling.enabled=false"
})
class TransactionSubmissionIntegrationTest {

    private static final Map<String, KeyPair> KEYS = new LinkedHashMap<>();
    private static final long ON_CHAIN_SEQUENCE = 5_000L;

    @DynamicPropertySource
    static void localSigningKeys(DynamicPropertyRegistry registry) {
        for (int i = 0; i < 2; i++) {
            String keyRef = "pipeline" + i;
            KeyPair keyPair = KeyPair.random();
            KEYS.put(keyRef, keyPair);
            registry.add("stellar.signing.local.keys." + keyRef, () -> String.valueOf(keyPair.getSecretSeed()));
        }
    }

    @Autowired
    private TransactionSubmissionService service;

    @Autowired
    private ChannelAccountService channelAccountService;

    @Autowired
    private TransactionSubmissionRepository submissions;

    @Autowired
    private ChannelAccountRepository channelAccounts;

    @Autowired
    private SigningProperties properties;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private SorobanRpcClient rpc;

    @BeforeEach
    void cleanSlate() {
        reset(rpc);
        submissions.deleteAll();
        channelAccounts.deleteAll();
        when(rpc.getAccountSequence(anyString())).thenReturn(ON_CHAIN_SEQUENCE);
        KEYS.keySet().forEach(channelAccountService::register);
    }

    // --- helpers ------------------------------------------------------------

    private static SubmitTransactionRequest request(String envelopeXdr) {
        return new SubmitTransactionRequest("idem-" + UUID.randomUUID(), "ref-1", envelopeXdr, null);
    }

    private TransactionSubmission submitAndPrepare() {
        Long id = service.submit(request(StellarTestFixtures.unsignedEnvelope())).submission().getId();
        service.preparePending();
        return reload(id);
    }

    private TransactionSubmission reload(Long id) {
        return submissions.findById(id).orElseThrow();
    }

    private static GetTransactionResult notFound() {
        return new GetTransactionResult("NOT_FOUND", null, null);
    }

    private static GetTransactionResult success(long ledger) {
        return new GetTransactionResult("SUCCESS",
                StellarTestFixtures.transactionResultXdr(TransactionResultCode.txSUCCESS), ledger);
    }

    private static SendTransactionResult rejectedWith(TransactionResultCode code) {
        return new SendTransactionResult(null, "ERROR", StellarTestFixtures.transactionResultXdr(code));
    }

    private ChannelAccountStatus statusOf(Long channelAccountId) {
        return channelAccounts.findById(channelAccountId).orElseThrow().getStatus();
    }

    /** Backdates the row's timestamps so a phase-3 poll sees a stall or an expiry, without sleeping. */
    private TransactionSubmission backdate(TransactionSubmission entity, Instant broadcastAt, Instant validUntil) {
        entity.setBroadcastAt(broadcastAt);
        entity.setValidUntil(validUntil);
        entity.setNextAttemptAt(Instant.now().minusSeconds(1));
        return submissions.saveAndFlush(entity);
    }

    // --- submit -------------------------------------------------------------

    @Test
    void submitStoresAPendingRequestAndIsIdempotent() {
        SubmitTransactionRequest request = request(StellarTestFixtures.unsignedEnvelope());

        var first = service.submit(request);
        var second = service.submit(request);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.submission().getId()).isEqualTo(first.submission().getId());
        assertThat(reload(first.submission().getId()).getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(submissions.count()).isEqualTo(1);
    }

    /**
     * Validation that needs the envelope's contents happens on the caller's own
     * request. Deferring it would turn a client mistake into a dead-lettered
     * row discovered minutes later by nobody.
     */
    @Test
    void submitRejectsAnUnusableEnvelopeSynchronously() {
        assertThatThrownBy(() -> service.submit(request("AAAAsomethingthatisnotanenvelope")))
                .isInstanceOf(TransactionAssemblyException.class);
        assertThat(submissions.count()).isZero();
    }

    @Test
    void submitRejectsAnUnknownExtraSignerReferenceSynchronously() {
        SubmitTransactionRequest request = new SubmitTransactionRequest(
                "idem-" + UUID.randomUUID(), null, StellarTestFixtures.unsignedEnvelope(), List.of("not-a-key"));

        assertThatThrownBy(() -> service.submit(request))
                .isInstanceOf(UnknownKeyReferenceException.class);
        assertThat(submissions.count()).isZero();
    }

    @Test
    void submissionsAreRetrievableByIdAndByCallerReference() {
        Long id = service.submit(request(StellarTestFixtures.unsignedEnvelope())).submission().getId();

        assertThat(service.get(id).getReference()).isEqualTo("ref-1");
        assertThat(service.findByReference("ref-1")).extracting(TransactionSubmission::getId).containsExactly(id);
        assertThatThrownBy(() -> service.get(id + 9999)).isInstanceOf(SubmissionNotFoundException.class);
    }

    // --- phase 1: prepare ---------------------------------------------------

    @Test
    void preparingSignsTheTransactionOntoALeasedChannelAccount() {
        TransactionSubmission prepared = submitAndPrepare();

        assertThat(prepared.getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        assertThat(prepared.getSequenceNumber()).isEqualTo(ON_CHAIN_SEQUENCE + 1);
        assertThat(prepared.getSigningProvider()).isEqualTo("local");
        assertThat(prepared.getTransactionHash()).isNotBlank();
        assertThat(prepared.getValidUntil()).isAfter(Instant.now());
        assertThat(statusOf(prepared.getChannelAccountId())).isEqualTo(ChannelAccountStatus.LEASED);

        // The signed envelope really carries the leased account's sequence,
        // really is signed, and the signature really is the channel key's.
        Transaction signed = (Transaction) AbstractTransaction.fromEnvelopeXdr(
                prepared.getSignedEnvelopeXdr(), new Network(properties.getNetworkPassphrase()));
        assertThat(signed.getSequenceNumber()).isEqualTo(ON_CHAIN_SEQUENCE + 1);
        assertThat(signed.getSourceAccount()).isEqualTo(prepared.getSourceAccount());
        assertThat(signed.getSignatures()).hasSize(1);
        assertThat(KeyPair.fromAccountId(prepared.getSourceAccount())
                .verify(signed.hash(), signed.getSignatures().get(0).getSignature().getSignature())).isTrue();
    }

    /** No secret ever reaches the row, whatever else it records. */
    @Test
    void nothingPersistedLooksLikeKeyMaterial() {
        TransactionSubmission prepared = submitAndPrepare();

        assertThat(prepared.getKeyRef()).isIn(KEYS.keySet());
        assertThat(com.guildworkman.api.signing.custody.SecretRedactor
                .containsSecret(prepared.getSignedEnvelopeXdr())).isFalse();
        assertThat(com.guildworkman.api.signing.custody.SecretRedactor
                .containsSecret(prepared.toString())).isFalse();
    }

    @Test
    void anEmptyPoolLeavesTheSubmissionPendingForARetry() {
        channelAccounts.deleteAll();
        Long id = service.submit(request(StellarTestFixtures.unsignedEnvelope())).submission().getId();

        service.preparePending();

        TransactionSubmission retried = reload(id);
        assertThat(retried.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(retried.getFailureReason()).isEqualTo(SubmissionFailureReason.NO_CHANNEL_ACCOUNT);
        assertThat(retried.getAttempts()).isEqualTo(1);
        assertThat(retried.getNextAttemptAt()).isAfter(Instant.now().minusSeconds(1));
    }

    /**
     * A contract call that simulation says cannot succeed must never be signed:
     * submitting it would burn a fee and a sequence number to learn what
     * simulation just reported for free.
     */
    @Test
    void aFailedSimulationIsTerminalAndNeverBroadcast() {
        when(rpc.simulateTransaction(anyString()))
                .thenReturn(new SimulateTransactionResult("HostError: contract panicked", null, null, false));
        Long id = service.submit(request(StellarTestFixtures.unsignedSorobanEnvelope())).submission().getId();

        service.preparePending();

        TransactionSubmission failed = reload(id);
        assertThat(failed.getStatus()).isEqualTo(SubmissionStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(SubmissionFailureReason.SIMULATION_FAILED);
        assertThat(failed.getLastError()).contains("contract panicked");
        assertThat(failed.getSignedEnvelopeXdr()).isNull();
        verify(rpc, never()).sendTransaction(anyString());
        // Nothing landed, so the sequence it was given has to be re-read.
        assertThat(channelAccounts.countByStatus(ChannelAccountStatus.NEEDS_RESYNC)).isEqualTo(KEYS.size());
    }

    @Test
    void aSorobanTransactionIsSimulatedAndCarriesTheResourceFee() {
        when(rpc.simulateTransaction(anyString()))
                .thenReturn(new SimulateTransactionResult(null, 400L,
                        StellarTestFixtures.sorobanTransactionData(400L), false));
        Long id = service.submit(request(StellarTestFixtures.unsignedSorobanEnvelope())).submission().getId();

        service.preparePending();

        TransactionSubmission prepared = reload(id);
        assertThat(prepared.getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        // 100 stroops inclusion fee for the single operation, plus the 400
        // stroop resource fee simulation reported.
        assertThat(prepared.getFeeStroops()).isEqualTo(500L);
    }

    /** The ceiling is checked before signing as well as before each bump. */
    @Test
    void aTransactionThatAlreadyExceedsTheFeeCeilingIsRefused() {
        when(rpc.simulateTransaction(anyString()))
                .thenReturn(new SimulateTransactionResult(null, 50_000L,
                        StellarTestFixtures.sorobanTransactionData(50_000L), false));
        Long id = service.submit(request(StellarTestFixtures.unsignedSorobanEnvelope())).submission().getId();

        service.preparePending();

        TransactionSubmission failed = reload(id);
        assertThat(failed.getStatus()).isEqualTo(SubmissionStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(SubmissionFailureReason.FEE_CEILING_REACHED);
    }

    // --- phase 2: broadcast -------------------------------------------------

    @Test
    void broadcastSendsTheSignedEnvelopeAndRecordsTheHash() {
        TransactionSubmission prepared = submitAndPrepare();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        when(rpc.sendTransaction(anyString()))
                .thenReturn(new SendTransactionResult(prepared.getTransactionHash(), "PENDING", null));

        service.broadcastSigned();

        TransactionSubmission broadcast = reload(prepared.getId());
        assertThat(broadcast.getStatus()).isEqualTo(SubmissionStatus.BROADCAST);
        assertThat(broadcast.getBroadcastAt()).isNotNull();
        verify(rpc).sendTransaction(prepared.getSignedEnvelopeXdr());
    }

    /**
     * Restart safety. The envelope is durable before it is sent, so a process
     * that died mid-broadcast comes back holding the hash it may have already
     * submitted — and asks about it rather than signing something new.
     */
    @Test
    void aTransactionThatLandedWhileWeWereAwayIsConfirmedRatherThanResent() {
        TransactionSubmission prepared = submitAndPrepare();
        when(rpc.getTransaction(prepared.getTransactionHash())).thenReturn(success(4242L));

        service.broadcastSigned();

        TransactionSubmission confirmed = reload(prepared.getId());
        assertThat(confirmed.getStatus()).isEqualTo(SubmissionStatus.CONFIRMED);
        assertThat(confirmed.getLedgerSequence()).isEqualTo(4242L);
        verify(rpc, never()).sendTransaction(anyString());
        // It reached a ledger, so the sequence number was genuinely spent.
        assertThat(statusOf(confirmed.getChannelAccountId())).isEqualTo(ChannelAccountStatus.AVAILABLE);
    }

    @Test
    void aBusyRpcIsRetriedRatherThanFailed() {
        TransactionSubmission prepared = submitAndPrepare();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        when(rpc.sendTransaction(anyString()))
                .thenReturn(new SendTransactionResult(null, "TRY_AGAIN_LATER", null));

        service.broadcastSigned();

        TransactionSubmission retried = reload(prepared.getId());
        assertThat(retried.getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        assertThat(retried.getFailureReason()).isEqualTo(SubmissionFailureReason.RPC_ERROR);
        assertThat(retried.getAttempts()).isEqualTo(1);
    }

    @Test
    void anRpcOutageIsRetriedRatherThanFailed() {
        TransactionSubmission prepared = submitAndPrepare();
        when(rpc.getTransaction(anyString())).thenThrow(new SorobanRpcException("connection reset"));

        service.broadcastSigned();

        assertThat(reload(prepared.getId()).getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        assertThat(reload(prepared.getId()).getFailureReason()).isEqualTo(SubmissionFailureReason.RPC_ERROR);
    }

    // --- failure classification ---------------------------------------------

    /**
     * The classic trap: retrying a {@code txBAD_SEQ} unchanged reproduces it
     * forever. The envelope has to be discarded and the account re-read from
     * the chain before anything is signed again.
     */
    @Test
    void aBadSequenceRebuildsAfterResyncingTheAccount() {
        TransactionSubmission prepared = submitAndPrepare();
        Long channelAccountId = prepared.getChannelAccountId();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        when(rpc.sendTransaction(anyString())).thenReturn(rejectedWith(TransactionResultCode.txBAD_SEQ));

        service.broadcastSigned();

        TransactionSubmission rebuilt = reload(prepared.getId());
        assertThat(rebuilt.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(rebuilt.getFailureReason()).isEqualTo(SubmissionFailureReason.BAD_SEQUENCE);
        assertThat(rebuilt.getSignedEnvelopeXdr()).isNull();
        assertThat(rebuilt.getTransactionHash()).isNull();
        assertThat(rebuilt.getSequenceNumber()).isNull();
        assertThat(rebuilt.getAttempts()).isEqualTo(1);
        assertThat(statusOf(channelAccountId)).isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);

        // And the rebuild really does re-read the chain rather than reusing the
        // local counter, which is what stops the failure repeating.
        when(rpc.getAccountSequence(anyString())).thenReturn(ON_CHAIN_SEQUENCE + 7);
        service.preparePending();
        assertThat(reload(prepared.getId()).getSequenceNumber()).isEqualTo(ON_CHAIN_SEQUENCE + 8);
    }

    @Test
    void aLapsedValidityWindowRebuildsRatherThanRetryingTheSameEnvelope() {
        TransactionSubmission prepared = submitAndPrepare();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        when(rpc.sendTransaction(anyString())).thenReturn(rejectedWith(TransactionResultCode.txTOO_LATE));

        service.broadcastSigned();

        TransactionSubmission rebuilt = reload(prepared.getId());
        assertThat(rebuilt.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(rebuilt.getFailureReason()).isEqualTo(SubmissionFailureReason.TOO_LATE);
        assertThat(rebuilt.getSignedEnvelopeXdr()).isNull();
    }

    @Test
    void anUnauthorisedOrMalformedTransactionIsTerminal() {
        TransactionSubmission prepared = submitAndPrepare();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        when(rpc.sendTransaction(anyString())).thenReturn(rejectedWith(TransactionResultCode.txBAD_AUTH));

        service.broadcastSigned();

        TransactionSubmission failed = reload(prepared.getId());
        assertThat(failed.getStatus()).isEqualTo(SubmissionStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(SubmissionFailureReason.BAD_AUTH);
        assertThat(failed.getLastError()).contains("txBAD_AUTH");
    }

    @Test
    void anInsufficientFeeGoesStraightToAFeeBump() {
        TransactionSubmission prepared = submitAndPrepare();
        String originalHash = prepared.getTransactionHash();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        when(rpc.sendTransaction(anyString())).thenReturn(rejectedWith(TransactionResultCode.txINSUFFICIENT_FEE));

        service.broadcastSigned();

        TransactionSubmission bumped = reload(prepared.getId());
        assertThat(bumped.getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        assertThat(bumped.getFeeBumpCount()).isEqualTo(1);
        assertThat(bumped.getFeeStroops()).isEqualTo(200L);
        assertThat(bumped.getTransactionHash()).isNotEqualTo(originalHash);
        assertThat(bumped.getInnerTransactionHash()).isEqualTo(originalHash);
    }

    // --- phase 3: poll, stall, fee bump -------------------------------------

    @Test
    void aTransactionIncludedInALedgerIsConfirmedAndReleasesItsAccount() {
        TransactionSubmission broadcast = broadcastOne();
        when(rpc.getTransaction(anyString())).thenReturn(success(777L));

        service.pollBroadcast();

        TransactionSubmission confirmed = reload(broadcast.getId());
        assertThat(confirmed.getStatus()).isEqualTo(SubmissionStatus.CONFIRMED);
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThat(confirmed.getLedgerSequence()).isEqualTo(777L);
        assertThat(statusOf(confirmed.getChannelAccountId())).isEqualTo(ChannelAccountStatus.AVAILABLE);
    }

    /** Included and failed there: terminal, because the sequence number is spent either way. */
    @Test
    void aTransactionThatFailsOnChainIsTerminalAndTheSequenceCounts() {
        TransactionSubmission broadcast = broadcastOne();
        when(rpc.getTransaction(anyString())).thenReturn(new GetTransactionResult("FAILED",
                StellarTestFixtures.transactionResultXdr(TransactionResultCode.txFAILED), 778L));

        service.pollBroadcast();

        TransactionSubmission failed = reload(broadcast.getId());
        assertThat(failed.getStatus()).isEqualTo(SubmissionStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo(SubmissionFailureReason.ON_CHAIN_FAILED);
        assertThat(statusOf(failed.getChannelAccountId())).isEqualTo(ChannelAccountStatus.AVAILABLE);
    }

    @Test
    void aTransactionStalledInTheMempoolIsFeeBumped() {
        TransactionSubmission broadcast = broadcastOne();
        String originalHash = broadcast.getTransactionHash();
        backdate(broadcast, Instant.now().minusSeconds(120), Instant.now().plusSeconds(600));
        when(rpc.getTransaction(anyString())).thenReturn(notFound());

        service.pollBroadcast();

        TransactionSubmission bumped = reload(broadcast.getId());
        assertThat(bumped.getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        assertThat(bumped.getFeeBumpCount()).isEqualTo(1);
        assertThat(bumped.getFeeStroops()).isEqualTo(200L);
        assertThat(bumped.getLastError()).contains("Stalled");

        // The bump really is a fee bump wrapping the original transaction.
        AbstractTransaction envelope = AbstractTransaction.fromEnvelopeXdr(
                bumped.getSignedEnvelopeXdr(), new Network(properties.getNetworkPassphrase()));
        assertThat(envelope).isInstanceOf(FeeBumpTransaction.class);
        assertThat(((FeeBumpTransaction) envelope).getInnerTransaction().hashHex()).isEqualTo(originalHash);
    }

    /**
     * The termination proof. Bumps double the fee, and the first that would
     * cross the ceiling ends the submission — so "stalled" is a bounded state,
     * not an open-ended one, and no amount of congestion turns a retry loop
     * into unbounded spend.
     */
    @Test
    void feeBumpsStopAtTheCeilingRatherThanEscalatingForever() {
        TransactionSubmission broadcast = broadcastOne();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());

        long ceiling = properties.getFee().getMaxTotalStroops();
        int bumps = 0;
        TransactionSubmission current = broadcast;
        while (current.getStatus() != SubmissionStatus.FAILED && bumps < 20) {
            // Put it back in flight so the next poll sees a stall again.
            current.setStatus(SubmissionStatus.BROADCAST);
            backdate(current, Instant.now().minusSeconds(120), Instant.now().plusSeconds(600));
            service.pollBroadcast();
            current = reload(broadcast.getId());
            bumps++;
            assertThat(current.getFeeStroops()).isLessThanOrEqualTo(ceiling);
        }

        assertThat(current.getStatus()).isEqualTo(SubmissionStatus.FAILED);
        assertThat(current.getFailureReason()).isEqualTo(SubmissionFailureReason.FEE_CEILING_REACHED);
        assertThat(statusOf(broadcast.getChannelAccountId())).isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);
    }

    /**
     * Past its time bounds the network can never include it, so there is no
     * verdict left to wait for — polling it forever would be the bug.
     */
    @Test
    void anExpiredTransactionIsRebuiltRatherThanPolledForever() {
        TransactionSubmission broadcast = broadcastOne();
        backdate(broadcast, Instant.now().minusSeconds(300), Instant.now().minusSeconds(10));
        when(rpc.getTransaction(anyString())).thenReturn(notFound());

        service.pollBroadcast();

        TransactionSubmission rebuilt = reload(broadcast.getId());
        assertThat(rebuilt.getStatus()).isEqualTo(SubmissionStatus.PENDING);
        assertThat(rebuilt.getFailureReason()).isEqualTo(SubmissionFailureReason.TOO_LATE);
        assertThat(rebuilt.getFeeBumpCount()).isZero();
    }

    /**
     * After a bump two hashes are in flight and either may land. Missing that
     * would leave a confirmed transaction stuck in {@code BROADCAST} and its
     * channel account leased.
     */
    @Test
    void aTransactionThatLandedAsItsPreBumpEnvelopeIsStillRecognised() {
        TransactionSubmission broadcast = broadcastOne();
        String innerHash = broadcast.getTransactionHash();
        backdate(broadcast, Instant.now().minusSeconds(120), Instant.now().plusSeconds(600));
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        service.pollBroadcast();

        TransactionSubmission bumped = reload(broadcast.getId());
        bumped.setStatus(SubmissionStatus.BROADCAST);
        backdate(bumped, Instant.now(), Instant.now().plusSeconds(600));

        when(rpc.getTransaction(bumped.getTransactionHash())).thenReturn(notFound());
        when(rpc.getTransaction(innerHash)).thenReturn(success(999L));

        service.pollBroadcast();

        TransactionSubmission confirmed = reload(broadcast.getId());
        assertThat(confirmed.getStatus()).isEqualTo(SubmissionStatus.CONFIRMED);
        assertThat(confirmed.getLedgerSequence()).isEqualTo(999L);
    }

    // --- bounded retries ----------------------------------------------------

    /** Nothing retries forever: exhausting the budget hands the row to an operator. */
    @Test
    void repeatedFailuresEndInTheDeadLetterState() {
        TransactionSubmission prepared = submitAndPrepare();
        when(rpc.getTransaction(anyString())).thenThrow(new SorobanRpcException("rpc down"));

        for (int i = 0; i < properties.getRetry().getMaxAttempts() + 1; i++) {
            TransactionSubmission current = reload(prepared.getId());
            current.setNextAttemptAt(Instant.now().minusSeconds(1));
            submissions.saveAndFlush(current);
            service.broadcastSigned();
        }

        TransactionSubmission dead = reload(prepared.getId());
        assertThat(dead.getStatus()).isEqualTo(SubmissionStatus.DEAD_LETTER);
        assertThat(dead.getAttempts()).isEqualTo(properties.getRetry().getMaxAttempts());
        assertThat(statusOf(prepared.getChannelAccountId())).isEqualTo(ChannelAccountStatus.NEEDS_RESYNC);
    }

    /** Workers claim one row at a time, so a stuck submission can't starve the queue. */
    @Test
    void twoSubmissionsGetTwoAccountsAndTwoSequenceNumbers() {
        Long first = service.submit(request(StellarTestFixtures.unsignedEnvelope())).submission().getId();
        Long second = service.submit(request(StellarTestFixtures.unsignedEnvelope())).submission().getId();

        service.preparePending();
        service.preparePending();

        TransactionSubmission a = reload(first);
        TransactionSubmission b = reload(second);
        assertThat(a.getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        assertThat(b.getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        assertThat(a.getChannelAccountId()).isNotEqualTo(b.getChannelAccountId());
        assertThat(a.getSourceAccount()).isNotEqualTo(b.getSourceAccount());
        assertThat(a.getTransactionHash()).isNotEqualTo(b.getTransactionHash());
    }

    private TransactionSubmission broadcastOne() {
        TransactionSubmission prepared = submitAndPrepare();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        when(rpc.sendTransaction(anyString()))
                .thenReturn(new SendTransactionResult(prepared.getTransactionHash(), "PENDING", null));
        service.broadcastSigned();
        reset(rpc);
        when(rpc.getAccountSequence(anyString())).thenReturn(ON_CHAIN_SEQUENCE);
        TransactionSubmission broadcast = reload(prepared.getId());
        assertThat(broadcast.getStatus()).isEqualTo(SubmissionStatus.BROADCAST);
        return broadcast;
    }

    // --- the durability boundary --------------------------------------------

    /**
     * The single ordering rule the whole restart story rests on: <b>the
     * envelope is on disk before it is on the network</b>.
     *
     * <p>Asserted three ways, because "we save before we send" is easy to
     * believe and easy to break. First, phase 1 leaves a committed row —
     * re-read through a fresh {@code EntityManager}, so this is what another
     * process would see, not what this thread has cached — carrying the
     * envelope and the exact hash phase 2 will poll. Second, the RPC client is
     * never touched at all during phase 1, so no send can have raced the
     * commit. Third, phase 2's very first move against that hash is a
     * {@code getTransaction}, not a {@code sendTransaction}.
     *
     * <p>Invert the ordering and this is the failure it prevents: a process
     * that dies between broadcasting and committing comes back with no record
     * of a transaction the network has, signs a second one on a second
     * sequence number, and executes the caller's operations twice.
     */
    @Test
    void theEnvelopeIsCommittedBeforeAnythingIsEverBroadcast() {
        Long id = service.submit(request(StellarTestFixtures.unsignedEnvelope())).submission().getId();

        service.preparePending();

        // What a *different* process would read after phase 1's transaction commits.
        submissions.flush();
        TransactionSubmission committed = reload(id);
        assertThat(committed.getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        assertThat(committed.getSignedEnvelopeXdr()).isNotBlank();
        assertThat(committed.getTransactionHash()).isNotBlank().hasSize(64);
        assertThat(committed.getSignedAt()).isNotNull();

        // Phase 1 never spoke to the network beyond reading the account sequence.
        verify(rpc, never()).sendTransaction(anyString());
        verify(rpc, never()).getTransaction(anyString());

        // The committed hash is exactly what a recovering process would ask about.
        String hash = committed.getTransactionHash();
        assertThat(AbstractTransaction.fromEnvelopeXdr(committed.getSignedEnvelopeXdr(), Network.TESTNET).hashHex())
                .isEqualTo(hash);

        when(rpc.getTransaction(hash)).thenReturn(notFound());
        when(rpc.sendTransaction(anyString())).thenReturn(new SendTransactionResult(hash, "PENDING", null));
        service.broadcastSigned();

        InOrder inOrder = inOrder(rpc);
        inOrder.verify(rpc).getTransaction(hash);
        inOrder.verify(rpc).sendTransaction(committed.getSignedEnvelopeXdr());
    }

    /**
     * The same rule seen from recovery's side. A row left {@code SIGNED} by a
     * process that died is picked up by a later one and asked about before
     * anything is sent — which is what turns "we may have broadcast this" into
     * a question with an answer.
     */
    @Test
    void aSignedRowLeftBehindByACrashedProcessIsAskedAboutBeforeItIsResent() {
        TransactionSubmission prepared = submitAndPrepare();
        String hash = prepared.getTransactionHash();

        // The crashed process had in fact broadcast it; the network says so.
        when(rpc.getTransaction(hash)).thenReturn(success(4242L));

        service.broadcastSigned();

        TransactionSubmission recovered = reload(prepared.getId());
        assertThat(recovered.getStatus()).isEqualTo(SubmissionStatus.CONFIRMED);
        assertThat(recovered.getLedgerSequence()).isEqualTo(4242L);
        verify(rpc, never()).sendTransaction(anyString());
        assertThat(statusOf(recovered.getChannelAccountId())).isEqualTo(ChannelAccountStatus.AVAILABLE);
    }

    // --- operator levers ----------------------------------------------------

    /**
     * The rollback switch. Pausing has to stop work moving without losing any,
     * and resuming has to pick up from the durable row state rather than from
     * the beginning — which it does for free, because pausing changes no state
     * at all.
     */
    @Test
    void pausingStopsTheWorkersWithoutLosingOrDuplicatingWork() {
        properties.setEnabled(false);
        try {
            Long id = service.submit(request(StellarTestFixtures.unsignedEnvelope())).submission().getId();

            service.preparePending();
            service.broadcastSigned();
            service.pollBroadcast();

            // Submissions are still accepted — they just queue.
            TransactionSubmission paused = reload(id);
            assertThat(paused.getStatus()).isEqualTo(SubmissionStatus.PENDING);
            assertThat(paused.getSignedEnvelopeXdr()).isNull();
            assertThat(paused.getAttempts()).isZero();
            verify(rpc, never()).sendTransaction(anyString());
            assertThat(channelAccounts.findAll())
                    .allMatch(account -> account.getStatus() != ChannelAccountStatus.LEASED);

            properties.setEnabled(true);
            service.preparePending();

            assertThat(reload(id).getStatus()).isEqualTo(SubmissionStatus.SIGNED);
        } finally {
            properties.setEnabled(true);
        }
    }

    // --- metrics ------------------------------------------------------------

    /**
     * The counters an operator would alert on. Asserted as deltas rather than
     * absolutes: these are process-wide meters and other tests in this class
     * share the registry.
     */
    @Test
    void thePipelineCountsWhatAnOperatorWouldAlertOn() {
        double submissionsBefore = counter("stellar.signing.submissions", "outcome", "created");
        double replaysBefore = counter("stellar.signing.submissions", "outcome", "replayed");
        double prepareBefore = counter("stellar.signing.phase.attempts", "phase", "prepare");
        double leasesBefore = counter("stellar.signing.leases", "event", "ACQUIRED");
        double confirmedBefore = counter("stellar.signing.terminal", "status", "CONFIRMED");

        SubmitTransactionRequest request = request(StellarTestFixtures.unsignedEnvelope());
        Long id = service.submit(request).submission().getId();
        service.submit(request); // the same idempotency key: a replay
        service.preparePending();
        TransactionSubmission signed = reload(id);
        when(rpc.getTransaction(signed.getTransactionHash())).thenReturn(success(99L));
        service.broadcastSigned();

        assertThat(counter("stellar.signing.submissions", "outcome", "created")).isEqualTo(submissionsBefore + 1);
        assertThat(counter("stellar.signing.submissions", "outcome", "replayed")).isEqualTo(replaysBefore + 1);
        assertThat(counter("stellar.signing.phase.attempts", "phase", "prepare")).isEqualTo(prepareBefore + 1);
        assertThat(counter("stellar.signing.leases", "event", "ACQUIRED")).isEqualTo(leasesBefore + 1);
        assertThat(counter("stellar.signing.terminal", "status", "CONFIRMED")).isEqualTo(confirmedBefore + 1);
    }

    /** A fee bump is the signal that the network is congested — or that the ceiling is about to bite. */
    @Test
    void feeBumpsAndDeadLettersAreCounted() {
        double bumpsBefore = counter("stellar.signing.fee.bumps");
        double ceilingBefore = counter("stellar.signing.terminal", "reason", "FEE_CEILING_REACHED");

        TransactionSubmission broadcast = broadcastOne();
        when(rpc.getTransaction(anyString())).thenReturn(notFound());
        when(rpc.sendTransaction(anyString()))
                .thenReturn(new SendTransactionResult(broadcast.getTransactionHash(), "PENDING", null));
        backdate(broadcast, Instant.now().minusSeconds(120), Instant.now().plusSeconds(600));
        service.pollBroadcast();

        assertThat(counter("stellar.signing.fee.bumps")).isEqualTo(bumpsBefore + 1);
        assertThat(counter("stellar.signing.terminal", "reason", "FEE_CEILING_REACHED")).isEqualTo(ceilingBefore);
    }

    /**
     * Tag cardinality is bounded by the code, not by traffic: nothing a caller
     * supplies may become a tag value, or a busy day turns into a metrics
     * cardinality incident.
     */
    @Test
    void noCallerSuppliedValueBecomesAMetricTag() {
        String reference = "reference-" + UUID.randomUUID();
        service.submit(new SubmitTransactionRequest("idem-" + UUID.randomUUID(), reference,
                StellarTestFixtures.unsignedEnvelope(), null));
        service.preparePending();

        assertThat(meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("stellar.signing"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getValue))
                .doesNotContain(reference)
                .allSatisfy(value -> assertThat(value).doesNotContain("idem-"));
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    /** Sanity: the pool this test class relies on really was registered. */
    @Test
    void theTestPoolIsRegistered() {
        assertThat(channelAccounts.count()).isEqualTo(KEYS.size());
        for (ChannelAccount account : channelAccounts.findAll()) {
            assertThat(account.getAccountId()).isEqualTo(KEYS.get(account.getKeyRef()).getAccountId());
        }
    }
}
