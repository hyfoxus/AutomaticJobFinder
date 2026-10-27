package com.gnemirko.jobapplier.ingestion.learner.dto;

/** What scoring's /api/llm/complete answers. */
public record Completion(String text, String finishReason, long inputTokens, long outputTokens) {

    public boolean cutOff() {
        return "LENGTH".equals(finishReason);
    }
}
