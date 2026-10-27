package com.gnemirko.jobapplier.scoring.dto;

/** The model's text and why it stopped (STOP, LENGTH or OTHER); LENGTH means the reply was cut and is unusable. */
public record LearnReply(String text, String finishReason, long inputTokens, long outputTokens) {
}
