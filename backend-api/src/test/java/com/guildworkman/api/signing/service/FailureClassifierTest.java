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
}
