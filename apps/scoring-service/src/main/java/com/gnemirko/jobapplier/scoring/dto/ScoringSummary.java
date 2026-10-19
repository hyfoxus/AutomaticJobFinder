package com.gnemirko.jobapplier.scoring.dto;

/**
 * Result of one {@code score} call.
 *
 * @param received          jobs passed in
 * @param duplicatesInBatch jobs skipped because the same source and external id came earlier in the batch
 * @param stored            jobs stored for the first time
 * @param rejectedByFilter  rows rejected by hard filters in this run
 * @param scored            rows scored in this run
 * @param failed            rows that failed in this run (including those that became permanent)
 * @param failedPermanent   of those, rows that used up their attempts
 * @param unscoredBudget    rows left unscored because the cap was reached
 * @param unprocessed       rows not touched because the run stopped early
 * @param llmCalls          model calls made
 * @param batchSpentMicros  spend of this run in micro-dollars
 * @param spentTodayMicros  spend of the whole day in micro-dollars
 * @param capMicros         daily cap in micro-dollars
 * @param budgetState       state of the budget at the end
 * @param stopReason        why the run ended early, or null
 */
public record ScoringSummary(
        int received,
        int duplicatesInBatch,
        int stored,
        int rejectedByFilter,
        int scored,
        int failed,
        int failedPermanent,
        int unscoredBudget,
        int unprocessed,
        int llmCalls,
        long batchSpentMicros,
        long spentTodayMicros,
        long capMicros,
        BudgetState budgetState,
        String stopReason) {
}
