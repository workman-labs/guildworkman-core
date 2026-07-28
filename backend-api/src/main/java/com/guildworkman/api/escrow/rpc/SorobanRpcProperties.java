package com.guildworkman.api.escrow.rpc;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds the {@code soroban.rpc.*} properties used to reach a Soroban RPC
 * endpoint (e.g. a Horizon/RPC provider or a local {@code soroban-rpc}
 * instance) for submitting and polling escrow-contract transactions.
 */
@Component
@ConfigurationProperties(prefix = "soroban.rpc")
@Getter
@Setter
public class SorobanRpcProperties {

    /** JSON-RPC endpoint, e.g. {@code https://soroban-testnet.stellar.org}. */
    private String url = "https://soroban-testnet.stellar.org";

    /** HTTP call timeout for each JSON-RPC request. */
    private Duration requestTimeout = Duration.ofSeconds(10);
}
