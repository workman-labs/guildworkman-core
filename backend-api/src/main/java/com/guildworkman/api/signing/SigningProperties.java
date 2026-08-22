package com.guildworkman.api.signing;

import jakarta.annotation.PostConstruct;
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

    /**
     * Master switch for the submission workers. Set to {@code false} to stop
     * signing, broadcasting and polling without a deploy — the rollback lever
     * described in {@code docs/STELLAR_SIGNING.md} ("Operator runbook").
     *
     * <p>Pausing is safe at any point because every phase transition is
     * already durable: work queues up as {@code PENDING}/{@code SIGNED}/
     * {@code BROADCAST} rows and resumes exactly where it stopped. The API
     * keeps accepting submissions while paused (they simply aren't signed
     * yet), and the lease sweeper keeps running, so an in-flight transaction's
     * channel account stays held rather than being handed to someone else.
     */
    private boolean enabled = true;

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

    /**
     * Refuses to start on a configuration that would misbehave in production
     * rather than in a test.
     *
     * <p>The fee bounds are the reason this exists. A ceiling below the base
     * fee makes every transaction fail {@code FEE_CEILING_REACHED} before it
     * is ever signed — a silent, total outage that looks like a Stellar
     * problem. A bump multiplier of 1.0 or less makes fee bumps a no-op loop
     * that burns attempts without ever outbidding anything. Both are one typo
     * away, and neither shows up until a transaction actually needs to go out,
     * which in a signing service is the worst possible moment to find out.
     *
     * @throws IllegalStateException naming the offending property and what a valid value looks like
     */
    @PostConstruct
    void validate() {
        require("local".equals(provider) || "kms".equals(provider),
                "stellar.signing.provider must be 'local' or 'kms', got '" + provider + "'");
        require(networkPassphrase != null && !networkPassphrase.isBlank(),
                "stellar.signing.network-passphrase must be set; signing for the wrong network "
                        + "produces signatures that are valid nowhere");

        require(fee.baseStroops >= MIN_STROOPS_PER_OPERATION,
                "stellar.signing.fee.base-stroops must be at least the network minimum of "
                        + MIN_STROOPS_PER_OPERATION + " stroops per operation, got " + fee.baseStroops);
        require(fee.maxTotalStroops >= fee.baseStroops,
                "stellar.signing.fee.max-total-stroops (" + fee.maxTotalStroops + ") must be at least "
                        + "stellar.signing.fee.base-stroops (" + fee.baseStroops + "); a lower ceiling refuses "
                        + "every transaction before it is signed");
        require(fee.bumpMultiplier > 1.0,
                "stellar.signing.fee.bump-multiplier must be greater than 1.0, got " + fee.bumpMultiplier
                        + "; a bump that doesn't raise the fee cannot outbid anything");

        require(retry.maxAttempts >= 1,
                "stellar.signing.retry.max-attempts must be at least 1, got " + retry.maxAttempts);
        require(isPositive(retry.baseDelay), "stellar.signing.retry.base-delay must be positive");
        require(isPositive(retry.maxDelay) && retry.maxDelay.compareTo(retry.baseDelay) >= 0,
                "stellar.signing.retry.max-delay must be positive and at least the base delay");
        require(retry.jitter >= 0 && retry.jitter < 1,
                "stellar.signing.retry.jitter must be in [0, 1), got " + retry.jitter);

        require(isPositive(transactionTimeout), "stellar.signing.transaction-timeout must be positive");
        require(isPositive(stallAfter), "stellar.signing.stall-after must be positive");
        require(isPositive(leaseTtl), "stellar.signing.lease-ttl must be positive");
    }

    /** The network's minimum inclusion fee per operation. Below this nothing is ever included. */
    private static final long MIN_STROOPS_PER_OPERATION = 100;

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

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
