package com.gnemirko.jobapplier.scoring.service;

import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;
import com.gnemirko.jobapplier.scoring.dto.BudgetState;
import com.gnemirko.jobapplier.scoring.exception.ScoringConfigurationException;

/** Decides whether the next call fits under the daily cap. */
@Component
@ScoringEnabled
public class BudgetGuard {

    /** WARN threshold, as a percentage of the cap. */
    static final int WARN_PERCENT = 80;

    private final UsageLedger ledger;
    private final long capMicros;

    public BudgetGuard(UsageLedger ledger, ScoringProperties props) {
        this.ledger = ledger;
        if (props.budget().dailyUsd() == null || props.budget().dailyUsd().signum() <= 0) {
            throw new ScoringConfigurationException("scoring.budget.daily-usd must be greater than 0");
        }
        this.capMicros = props.budget().capMicros();
        if (capMicros <= 0) {
            throw new ScoringConfigurationException("scoring.budget.daily-usd is below one micro-dollar");
        }
    }

    public long capMicros() {
        return capMicros;
    }

    /** True when today's spend plus the worst case of the next call stays within the cap. */
    public boolean canAfford(long worstCaseMicros) {
        return ledger.spentPlusFits(worstCaseMicros, capMicros);
    }

    /** Check and charge in one step: reserves the worst case in the ledger, or returns empty when it does not fit. */
    public java.util.OptionalLong reserve(String model, long worstCaseMicros, String jobKey) {
        return ledger.reserve(model, worstCaseMicros, capMicros, jobKey);
    }

    public void settle(long reservationId, long inputTokens, long outputTokens, long micros, boolean estimated) {
        ledger.settle(reservationId, inputTokens, outputTokens, micros, estimated);
    }

    public void release(long reservationId) {
        ledger.release(reservationId);
    }

    public long spentToday() {
        return ledger.spentToday();
    }

    public boolean isNearCap(long spentMicros) {
        return spentMicros * 100 >= capMicros * WARN_PERCENT;
    }

    public BudgetState state(long spentMicros, boolean capReached) {
        if (capReached) {
            return BudgetState.CAP_REACHED;
        }
        return isNearCap(spentMicros) ? BudgetState.NEAR_CAP : BudgetState.OK;
    }
}
