package com.gnemirko.jobapplier.ingestion.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ingestion.fetch")
public record FetchProperties(
        @DefaultValue("JobApplierBot/0.1 (personal job-search tool)") String userAgent,
        @DefaultValue("2s") Duration minDelay,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("30s") Duration totalTimeout,
        @DefaultValue("5242880") long maxBodyBytes,
        @DefaultValue List<String> allowInsecureHosts) {

    public FetchProperties {
        allowInsecureHosts = allowInsecureHosts == null ? List.of() : List.copyOf(allowInsecureHosts);
    }
}
