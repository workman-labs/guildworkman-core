package com.guildworkman.api.escrow.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds the {@code escrow.orchestration.retry.*} properties governing
 * {@link EscrowOrchestrationService}'s submit/poll retry loop. Exposed as
 * config (rather than hardcoded constants) so both production tuning and
 * tests can set deterministic values without recompiling.
 */
@Component
@ConfigurationProperties(prefix = "escrow.orchestration.retry")
@Getter
@Setter
public class EscrowOrchestrationRetryProperties {

    /** Attempts (submit or poll, counted separately) before a request moves to DEAD_LETTER. */
    private int maxAttempts = 5;

    /** Backoff for attempt 1; doubles each subsequent attempt up to {@link #maxDelay}. */
    private Duration baseDelay = Duration.ofSeconds(1);

    /** Ceiling on the (pre-jitter) computed backoff, regardless of attempt count. */
    private Duration maxDelay = Duration.ofSeconds(64);

    /**
     * Fraction of the computed backoff to randomize by, in both directions
     * (e.g. {@code 0.2} spreads a 10s backoff over roughly [8s, 12s]) so many
     * requests scheduled for the same instant don't all retry in lockstep
     * against Soroban RPC.
     */
    private double jitter = 0.2;
}
