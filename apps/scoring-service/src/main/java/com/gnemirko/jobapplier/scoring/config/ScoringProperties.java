package com.gnemirko.jobapplier.scoring.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
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
        @DefaultValue Budget budget,
        @DefaultValue Ingestion ingestion) {

    /**
     * @param baseUrl        ingestion service root
     * @param overlap        how far before the cursor each pull starts; must be positive
     * @param chunkSize      jobs handed to scoring at a time
     * @param cron           schedule of the pull; {@code -} disables it
     * @param connectTimeout connect timeout of the pull
     * @param readTimeout    read timeout of the pull
     * @param maxBytes       largest accepted response body
     */
    public record Ingestion(
            @DefaultValue("http://localhost:8081") String baseUrl,
            @DefaultValue("5m") Duration overlap,
            @DefaultValue("50") int chunkSize,
            @DefaultValue("0 15 */6 * * *") String cron,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout,
            @DefaultValue("67108864") long maxBytes) {

        /** Refuses settings that would make the pull unsafe or endless. */
        public void validate() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new ScoringConfigurationException("scoring.ingestion.base-url must be set");
            }
            if (overlap == null || overlap.isZero() || overlap.isNegative()) {
                throw new ScoringConfigurationException("scoring.ingestion.overlap must be greater than 0");
            }
            if (chunkSize < 1) {
                throw new ScoringConfigurationException("scoring.ingestion.chunk-size must be at least 1");
            }
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                    || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
                throw new ScoringConfigurationException("scoring.ingestion timeouts must be greater than 0");
            }
            if (maxBytes < 1) {
                throw new ScoringConfigurationException("scoring.ingestion.max-bytes must be at least 1");
            }
        }
    }

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
