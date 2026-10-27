package com.gnemirko.jobapplier.scoring.dto;

/** What the recipe learner sends: the site host, the reduced page skeleton and, on a retry, why the last reply failed. */
public record LearnRequest(String host, String skeleton, String feedback) {
}
