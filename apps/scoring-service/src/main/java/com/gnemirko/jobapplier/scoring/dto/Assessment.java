package com.gnemirko.jobapplier.scoring.dto;

import java.util.List;

/**
 * The reply schema the model must follow. Nothing in it triggers any action.
 *
 * @param tier            overall requirement match
 * @param metMustHaves    1-based numbers of the profile must-haves this job satisfies
 * @param gaps            job requirements the profile does not cover
 * @param interviewChance heuristic label from requirement match only
 * @param reason          one line explaining the tier
 */
public record Assessment(
        Tier tier,
        List<Integer> metMustHaves,
        List<Gap> gaps,
        InterviewChance interviewChance,
        String reason) {
}
