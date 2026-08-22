package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.model.SubmissionFailureReason;

/**
 * What to <em>do</em> about a failure, as opposed to what the failure
 * <em>was</em> ({@link SubmissionFailureReason}).
 *
 * <p>Split out from the submission workers so the decision can be asserted
 * directly, one reason at a time, instead of only through the pipeline that
 * consumes it. The mapping lives in
 * {@link FailureClassifier#recoveryFor(SubmissionFailureReason)} as an
 * exhaustive switch with no {@code default}, so a failure reason added later
 * fails compilation rather than silently falling into "retry it" — which for
 * a transaction that is already on the network is the one answer that can do
 * damage.
 */
public enum RecoveryAction {

    /**
     * Throw the envelope away and start over: new channel account, new
     * sequence number, new time bounds. Only ever correct for an envelope the
     * network has definitively refused, or one whose validity window has
     * closed — both cases where the old envelope can never be included later.
     */
    REBUILD,

    /**
     * Wrap the in-flight transaction in a fee bump paying more. Safe while the
     * original may still be in the mempool: the inner transaction, and
     * therefore the operations and the sequence number, are unchanged.
     */
    FEE_BUMP,

    /** Nothing about resubmitting this transaction could change the outcome. Stop. */
    TERMINAL,

    /** A transient condition. Back off and try the same thing again, while attempts remain. */
    RETRY
}
