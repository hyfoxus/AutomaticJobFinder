package com.gnemirko.jobapplier.ingestion.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ingestion.fetch")
public record FetchProperties(
        @DefaultValue("JobApplierBot/0.1 (personal job-search tool)") String userAgent,
        @DefaultValue("2s") Duration minDelay,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("30s") Duration totalTimeout,
        @DefaultValue("5242880") long maxBodyBytes,
        @DefaultValue List<String> allowInsecureHosts,
        @DefaultValue("20971520") long maxSitemapBytes) {

    public static final long DEFAULT_MAX_SITEMAP_BYTES = 20L * 1024 * 1024;

    @ConstructorBinding
    public FetchProperties {
        allowInsecureHosts = allowInsecureHosts == null ? List.of() : List.copyOf(allowInsecureHosts);
    }

    public FetchProperties(String userAgent, Duration minDelay, Duration connectTimeout, Duration totalTimeout,
            long maxBodyBytes, List<String> allowInsecureHosts) {
        this(userAgent, minDelay, connectTimeout, totalTimeout, maxBodyBytes, allowInsecureHosts,
                DEFAULT_MAX_SITEMAP_BYTES);
    }
}
