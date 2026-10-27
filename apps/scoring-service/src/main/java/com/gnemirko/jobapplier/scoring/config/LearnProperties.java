package com.gnemirko.jobapplier.scoring.config;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.gnemirko.jobapplier.scoring.exception.ScoringConfigurationException;

/**
 * Settings of the recipe-learn endpoint. The endpoint exists only when a token is set.
 *
 * @param token             shared secret expected in X-Learner-Token (from the learner_token secret file)
 * @param maxTokens         output cap of a learn call, sized for a full recipe; also used for the reservation
 * @param dailyRuns         learn runs per day (a retry is the second call of a run)
 * @param dailyUsd          learn-only spend per day, inside the shared daily cap
 * @param maxSkeletonChars  longest accepted skeleton; longer is 413
 * @param maxFeedbackChars  longest accepted retry feedback; longer is 413
 */
@ConfigurationProperties("scoring.learn")
public record LearnProperties(
        @DefaultValue("") String token,
        @DefaultValue("2000") int maxTokens,
        @DefaultValue("3") int dailyRuns,
        @DefaultValue("0.25") BigDecimal dailyUsd,
        @DefaultValue("16000") int maxSkeletonChars,
        @DefaultValue("1000") int maxFeedbackChars) {

    public static final int MIN_TOKEN_CHARS = 16;

    public String cleanToken() {
        return token == null ? "" : token.strip();
    }

    public long dailyMicros() {
        return dailyUsd.multiply(BigDecimal.valueOf(1_000_000)).setScale(0, RoundingMode.DOWN).longValueExact();
    }

    public void validate() {
        if (cleanToken().length() < MIN_TOKEN_CHARS) {
            throw new ScoringConfigurationException(
                    "the learner token must be at least " + MIN_TOKEN_CHARS + " characters");
        }
        if (maxTokens < 1 || dailyRuns < 1 || maxSkeletonChars < 1 || maxFeedbackChars < 1
                || dailyUsd == null || dailyUsd.signum() <= 0) {
            throw new ScoringConfigurationException("scoring.learn limits must be greater than 0");
        }
    }
}
