package com.gnemirko.jobapplier.ui.dto;

/** {@code GET /api/usage/today} as received. Strings are untrusted. */
public record UsageInfo(String spentUsd, String capUsd, BudgetState budgetState, Long calls) {
}
