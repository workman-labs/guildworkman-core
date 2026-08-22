package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.SigningProperties;
import com.guildworkman.api.signing.StellarTestFixtures;
import org.junit.jupiter.api.Test;
import org.stellar.sdk.FeeBumpTransaction;
import org.stellar.sdk.KeyPair;
import org.stellar.sdk.Transaction;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionAssemblerTest {

    private final SigningProperties properties = new SigningProperties();
    private final TransactionAssembler assembler = new TransactionAssembler(properties);

    private static SequenceLease lease(String accountId, long sequence) {
        return new SequenceLease(1L, accountId, "channel1", sequence, Instant.now().plusSeconds(300));
    }

    // --- parse --------------------------------------------------------------

    @Test
    void parsesAWellFormedEnvelope() {
        Transaction parsed = assembler.parse(StellarTestFixtures.unsignedEnvelope(2));

        assertThat(parsed.getOperations()).hasSize(2);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> assembler.parse("not-xdr"))
                .isInstanceOf(TransactionAssemblyException.class)
                .hasMessageContaining("Could not parse");
    }

    /**
     * Accepting a fee bump would hand us an envelope whose fee we don't
     * control, and whose inner transaction is already signed against someone
     * else's sequence number. The service builds its own bumps.
     */
    @Test
    void rejectsAFeeBumpEnvelope() {
        KeyPair signer = KeyPair.random();
        Transaction inner = StellarTestFixtures.unsignedTransaction(1);
        inner.sign(signer);
        FeeBumpTransaction feeBump = FeeBumpTransaction.createWithFee(signer.getAccountId(), 1000L, inner);

        assertThatThrownBy(() -> assembler.parse(feeBump.toEnvelopeXdrBase64()))
                .isInstanceOf(TransactionAssemblyException.class)
                .hasMessageContaining("Fee-bump envelopes are not accepted");
    }

    @Test
    void rejectsAnEnvelopeWithTooManyOperations() {
        String envelope = StellarTestFixtures.unsignedEnvelope(TransactionAssembler.MAX_OPERATIONS + 1);

        assertThatThrownBy(() -> assembler.parse(envelope))
                .isInstanceOf(TransactionAssemblyException.class)
                .hasMessageContaining("the maximum is " + TransactionAssembler.MAX_OPERATIONS);
    }

    // --- rebuild ------------------------------------------------------------

    /**
     * The sequence number is the reason this service exists, so it gets its
     * own assertion: the built transaction must carry exactly the number the
     * lease allocated, not the one the caller's placeholder envelope had.
     */
    @Test
    void rebuildsOntoTheLeasedAccountWithTheAllocatedSequence() {
        Transaction source = assembler.parse(StellarTestFixtures.unsignedEnvelope(2));
        String channelAccount = KeyPair.random().getAccountId();

        Transaction rebuilt = assembler.rebuild(source, lease(channelAccount, 4242L), 100L, null);

        assertThat(rebuilt.getSourceAccount()).isEqualTo(channelAccount);
        assertThat(rebuilt.getSequenceNumber()).isEqualTo(4242L);
        assertThat(rebuilt.getOperations()).hasSize(2);
        assertThat(rebuilt.getFee()).isEqualTo(200L); // 100 stroops x 2 operations
    }

    @Test
    void rebuildAppliesTheConfiguredValidityWindow() {
        properties.setTransactionTimeout(Duration.ofSeconds(90));
        Transaction source = assembler.parse(StellarTestFixtures.unsignedEnvelope());

        Transaction rebuilt = assembler.rebuild(source, lease(KeyPair.random().getAccountId(), 7L), 100L, null);

        long maxTime = rebuilt.getTimeBounds().getMaxTime().longValue();
        assertThat(maxTime).isBetween(Instant.now().getEpochSecond() + 60,
                Instant.now().getEpochSecond() + 120);
    }

    /** Two rebuilds of the same operations onto different sequences are different transactions. */
    @Test
    void rebuildingWithADifferentSequenceProducesADifferentHash() {
        Transaction source = assembler.parse(StellarTestFixtures.unsignedEnvelope());
        String channelAccount = KeyPair.random().getAccountId();

        String first = assembler.rebuild(source, lease(channelAccount, 1L), 100L, null).hashHex();
        String second = assembler.rebuild(source, lease(channelAccount, 2L), 100L, null).hashHex();

        assertThat(first).isNotEqualTo(second);
    }

    // --- feeBump ------------------------------------------------------------

    @Test
    void feeBumpKeepsTheInnerTransactionIntact() {
        Transaction source = assembler.parse(StellarTestFixtures.unsignedEnvelope());
        KeyPair channel = KeyPair.random();
        Transaction inner = assembler.rebuild(source, lease(channel.getAccountId(), 99L), 100L, null);
        inner.sign(channel);

        FeeBumpTransaction bumped = assembler.feeBump(inner, channel.getAccountId(), 5000L);

        assertThat(bumped.getFee()).isEqualTo(5000L);
        assertThat(bumped.getInnerTransaction().getSequenceNumber()).isEqualTo(99L);
        assertThat(bumped.getInnerTransaction().hashHex()).isEqualTo(inner.hashHex());
        // The bump is a different envelope with a different hash, which is why
        // the poller has to watch both.
        assertThat(bumped.hashHex()).isNotEqualTo(inner.hashHex());
    }

    /**
     * {@code createWithFee} accepts any amount at all — including one lower
     * than the inner transaction's own fee. That's why the ceiling in
     * {@code TransactionSubmissionService} is enforced by us and not delegated
     * to the SDK; if that ever changes, this test says so.
     */
    @Test
    void theSdkDoesNotPoliceFeeBumpAmounts() {
        Transaction source = assembler.parse(StellarTestFixtures.unsignedEnvelope());
        KeyPair channel = KeyPair.random();
        Transaction inner = assembler.rebuild(source, lease(channel.getAccountId(), 1L), 100L, null);
        inner.sign(channel);

        assertThat(assembler.feeBump(inner, channel.getAccountId(), 1L).getFee()).isEqualTo(1L);
    }

    @Test
    void feeBumpFailuresSurfaceAsAssemblyExceptions() {
        Transaction source = assembler.parse(StellarTestFixtures.unsignedEnvelope());
        Transaction inner = assembler.rebuild(source, lease(KeyPair.random().getAccountId(), 1L), 100L, null);

        assertThatThrownBy(() -> assembler.feeBump(inner, null, 1000L))
                .isInstanceOf(TransactionAssemblyException.class)
                .hasMessageContaining("Could not build a fee-bump transaction");
    }

    @Test
    void networkMatchesTheConfiguredPassphrase() {
        assertThat(assembler.network().getNetworkPassphrase())
                .isEqualTo(properties.getNetworkPassphrase());
    }
}
