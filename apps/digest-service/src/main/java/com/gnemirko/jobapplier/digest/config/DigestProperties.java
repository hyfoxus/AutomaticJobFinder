package com.gnemirko.jobapplier.digest.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.gnemirko.jobapplier.digest.dto.Tier;

/**
 * All digest settings. The SMTP password is deliberately not here: it is read from the environment when the
 * mail sender is built, so no record or {@code toString} can leak it.
 *
 * @param mode           dry-run writes files and records nothing; smtp sends and records
 * @param outDir         where dry-run files go
 * @param tiers          tiers that qualify
 * @param maxItems       jobs per digest
 * @param overlap        how far before the end of a scan the next one starts; must be positive
 * @param initialLookback how far back the very first smtp run looks
 * @param cron           schedule; {@code -} disables it
 */
@ConfigurationProperties("digest")
public record DigestProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("dry-run") Mode mode,
        @DefaultValue("./digest-out") String outDir,
        @DefaultValue({"STRONG", "PARTIAL"}) List<Tier> tiers,
        @DefaultValue("50") int maxItems,
        @DefaultValue("5m") Duration overlap,
        @DefaultValue("7d") Duration initialLookback,
        @DefaultValue("0 0 8 * * *") String cron,
        @DefaultValue Scoring scoring,
        @DefaultValue Smtp smtp) {

    public enum Mode { DRY_RUN, SMTP }

    /** The scoring REST API. */
    public record Scoring(
            @DefaultValue("http://localhost:8082") String baseUrl,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout,
            @DefaultValue("8388608") long maxBytes) {
    }

    /** Mail settings without the password. */
    public record Smtp(
            String host,
            @DefaultValue("587") int port,
            String username,
            String from,
            List<String> to,
            @DefaultValue("false") boolean ssl,
            @DefaultValue("false") boolean allowPlaintext) {

        @Override
        public String toString() {
            return "Smtp[host=" + host + ", port=" + port + ", from=" + from + ", ssl=" + ssl + "]";
        }
    }
}
