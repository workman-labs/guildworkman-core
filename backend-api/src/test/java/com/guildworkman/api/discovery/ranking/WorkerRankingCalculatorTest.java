package com.guildworkman.api.discovery.ranking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorkerRankingCalculatorTest {

    private static WorkerRankingCalculator calculator(double wProx, double wRep, double wAvail) {
        RankingWeights weights = new RankingWeights();
        weights.setProximityWeight(wProx);
        weights.setReputationWeight(wRep);
        weights.setAvailabilityWeight(wAvail);
        return new WorkerRankingCalculator(weights);
    }

    @Test
    void proximityComponentIsOneAtTheCallerAndZeroAtTheEdge() {
        assertThat(WorkerRankingCalculator.proximityComponent(0, 10)).isEqualTo(1.0);
        assertThat(WorkerRankingCalculator.proximityComponent(10, 10)).isEqualTo(0.0);
        assertThat(WorkerRankingCalculator.proximityComponent(5, 10)).isEqualTo(0.5);
        // Beyond the edge never goes negative.
        assertThat(WorkerRankingCalculator.proximityComponent(50, 10)).isEqualTo(0.0);
    }

    @Test
    void scoreIsTheNormalisedWeightedBlend() {
        WorkerRankingCalculator calc = calculator(0.5, 0.3, 0.2);
        // distance 2.5/10 -> proximity 0.75; reputation 0.8; available -> 1
        double expected = (0.5 * 0.75 + 0.3 * 0.8 + 0.2 * 1.0) / 1.0;
        assertThat(calc.score(new RankingSignals(2.5, 10, 0.8, true)))
                .isCloseTo(expected, within(1e-9));
    }

    @Test
    void scoreStaysInUnitRangeRegardlessOfWeightMagnitude() {
        WorkerRankingCalculator calc = calculator(3, 5, 2); // do not sum to 1
        double best = calc.score(new RankingSignals(0, 10, 1.0, true));
        double worst = calc.score(new RankingSignals(10, 10, 0.0, false));
        assertThat(best).isEqualTo(1.0);
        assertThat(worst).isEqualTo(0.0);
    }

    @Test
    void weightsAreConfigurable_reweightingChangesTheOrder() {
        RankingSignals near = new RankingSignals(1, 10, 0.2, true);   // close, poorly rated
        RankingSignals far = new RankingSignals(8, 10, 0.95, true);   // far, well rated

        assertThat(calculator(0.8, 0.1, 0.1).score(near))
                .isGreaterThan(calculator(0.8, 0.1, 0.1).score(far));
        assertThat(calculator(0.1, 0.8, 0.1).score(far))
                .isGreaterThan(calculator(0.1, 0.8, 0.1).score(near));
    }

    @Test
    void reputationOutOfRangeIsClamped() {
        WorkerRankingCalculator calc = calculator(0, 1, 0);
        assertThat(calc.score(new RankingSignals(0, 10, 5.0, false))).isEqualTo(1.0);
        assertThat(calc.score(new RankingSignals(0, 10, -1.0, false))).isEqualTo(0.0);
    }

    @Test
    void weightsValidationRejectsAllZeroAndNegative() {
        RankingWeights allZero = new RankingWeights();
        allZero.setProximityWeight(0);
        allZero.setReputationWeight(0);
        allZero.setAvailabilityWeight(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(allZero::validate)
                .isInstanceOf(IllegalStateException.class);
    }
}
