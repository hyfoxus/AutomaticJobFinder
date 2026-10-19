package com.gnemirko.jobapplier.scoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.scoring.config.ScoringProperties;

class CostModelTest {

    @Test
    void pricesArePerMillionTokensRoundedUp() {
        CostModel cost = new CostModel(UnitSupport.props(0.15, 0.60, 1000, "1"));
        assertThat(cost.costMicros(1, 0)).isEqualTo(1);      // 0.15 rounds up
        assertThat(cost.costMicros(1000, 1000)).isEqualTo(150 + 600);
        assertThat(cost.costMicros(0, 0)).isZero();
    }

    @Test
    void worstCaseAddsFullOutputAllowance() {
        CostModel cost = new CostModel(UnitSupport.props(2, 10, 1000, "1"));
        assertThat(cost.worstCaseMicros(500)).isEqualTo(500 * 2 + 1000 * 10);
    }

    @Test
    void freeModelCostsNothing() {
        ScoringProperties p = new ScoringProperties(true, "x", 3,
                new ScoringProperties.Llm("llama3", true, 500, 6000, Map.of(), null),
                new ScoringProperties.Budget(new BigDecimal("1"), "UTC"));
        CostModel cost = new CostModel(p);
        assertThat(cost.costMicros(1_000_000, 1_000_000)).isZero();
        assertThat(cost.worstCaseMicros(10_000)).isZero();
    }

    @Test
    void largeCountsDoNotOverflow() {
        CostModel cost = new CostModel(UnitSupport.props(100, 100, 1000, "1"));
        assertThat(cost.costMicros(Integer.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(2L * Integer.MAX_VALUE * 100);
    }
}
