package com.gnemirko.jobapplier.digest.dto;

/** {@code GET /api/usage/today} as received. Strings are untrusted. */
public record UsageInfo(String spentUsd, String capUsd, String budgetState, Long calls) {
}
