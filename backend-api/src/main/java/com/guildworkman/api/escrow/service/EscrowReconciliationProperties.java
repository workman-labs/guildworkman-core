package com.guildworkman.api.escrow.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "escrow.reconciliation")
@Getter
@Setter
public class EscrowReconciliationProperties {

    /**
     * How long a CONFIRMED request may go without a corroborating on-chain
     * event before it's flagged MISMATCHED. Gives the ingestion indexer
     * (see {@code com.guildworkman.api.chain}) time to catch up.
     */
    private Duration window = Duration.ofMinutes(10);
}
