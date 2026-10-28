package com.gnemirko.jobapplier.ingestion.learner.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the recipe clone run.
 *
 * @param cloneMaxRun time budget of one run, checked before each locale; the rest is skipped
 * @param maxClones   locales tried per run (default 80, hard upper limit 120); extra ones are skipped with "cap"
 */
@ConfigurationProperties("ingestion.learner")
public record CloneProperties(
        @DefaultValue("30m") Duration cloneMaxRun,
        @DefaultValue("80") int maxClones) {

    public static final int HARD_MAX_CLONES = 120;

    public CloneProperties {
        if (cloneMaxRun == null || cloneMaxRun.isNegative()) {
            throw new IllegalArgumentException("ingestion.learner.clone-max-run must not be negative");
        }
        if (maxClones < 1 || maxClones > HARD_MAX_CLONES) {
            throw new IllegalArgumentException("ingestion.learner.max-clones must be 1.." + HARD_MAX_CLONES);
        }
    }
}
