package com.guildworkman.api.signing.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.signing.StellarTestFixtures;
import com.guildworkman.api.signing.api.TransactionSubmissionResponse;
import org.junit.jupiter.api.Test;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.Transaction;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Belt and braces on what can leave a submission row.
 *
 * <p>The API answers from {@link TransactionSubmissionResponse} and never
 * serializes an entity, so today none of this can happen. These tests exist
 * for the day it can: someone returns an entity straight from a controller,
 * or interpolates one into a log line. Signatures and envelope contents must
 * not ride along when they do.
 */
class SubmissionEntityExposureTest {

    /** Modules discovered the way Spring Boot's own mapper discovers them, so this serializes like the API would. */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private static TransactionSubmission signedSubmission() {
        Transaction transaction = StellarTestFixtures.unsignedTransaction(1);
        KeyPair signer = StellarTestFixtures.randomKeyPair();
        transaction.sign(signer);

        TransactionSubmission submission = new TransactionSubmission();
        submission.setId(7L);
        submission.setIdempotencyKey("key-7");
        submission.setReference("appointment-42");
        submission.setUnsignedTransactionXdr(StellarTestFixtures.unsignedEnvelope());
        submission.setSignedEnvelopeXdr(transaction.toEnvelopeXdrBase64());
        submission.setResultXdr(StellarTestFixtures.transactionResultXdr(
                org.stellar.sdk.xdr.TransactionResultCode.txFAILED));
        submission.setTransactionHash(transaction.hashHex());
        submission.setSourceAccount(signer.getAccountId());
        submission.setSequenceNumber(99L);
        submission.setStatus(SubmissionStatus.SIGNED);
        return submission;
    }

    @Test
    void serializingASubmissionOmitsEveryEnvelopeAndResultBlob() throws Exception {
        TransactionSubmission submission = signedSubmission();

        String json = objectMapper.writeValueAsString(submission);

        assertThat(json)
                .doesNotContain(submission.getSignedEnvelopeXdr())
                .doesNotContain(submission.getUnsignedTransactionXdr())
                .doesNotContain(submission.getResultXdr())
                .doesNotContain("signedEnvelopeXdr", "unsignedTransactionXdr", "resultXdr");
        // The bookkeeping a caller legitimately needs is still there.
        assertThat(json).contains("\"transactionHash\"", "\"status\"", "\"sequenceNumber\"");
    }

    @Test
    void aSubmissionRendersOnlyIdentifiersInItsToString() {
        TransactionSubmission submission = signedSubmission();

        String rendered = submission.toString();

        assertThat(rendered)
                .doesNotContain(submission.getSignedEnvelopeXdr())
                .doesNotContain(submission.getUnsignedTransactionXdr())
                .doesNotContain(submission.getResultXdr());
        assertThat(rendered).contains("id=7", "status=SIGNED", submission.getTransactionHash());
    }

    @Test
    void aChannelAccountRendersOnlyPublicInformation() {
        ChannelAccount account = new ChannelAccount();
        account.setId(3L);
        account.setAccountId(StellarTestFixtures.randomKeyPair().getAccountId());
        account.setKeyRef("channel1");
        account.setStatus(ChannelAccountStatus.LEASED);
        account.setNextSequence(1234L);

        assertThat(account.toString())
                .contains("id=3", "keyRef=channel1", "status=LEASED", "nextSequence=1234")
                .contains(account.getAccountId());
    }

    /**
     * The public view is a record, so its component list <em>is</em> its
     * contract — a component named after an envelope would put one on the
     * wire. Checked by shape rather than by example so it holds for whatever
     * the record grows into.
     */
    @Test
    void thePublicResponseHasNoComponentThatCouldCarryAnEnvelopeOrAKey() {
        assertThat(Arrays.stream(TransactionSubmissionResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> {
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    return lower.contains("xdr") || lower.contains("envelope") || lower.contains("signature")
                            || lower.contains("secret") || lower.contains("seed");
                })
                .toList())
                .isEmpty();
    }
}
