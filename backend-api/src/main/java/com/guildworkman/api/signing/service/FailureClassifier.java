package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.model.SubmissionFailureReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.stellar.sdk.xdr.TransactionResult;
import org.stellar.sdk.xdr.TransactionResultCode;

/**
 * Decodes a {@code TransactionResult} XDR into the failure mode it actually
 * represents, so each one can get the recovery it needs.
 *
 * <p>Soroban RPC reports a rejected transaction as a base64 blob
 * ({@code errorResultXdr} on {@code sendTransaction}, {@code resultXdr} on
 * {@code getTransaction}). Treating that blob as an opaque "it failed" is
 * what produces the two classic bugs this feature is meant to avoid: retrying
 * a {@code txBAD_SEQ} unchanged, which reproduces it forever, and giving up
 * on a {@code txINSUFFICIENT_FEE}, which throws away a transaction a fee bump
 * would have landed.
 *
 * <p>A result we can't decode is {@link SubmissionFailureReason#UNKNOWN}
 * rather than an exception: an unrecognised code from a future protocol
 * version should degrade to bounded retries, not crash a worker.
 */
@Component
public class FailureClassifier {

    private static final Logger log = LoggerFactory.getLogger(FailureClassifier.class);

    /**
     * @param resultXdr base64 {@code TransactionResult} XDR, or {@code null}
     * @return the failure mode, or {@link SubmissionFailureReason#UNKNOWN} if it can't be decoded
     */
    public SubmissionFailureReason classify(String resultXdr) {
        TransactionResultCode code = resultCode(resultXdr);
        if (code == null) {
            return SubmissionFailureReason.UNKNOWN;
        }
        return switch (code) {
            case txBAD_SEQ -> SubmissionFailureReason.BAD_SEQUENCE;
            case txINSUFFICIENT_FEE -> SubmissionFailureReason.INSUFFICIENT_FEE;
            case txTOO_LATE, txTOO_EARLY -> SubmissionFailureReason.TOO_LATE;
            case txMALFORMED, txMISSING_OPERATION, txSOROBAN_INVALID, txNOT_SUPPORTED ->
                    SubmissionFailureReason.MALFORMED;
            case txBAD_AUTH, txBAD_AUTH_EXTRA -> SubmissionFailureReason.BAD_AUTH;
            case txINSUFFICIENT_BALANCE, txNO_ACCOUNT -> SubmissionFailureReason.INSUFFICIENT_BALANCE;
            case txFAILED, txFEE_BUMP_INNER_FAILED -> SubmissionFailureReason.ON_CHAIN_FAILED;
            default -> SubmissionFailureReason.UNKNOWN;
        };
    }

    /** @return the raw result code name for diagnostics (e.g. {@code txBAD_SEQ}), or {@code null} if undecodable. */
    public String resultCodeName(String resultXdr) {
        TransactionResultCode code = resultCode(resultXdr);
        return code == null ? null : code.name();
    }

    private TransactionResultCode resultCode(String resultXdr) {
        if (resultXdr == null || resultXdr.isBlank()) {
            return null;
        }
        try {
            TransactionResult result = TransactionResult.fromXdrBase64(resultXdr);
            return result.getResult().getDiscriminant();
        } catch (Exception ex) {
            // Includes IOException from the XDR decoder and any runtime error
            // a malformed blob triggers inside it. Diagnostic only: the blob
            // itself is never logged, since a result XDR echoes transaction
            // contents back.
            log.debug("Could not decode a TransactionResult XDR ({} chars): {}",
                    resultXdr.length(), ex.getClass().getSimpleName());
            return null;
        }
    }
}
