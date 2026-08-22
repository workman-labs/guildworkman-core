package com.guildworkman.api.signing;

import org.stellar.sdk.Account;
import org.stellar.sdk.AssetTypeNative;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.Network;
import org.stellar.sdk.Transaction;
import org.stellar.sdk.TransactionBuilder;
import org.stellar.sdk.operations.ExtendFootprintTTLOperation;
import org.stellar.sdk.operations.PaymentOperation;
import org.stellar.sdk.xdr.AccountEntry;
import org.stellar.sdk.xdr.Int64;
import org.stellar.sdk.xdr.LedgerEntry;
import org.stellar.sdk.xdr.LedgerEntryType;
import org.stellar.sdk.xdr.LedgerFootprint;
import org.stellar.sdk.xdr.LedgerKey;
import org.stellar.sdk.xdr.SorobanResources;
import org.stellar.sdk.xdr.SorobanTransactionData;
import org.stellar.sdk.xdr.OperationResult;
import org.stellar.sdk.xdr.SequenceNumber;
import org.stellar.sdk.xdr.Signer;
import org.stellar.sdk.xdr.String32;
import org.stellar.sdk.xdr.Thresholds;
import org.stellar.sdk.xdr.TransactionResult;
import org.stellar.sdk.xdr.TransactionResultCode;
import org.stellar.sdk.xdr.Uint32;
import org.stellar.sdk.xdr.XdrString;
import org.stellar.sdk.xdr.XdrUnsignedInteger;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Builders for the two kinds of Stellar XDR the signing tests need: an
 * unsigned envelope to submit, and a {@code TransactionResult} standing in for
 * a network rejection.
 *
 * <p>Everything here is real XDR produced by the SDK rather than a hand-pasted
 * constant, so the tests exercise the same parse/decode paths production does
 * and don't quietly rot if the envelope format moves under them.
 */
public final class StellarTestFixtures {

    /** The network the tests sign against; matches the default network passphrase. */
    public static final Network NETWORK = Network.TESTNET;

    private StellarTestFixtures() {
    }

    public static KeyPair randomKeyPair() {
        return KeyPair.random();
    }

    public static String randomKeyRef(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A one-payment envelope with placeholder source/sequence/fee, as a caller would submit it. */
    public static String unsignedEnvelope() {
        return unsignedEnvelope(1);
    }

    /**
     * @param operationCount how many payment operations to include — the knob the
     *                       operation-limit test turns
     */
    public static String unsignedEnvelope(int operationCount) {
        return unsignedTransaction(operationCount).toEnvelopeXdrBase64();
    }

    public static Transaction unsignedTransaction(int operationCount) {
        KeyPair source = KeyPair.random();
        KeyPair destination = KeyPair.random();
        TransactionBuilder builder = new TransactionBuilder(new Account(source.getAccountId(), 0L), NETWORK)
                .setBaseFee(100)
                .setTimeout(180);
        for (int i = 0; i < operationCount; i++) {
            builder.addOperation(PaymentOperation.builder()
                    .destination(destination.getAccountId())
                    .asset(new AssetTypeNative())
                    .amount(BigDecimal.valueOf(1L + i))
                    .build());
        }
        return builder.build();
    }

    /**
     * An envelope the service will recognise as a Soroban transaction and
     * therefore simulate before signing. {@code ExtendFootprintTTL} is the
     * cheapest Soroban operation to construct — what matters is only that
     * {@code Transaction#isSorobanTransaction()} answers true.
     */
    public static String unsignedSorobanEnvelope() {
        KeyPair source = KeyPair.random();
        return new TransactionBuilder(new Account(source.getAccountId(), 0L), NETWORK)
                .setBaseFee(100)
                .setTimeout(180)
                .addOperation(ExtendFootprintTTLOperation.builder().extendTo(1000L).build())
                .build()
                .toEnvelopeXdrBase64();
    }

    /**
     * Base64 {@code SorobanTransactionData} of the kind simulation returns —
     * an empty footprint and the given resource fee, which the transaction
     * builder adds on top of the inclusion fee.
     */
    public static String sorobanTransactionData(long resourceFeeStroops) {
        LedgerFootprint footprint = new LedgerFootprint(new LedgerKey[0], new LedgerKey[0]);
        SorobanResources resources = new SorobanResources(footprint,
                new Uint32(new XdrUnsignedInteger(0)),
                new Uint32(new XdrUnsignedInteger(0)),
                new Uint32(new XdrUnsignedInteger(0)));
        SorobanTransactionData.SorobanTransactionDataExt ext =
                new SorobanTransactionData.SorobanTransactionDataExt();
        ext.setDiscriminant(0);
        SorobanTransactionData data = new SorobanTransactionData(ext, resources, new Int64(resourceFeeStroops));
        try {
            return data.toXdrBase64();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not encode SorobanTransactionData", ex);
        }
    }

    /**
     * Base64 {@code LedgerEntryData} for a funded account at {@code sequence},
     * as {@code getLedgerEntries} returns it — the shape
     * {@code SorobanRpcClient#getAccountSequence} decodes.
     */
    public static String accountLedgerEntryXdr(String accountId, long sequence) {
        AccountEntry account = new AccountEntry();
        account.setAccountID(KeyPair.fromAccountId(accountId).getXdrAccountId());
        account.setBalance(new Int64(100_000_000L));
        account.setSeqNum(new SequenceNumber(new Int64(sequence)));
        account.setNumSubEntries(new Uint32(new XdrUnsignedInteger(0)));
        account.setFlags(new Uint32(new XdrUnsignedInteger(0)));
        account.setHomeDomain(new String32(new XdrString("")));
        account.setThresholds(new Thresholds(new byte[] {1, 0, 0, 0}));
        account.setSigners(new Signer[0]);
        account.setExt(new AccountEntry.AccountEntryExt(0, null));

        LedgerEntry.LedgerEntryData data = new LedgerEntry.LedgerEntryData();
        data.setDiscriminant(LedgerEntryType.ACCOUNT);
        data.setAccount(account);
        try {
            return data.toXdrBase64();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not encode an account ledger entry", ex);
        }
    }

    /** Base64 {@code TransactionResult} carrying {@code code}, as Soroban RPC returns on a rejection. */
    public static String transactionResultXdr(TransactionResultCode code) {
        TransactionResult.TransactionResultResult result = new TransactionResult.TransactionResultResult();
        result.setDiscriminant(code);
        if (code == TransactionResultCode.txSUCCESS || code == TransactionResultCode.txFAILED) {
            // The only two arms of the union that carry per-operation results.
            result.setResults(new OperationResult[0]);
        }
        TransactionResult transactionResult = new TransactionResult();
        transactionResult.setFeeCharged(new Int64(100L));
        transactionResult.setResult(result);
        TransactionResult.TransactionResultExt ext = new TransactionResult.TransactionResultExt();
        ext.setDiscriminant(0);
        transactionResult.setExt(ext);
        try {
            return transactionResult.toXdrBase64();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not encode a TransactionResult for " + code, ex);
        }
    }
}
