package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.StellarTestFixtures;
import com.guildworkman.api.signing.model.SubmissionFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.stellar.sdk.xdr.TransactionResultCode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping this whole feature's recovery logic hangs off: a
 * {@code txBAD_SEQ} that gets retried unchanged reproduces itself forever, and
 * a {@code txINSUFFICIENT_FEE} that gets treated as fatal throws away a
 * transaction a fee bump would have landed.
 */
class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @ParameterizedTest
    @CsvSource({
            "txBAD_SEQ,             BAD_SEQUENCE",
            "txINSUFFICIENT_FEE,    INSUFFICIENT_FEE",
            "txTOO_LATE,            TOO_LATE",
            "txTOO_EARLY,           TOO_LATE",
            "txMALFORMED,           MALFORMED",
            "txMISSING_OPERATION,   MALFORMED",
            "txSOROBAN_INVALID,     MALFORMED",
            "txNOT_SUPPORTED,       MALFORMED",
            "txBAD_AUTH,            BAD_AUTH",
            "txBAD_AUTH_EXTRA,      BAD_AUTH",
            "txINSUFFICIENT_BALANCE,INSUFFICIENT_BALANCE",
            "txNO_ACCOUNT,          INSUFFICIENT_BALANCE",
            "txFAILED,              ON_CHAIN_FAILED",
            "txINTERNAL_ERROR,      UNKNOWN",
            "txBAD_SPONSORSHIP,     UNKNOWN"
    })
    void classifiesEachResultCode(String code, SubmissionFailureReason expected) {
        String resultXdr = StellarTestFixtures.transactionResultXdr(TransactionResultCode.valueOf(code));

        assertThat(classifier.classify(resultXdr)).isEqualTo(expected);
        assertThat(classifier.resultCodeName(resultXdr)).isEqualTo(code);
    }

    /**
     * An undecodable blob degrades to bounded retries rather than crashing a
     * worker — a code from a future protocol version shouldn't take the
     * pipeline down.
     */
    @Test
    void undecodableResultsAreUnknownRatherThanFatal() {
        assertThat(classifier.classify(null)).isEqualTo(SubmissionFailureReason.UNKNOWN);
        assertThat(classifier.classify("")).isEqualTo(SubmissionFailureReason.UNKNOWN);
        assertThat(classifier.classify("not-valid-xdr")).isEqualTo(SubmissionFailureReason.UNKNOWN);

        assertThat(classifier.resultCodeName(null)).isNull();
        assertThat(classifier.resultCodeName("not-valid-xdr")).isNull();
    }

    // --- the four failure classes the issue calls out, one test each --------

    /**
     * {@code tx_bad_seq} is the one that can double-submit if handled lazily.
     * REBUILD is not just "try again": it discards the envelope, releases the
     * channel account unconsumed (so the next lease re-reads the chain) and
     * signs a new transaction with a new number. A plain RETRY would resend
     * the identical envelope against the very number the network just rejected
     * — and, if the original were in fact in the mempool, would leave two
     * envelopes in flight for one intended operation.
     */
    @Test
    void aBadSequenceIsRebuilt_neverRetriedAsIs() {
        SubmissionFailureReason reason =
                classifier.classify(StellarTestFixtures.transactionResultXdr(TransactionResultCode.txBAD_SEQ));

        assertThat(reason).isEqualTo(SubmissionFailureReason.BAD_SEQUENCE);
        assertThat(classifier.recoveryFor(reason))
                .isEqualTo(RecoveryAction.REBUILD)
                .isNotEqualTo(RecoveryAction.RETRY);
    }

    /** {@code tx_insufficient_fee} is recoverable and only by paying more — never terminal. */
    @Test
    void anInsufficientFeeIsFeeBumped() {
        SubmissionFailureReason reason = classifier.classify(
                StellarTestFixtures.transactionResultXdr(TransactionResultCode.txINSUFFICIENT_FEE));

        assertThat(reason).isEqualTo(SubmissionFailureReason.INSUFFICIENT_FEE);
        assertThat(classifier.recoveryFor(reason))
                .isEqualTo(RecoveryAction.FEE_BUMP)
                .isNotEqualTo(RecoveryAction.TERMINAL);
    }

    /**
     * {@code tx_too_late} means the time bounds closed. The envelope can never
     * be included now, so resending it is pointless — but the caller's
     * operations are still perfectly valid, so giving up would be wrong too.
     */
    @Test
    void aTooLateTransactionIsRebuiltWithFreshTimeBounds() {
        SubmissionFailureReason reason =
                classifier.classify(StellarTestFixtures.transactionResultXdr(TransactionResultCode.txTOO_LATE));

        assertThat(reason).isEqualTo(SubmissionFailureReason.TOO_LATE);
        assertThat(classifier.recoveryFor(reason)).isEqualTo(RecoveryAction.REBUILD);
    }

    /**
     * A simulation failure has no result XDR — it never reached the network —
     * so it is set directly rather than classified. It is terminal because the
     * contract call itself cannot succeed: retrying spends a fee and a
     * sequence number to be told what simulation said for free.
     */
    @Test
    void aFailedSimulationIsTerminal() {
        assertThat(classifier.recoveryFor(SubmissionFailureReason.SIMULATION_FAILED))
                .isEqualTo(RecoveryAction.TERMINAL);
    }

    /**
     * Exhaustive, so the mapping is a specification rather than a description.
     * The dangerous direction is a new reason silently defaulting to RETRY —
     * for a transaction that may already be on the network, retrying is the
     * one answer that can do damage — so {@code recoveryFor} has no
     * {@code default} arm and this pins every constant's answer.
     */
    @ParameterizedTest
    @CsvSource({
            "NONE,                 RETRY",
            "SIMULATION_FAILED,    TERMINAL",
            "BAD_SEQUENCE,         REBUILD",
            "INSUFFICIENT_FEE,     FEE_BUMP",
            "TOO_LATE,             REBUILD",
            "FEE_CEILING_REACHED,  TERMINAL",
            "MALFORMED,            TERMINAL",
            "BAD_AUTH,             TERMINAL",
            "INSUFFICIENT_BALANCE, TERMINAL",
            "ON_CHAIN_FAILED,      TERMINAL",
            "NO_CHANNEL_ACCOUNT,   RETRY",
            "SIGNING_FAILED,       RETRY",
            "RPC_ERROR,            RETRY",
            "UNKNOWN,              RETRY"
    })
    void everyFailureReasonHasADeclaredRecovery(SubmissionFailureReason reason, RecoveryAction expected) {
        assertThat(classifier.recoveryFor(reason)).isEqualTo(expected);
    }

    /** Guards the table above against a reason added to the enum but not to the CsvSource. */
    @Test
    void theRecoveryTableCoversEveryFailureReason() {
        assertThat(SubmissionFailureReason.values()).hasSize(14);
        for (SubmissionFailureReason reason : SubmissionFailureReason.values()) {
            assertThat(classifier.recoveryFor(reason))
                    .withFailMessage("no recovery declared for %s", reason)
                    .isNotNull();
        }
    }
}
