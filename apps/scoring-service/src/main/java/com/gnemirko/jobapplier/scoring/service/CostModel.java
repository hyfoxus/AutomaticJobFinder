package com.gnemirko.jobapplier.scoring.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties.Price;
import com.gnemirko.jobapplier.scoring.exception.ScoringConfigurationException;

/**
 * Token prices in micro-dollars, always rounded up. USD per million tokens equals micro-dollars per
 * token, so no scaling is needed. A local model ({@code free=true}) costs nothing.
 */
@Component
@ScoringEnabled
public class CostModel {

    private final boolean free;
    private final BigDecimal inputMicros;
    private final BigDecimal outputMicros;
    private final long maxTokens;

    public CostModel(ScoringProperties props) {
        ScoringProperties.Llm llm = props.llm();
        this.maxTokens = llm.maxTokens();
        this.free = llm.free();
        if (free) {
            this.inputMicros = BigDecimal.ZERO;
            this.outputMicros = BigDecimal.ZERO;
            return;
        }
        Price price = llm.prices().get(llm.model());
        if (price == null || price.inputPerMillion() == null || price.outputPerMillion() == null
                || price.inputPerMillion().signum() < 0 || price.outputPerMillion().signum() < 0) {
            throw new ScoringConfigurationException("Model '" + llm.model() + "' is not free and has no prices. Set "
                    + "scoring.llm.prices[" + llm.model() + "].input-per-million and .output-per-million "
                    + "(USD per million tokens), or scoring.llm.free=true for a local model.");
        }
        this.inputMicros = price.inputPerMillion();
        this.outputMicros = price.outputPerMillion();
    }

    public boolean isFree() {
        return free;
    }

    /** Cost of a finished call. */
    public long costMicros(long inputTokens, long outputTokens) {
        return ceil(inputMicros.multiply(BigDecimal.valueOf(Math.max(0, inputTokens)))
                .add(outputMicros.multiply(BigDecimal.valueOf(Math.max(0, outputTokens)))));
    }

    /** Most one call can cost: the prompt estimate plus the full output allowance. */
    public long worstCaseMicros(long promptEstimateTokens) {
        return costMicros(promptEstimateTokens, maxTokens);
    }

    /** Most one call can cost with a caller-chosen output allowance. */
    public long worstCaseMicros(long promptEstimateTokens, long maxOutputTokens) {
        return costMicros(promptEstimateTokens, maxOutputTokens);
    }

    private static long ceil(BigDecimal v) {
        return v.setScale(0, RoundingMode.CEILING).longValueExact();
    }
}
