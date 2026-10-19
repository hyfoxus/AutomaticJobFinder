package com.gnemirko.jobapplier.scoring.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.gnemirko.jobapplier.scoring.exception.ScoringConfigurationException;

/** All scoring settings. Validated at startup by {@link ScoringConfig} when scoring is enabled. */
@ConfigurationProperties("scoring")
public record ScoringProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("./profile.yml") String profilePath,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue Llm llm,
        @DefaultValue Budget budget) {

    /**
     * @param model                name sent to the provider and key into {@code prices}
     * @param free                 true for local models: every call costs $0
     * @param maxTokens            output cap per call, also used for the worst-case reservation
     * @param maxDescriptionChars  job description is cut to this length before it is sent
     * @param prices               USD per million tokens, keyed by model name
     * @param temperature          sampling temperature; null (the default) leaves it out of the request
     */
    public record Llm(
            @DefaultValue("") String model,
            @DefaultValue("false") boolean free,
            @DefaultValue("600") int maxTokens,
            @DefaultValue("6000") int maxDescriptionChars,
            @DefaultValue Map<String, Price> prices,
            Double temperature) {
    }

    /** USD per million tokens; one micro-dollar per token per dollar of price. */
    public record Price(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }

    /**
     * @param dailyUsd cap per day
     * @param zone     zone that decides when a day starts; {@code system} is the JVM zone
     */
    public record Budget(
            @DefaultValue("1.00") BigDecimal dailyUsd,
            @DefaultValue("system") String zone) {

        public ZoneId zoneId() {
            if (zone == null || zone.isBlank() || zone.equalsIgnoreCase("system")) {
                return ZoneId.systemDefault();
            }
            try {
                return ZoneId.of(zone.trim());
            } catch (DateTimeException e) {
                throw new ScoringConfigurationException("scoring.budget.zone is not a time zone: " + zone, e);
            }
        }

        /** The cap in micro-dollars (rounded down). */
        public long capMicros() {
            return dailyUsd.multiply(BigDecimal.valueOf(1_000_000)).setScale(0, RoundingMode.DOWN).longValueExact();
        }
    }
}
