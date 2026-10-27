package com.gnemirko.jobapplier.ingestion.learner.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

/**
 * Settings of the recipe learner.
 *
 * @param enabled         master switch; off means no learner bean exists
 * @param allowedHosts    exact host names the learner may fetch; listing a host records that its terms and robots.txt
 *                        were reviewed
 * @param maxInputChars   cap on the page skeleton sent to the model
 * @param scoringBaseUrl  base URL of the scoring service
 * @param tokenFile       file holding the shared secret sent as X-Learner-Token
 * @param outputDir       review folder for proposed recipes (must not be the loaded recipes folder)
 * @param connectTimeout  connect timeout towards scoring
 * @param readTimeout     read timeout towards scoring (above scoring's own 60 s model timeout)
 * @param maxReplyBytes   cap on the size of scoring's answer
 * @param sampleMaxRun    time budget of the sanity run
 */
@ConfigurationProperties("ingestion.learner")
public record LearnerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue List<String> allowedHosts,
        @DefaultValue("12000") int maxInputChars,
        @DefaultValue("http://localhost:8082") String scoringBaseUrl,
        @DefaultValue("/run/secrets/learner_token") String tokenFile,
        @DefaultValue("./recipes-proposed") String outputDir,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("90s") Duration readTimeout,
        @DefaultValue("65536") long maxReplyBytes,
        @DefaultValue("2m") Duration sampleMaxRun) {

    public LearnerProperties {
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream()
                .filter(h -> h != null && !h.isBlank())
                .map(UrlPolicy::normalizeHost)
                .distinct()
                .toList();
        if (maxInputChars < 500 || maxInputChars > 16000) {
            throw new IllegalArgumentException("ingestion.learner.max-input-chars must be 500..16000 (scoring refuses more)");
        }
    }

    public boolean isAllowed(String host) {
        return host != null && allowedHosts.contains(UrlPolicy.normalizeHost(host));
    }
}
