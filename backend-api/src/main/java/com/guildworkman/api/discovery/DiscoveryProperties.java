package com.guildworkman.api.discovery;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code guildworkman.discovery.*} — the bounds and defaults of the
 * worker discovery endpoint. (Ranking weights and reputation settings have their
 * own nested prefixes: {@code RankingWeights}, {@code ReputationProperties}.)
 */
@Component
@ConfigurationProperties(prefix = "guildworkman.discovery")
@Getter
@Setter
public class DiscoveryProperties {

    /** Radius used when the request omits {@code radiusKm}. */
    private double defaultRadiusKm = 10;

    /** Hard ceiling on {@code radiusKm}; a larger value is a 400, not a silent clamp. */
    private double maxRadiusKm = 50;

    /** Page size used when the request omits {@code size}. */
    private int defaultPageSize = 20;

    /** Hard ceiling on {@code size}. */
    private int maxPageSize = 50;

    /** How many skill facet buckets are returned, most-common first. */
    private int maxSkillFacets = 25;
}
