package com.guildworkman.api.signing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Configuration that would break signing has to break the boot instead.
 *
 * <p>The fee bounds are the reason this validation exists. Every value here is
 * one typo away from a production outage that doesn't look like a
 * configuration problem at all: a ceiling under the base fee makes every
 * transaction fail {@code FEE_CEILING_REACHED} before it is even signed, and a
 * bump multiplier of 1.0 turns fee bumps into a loop that burns attempts
 * without ever outbidding anything. Both would first surface as "Stellar is
 * broken", at the exact moment a transaction needed to go out.
 */
class SigningPropertiesValidationTest {

    private static SigningProperties valid() {
        return new SigningProperties();
    }

    @Test
    void theShippedDefaultsAreValid() {
        assertThatCode(() -> valid().validate()).doesNotThrowAnyException();
    }

    @Test
    void aCeilingBelowTheBaseFeeIsRefused() {
        SigningProperties properties = valid();
        properties.getFee().setBaseStroops(100);
        properties.getFee().setMaxTotalStroops(50);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-total-stroops")
                .hasMessageContaining("refuses every transaction");
    }

    /** A ceiling exactly at the base fee is legal: a single-operation transaction still fits. */
    @Test
    void aCeilingEqualToTheBaseFeeIsAllowed() {
        SigningProperties properties = valid();
        properties.getFee().setBaseStroops(100);
        properties.getFee().setMaxTotalStroops(100);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void aBaseFeeBelowTheNetworkMinimumIsRefused() {
        SigningProperties properties = valid();
        properties.getFee().setBaseStroops(99);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("network minimum of 100 stroops");
    }

    @Test
    void aNegativeCeilingIsRefused() {
        SigningProperties properties = valid();
        properties.getFee().setMaxTotalStroops(-1);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBumpMultiplierThatCannotOutbidAnythingIsRefused() {
        SigningProperties properties = valid();
        properties.getFee().setBumpMultiplier(1.0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bump-multiplier")
                .hasMessageContaining("cannot outbid");
    }

    @Test
    void anUnknownProviderIsRefused() {
        SigningProperties properties = valid();
        properties.setProvider("vault");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be 'local' or 'kms'");
    }

    @Test
    void anEmptyNetworkPassphraseIsRefused() {
        SigningProperties properties = valid();
        properties.setNetworkPassphrase("  ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("network-passphrase");
    }

    @Test
    void aRetryPolicyThatWouldNeverRetryIsRefused() {
        SigningProperties properties = valid();
        properties.getRetry().setMaxAttempts(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-attempts");
    }

    @Test
    void aMaxDelayBelowTheBaseDelayIsRefused() {
        SigningProperties properties = valid();
        properties.getRetry().setBaseDelay(Duration.ofSeconds(10));
        properties.getRetry().setMaxDelay(Duration.ofSeconds(1));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-delay");
    }

    @Test
    void jitterOutsideTheUnitIntervalIsRefused() {
        SigningProperties properties = valid();
        properties.getRetry().setJitter(1.0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jitter");
    }

    @Test
    void nonPositiveDurationsAreRefused() {
        SigningProperties zeroTimeout = valid();
        zeroTimeout.setTransactionTimeout(Duration.ZERO);
        assertThatThrownBy(zeroTimeout::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction-timeout");

        SigningProperties negativeLease = valid();
        negativeLease.setLeaseTtl(Duration.ofSeconds(-1));
        assertThatThrownBy(negativeLease::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-ttl");

        SigningProperties zeroStall = valid();
        zeroStall.setStallAfter(Duration.ZERO);
        assertThatThrownBy(zeroStall::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stall-after");
    }

    /** The pause lever ships on, and toggling it is not itself a misconfiguration. */
    @Test
    void signingIsEnabledByDefaultAndMayBeTurnedOff() {
        SigningProperties properties = valid();
        assertThat(properties.isEnabled()).isTrue();

        properties.setEnabled(false);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
