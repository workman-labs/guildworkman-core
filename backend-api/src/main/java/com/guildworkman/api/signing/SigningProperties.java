package com.guildworkman.api.signing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds {@code stellar.signing.*}: which custody backend is active, the
 * network being signed for, and the fee/retry/lease policy the submission
 * workers apply.
 *
 * <p>The one genuinely secret value here is {@code stellar.signing.local.keys.*}
 * (development seeds, supplied through environment variables and never
 * committed). It is deliberately confined to {@link Local}, whose
 * {@code toString} is overridden so a stray {@code log.debug("{}", props)}
 * can't print it — see {@code docs/STELLAR_SIGNING.md} ("Key material").
 */
@Component
@ConfigurationProperties(prefix = "stellar.signing")
@Getter
@Setter
public class SigningProperties {

    /** Active custody backend: {@code local} or {@code kms}. */
    private String provider = "local";

    /**
     * Network passphrase the transaction hash is computed over. Signing for
     * the wrong network produces signatures that are valid nowhere, so this
     * must match the network {@code soroban.rpc.url} points at.
     */
    private String networkPassphrase = "Test SDF Network ; September 2015";

    /**
     * Key reference used as the fee source when a stalled transaction is
     * fee-bumped. Empty means "the channel account's own key", which is
     * always a valid fee source for its own transaction.
     */
    private String feeSourceKeyRef = "";

    /** How long a built transaction stays valid (its {@code maxTime} time bound). */
    private Duration transactionTimeout = Duration.ofSeconds(120);

    /**
     * How long a broadcast transaction may sit unconfirmed before it's
     * treated as stalled in the mempool and fee-bumped.
     */
    private Duration stallAfter = Duration.ofSeconds(30);

    /** How long a channel-account lease is held before the sweeper may reclaim it. */
    private Duration leaseTtl = Duration.ofMinutes(5);

    private final Local local = new Local();
    private final Kms kms = new Kms();
    private final Fee fee = new Fee();
    private final Retry retry = new Retry();

    /** Development/test custody: seeds supplied by configuration. */
    @Getter
    @Setter
    public static class Local {

        /**
         * keyRef → {@code S…} secret seed. Populated from the environment
         * (e.g. {@code STELLAR_LOCAL_KEY_CHANNEL_1}); never hard-coded and
         * never committed.
         */
        private Map<String, String> keys = new LinkedHashMap<>();

        /** Redacted on purpose: the values in {@link #keys} are secret seeds. */
        @Override
        public String toString() {
            return "SigningProperties.Local(keyRefs=" + keys.keySet() + ")";
        }
    }

    /** Production custody: signing delegated to an external KMS/HSM gateway. */
    @Getter
    @Setter
    public static class Kms {

        /** Base URL of the signing gateway, e.g. {@code https://kms.internal/stellar}. */
        private String url = "";

        /** Bearer credential for the gateway. Never logged, never returned. */
        private String apiKey = "";

        private Duration requestTimeout = Duration.ofSeconds(5);

        @Override
        public String toString() {
            return "SigningProperties.Kms(url=" + url + ", apiKey=***, requestTimeout=" + requestTimeout + ")";
        }
    }

    /**
     * Fee policy, in stroops (1 XLM = 10,000,000 stroops).
     *
     * <p>{@link #maxTotalStroops} is the bounded ceiling the issue asks for:
     * fee bumps double the fee each time, and the first bump that would cross
     * this ceiling instead moves the submission to a terminal
     * {@code FEE_CEILING_REACHED} failure. No amount of stalling can make this
     * service spend more than the ceiling on one transaction.
     */
    @Getter
    @Setter
    public static class Fee {

        /** Inclusion fee per operation for the first attempt. 100 stroops is the network minimum. */
        private long baseStroops = 100;

        /** Hard ceiling on the total fee of any one transaction (default 0.1 XLM). */
        private long maxTotalStroops = 1_000_000;

        /** Multiplier applied to the current total fee on each fee bump. */
        private double bumpMultiplier = 2.0;
    }

    /** Backoff for retryable failures, mirroring {@code escrow.orchestration.retry.*}. */
    @Getter
    @Setter
    public static class Retry {

        private int maxAttempts = 5;
        private Duration baseDelay = Duration.ofSeconds(1);
        private Duration maxDelay = Duration.ofSeconds(64);
        private double jitter = 0.2;
    }
}
