package com.gnemirko.jobapplier.scoring.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Today's spend against the cap. Amounts are dollars written as plain decimal strings. */
public record UsageView(LocalDate day, String spentUsd, String capUsd, BudgetState budgetState, long calls) {

    public static UsageView of(LocalDate day, long spentMicros, long capMicros, BudgetState state, long calls) {
        return new UsageView(day, dollars(spentMicros), dollars(capMicros), state, calls);
    }

    static String dollars(long micros) {
        return BigDecimal.valueOf(micros).movePointLeft(6).toPlainString();
    }
}
