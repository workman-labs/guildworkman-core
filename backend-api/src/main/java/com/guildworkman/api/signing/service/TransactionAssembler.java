package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.SigningProperties;
import org.springframework.stereotype.Component;
import org.stellar.sdk.AbstractTransaction;
import org.stellar.sdk.Account;
import org.stellar.sdk.FeeBumpTransaction;
import org.stellar.sdk.Network;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TransactionBuilder;
import org.stellar.sdk.operations.Operation;

import java.util.Arrays;

/**
 * Turns the caller's envelope into a transaction this service can actually
 * submit: same operations, but anchored to a leased channel account with a
 * freshly allocated sequence number, our fee, and our time bounds.
 *
 * <p><b>Why rebuild instead of relaying.</b> Signing a transaction commits to
 * its source account, sequence number, fee and time bounds — all four are
 * inside the signed payload. A service that accepted pre-signed envelopes
 * (as the escrow orchestration flow does) therefore has exactly one move
 * available when something goes wrong, which is to send the same bytes again.
 * Keeping the caller's <em>operations</em> and rebuilding everything around
 * them is what makes the recovery paths in {@code TransactionSubmissionService}
 * possible: a stale sequence or lapsed time bounds are re-derived and the
 * transaction is signed again, rather than being abandoned.
 *
 * <p>The caller's source account, sequence, fee and time bounds are placeholders
 * and are always replaced. Preconditions beyond time bounds (minimum sequence
 * age, ledger bounds, extra signers) are rejected rather than silently
 * dropped — they constrain the very fields this service owns, so honouring
 * them and managing sequences centrally are mutually exclusive.
 */
@Component
public class TransactionAssembler {

    /**
     * Enough for a batched classic operation set, while bounding the fee any
     * single submission can commit us to (fee is per-operation).
     */
    static final int MAX_OPERATIONS = 20;

    private final SigningProperties properties;
    private final Network network;

    public TransactionAssembler(SigningProperties properties) {
        this.properties = properties;
        this.network = new Network(properties.getNetworkPassphrase());
    }

    /** The network every transaction here is hashed and signed against. */
    public Network network() {
        return network;
    }

    /**
     * Parses a caller-supplied envelope.
     *
     * @throws TransactionAssemblyException if it isn't a plain, non-empty transaction envelope
     */
    public Transaction parse(String envelopeXdr) {
        AbstractTransaction parsed;
        try {
            parsed = AbstractTransaction.fromEnvelopeXdr(envelopeXdr, network);
        } catch (RuntimeException ex) {
            throw new TransactionAssemblyException("Could not parse the transaction envelope XDR: " + ex.getMessage());
        }
        if (parsed instanceof FeeBumpTransaction) {
            throw new TransactionAssemblyException("Fee-bump envelopes are not accepted: submit the inner "
                    + "transaction's operations and let the service fee-bump it if it stalls");
        }
        if (!(parsed instanceof Transaction transaction)) {
            throw new TransactionAssemblyException("Unsupported transaction envelope type");
        }
        Operation[] operations = transaction.getOperations();
        if (operations == null || operations.length == 0) {
            throw new TransactionAssemblyException("The transaction envelope carries no operations");
        }
        if (operations.length > MAX_OPERATIONS) {
            throw new TransactionAssemblyException("The transaction envelope carries " + operations.length
                    + " operations; the maximum is " + MAX_OPERATIONS);
        }
        if (transaction.getPreconditions() != null && transaction.getPreconditions().hasV2()) {
            throw new TransactionAssemblyException("Preconditions other than time bounds are not supported: "
                    + "this service owns the source account, sequence number and validity window");
        }
        return transaction;
    }

    /**
     * Rebuilds {@code source}'s operations onto the leased channel account.
     *
     * <p>{@code Account(accountId, sequence - 1)} is not an off-by-one:
     * {@link TransactionBuilder} increments the account's sequence when it
     * builds, so seeding it one below the allocated number produces a
     * transaction carrying exactly that number.
     *
     * @param inclusionFeePerOperation inclusion (bid) fee per operation, in stroops. For a Soroban transaction the
     *                                 builder adds the resource fee from {@code sorobanDataXdr} on top, which is
     *                                 why the caller reads the final total back off the built transaction rather
     *                                 than computing it
     * @param sorobanDataXdr           base64 {@code SorobanTransactionData} from simulation, or {@code null} for a
     *                                 classic transaction
     */
    public Transaction rebuild(Transaction source, SequenceLease lease, long inclusionFeePerOperation,
                               String sorobanDataXdr) {
        TransactionBuilder builder = new TransactionBuilder(
                new Account(lease.accountId(), lease.sequenceNumber() - 1), network)
                .addOperations(Arrays.asList(source.getOperations()))
                .setBaseFee(inclusionFeePerOperation)
                .setTimeout(properties.getTransactionTimeout().toSeconds());

        if (source.getMemo() != null) {
            builder.addMemo(source.getMemo());
        }
        if (sorobanDataXdr != null && !sorobanDataXdr.isBlank()) {
            builder.setSorobanData(sorobanDataXdr);
        }
        try {
            return builder.build();
        } catch (RuntimeException ex) {
            throw new TransactionAssemblyException("Could not build the transaction: " + ex.getMessage());
        }
    }

    /**
     * Wraps a stalled transaction in a fee bump paying {@code totalFeeStroops}.
     *
     * <p>A fee bump keeps the inner transaction — and therefore its sequence
     * number, operations and signatures — exactly as it was, and only offers
     * the network more to include it. That's why it is the right tool for a
     * transaction stuck in the mempool: no sequence is consumed, nothing is
     * re-authorised, and if the original happens to land first the bump simply
     * becomes irrelevant.
     */
    public FeeBumpTransaction feeBump(Transaction inner, String feeSourceAccountId, long totalFeeStroops) {
        try {
            return FeeBumpTransaction.createWithFee(feeSourceAccountId, totalFeeStroops, inner);
        } catch (RuntimeException ex) {
            throw new TransactionAssemblyException("Could not build a fee-bump transaction: " + ex.getMessage());
        }
    }
}
