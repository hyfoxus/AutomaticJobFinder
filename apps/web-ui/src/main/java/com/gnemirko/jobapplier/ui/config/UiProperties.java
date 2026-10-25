package com.gnemirko.jobapplier.ui.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.gnemirko.jobapplier.ui.exception.UiConfigurationException;

/**
 * @param allowedHosts Host header names the UI answers to (the DNS-rebinding guard); a bare name, no port,
 *                     scheme or wildcard
 * @param runCooldown  minimum time between two accepted run requests
 */
@ConfigurationProperties("ui")
public record UiProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"localhost", "127.0.0.1"}) List<String> allowedHosts,
        @DefaultValue("60s") Duration runCooldown,
        @DefaultValue Scoring scoring) {

    /** The scoring REST API. */
    public record Scoring(
            @DefaultValue("http://localhost:8082") String baseUrl,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("8388608") long maxBytes) {
    }

    /** The allow-list, lower-cased. Refuses a blank list, blank entries, wildcards, ports and schemes. */
    public List<String> normalizedHosts() {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new UiConfigurationException("ui.allowed-hosts must not be empty");
        }
        return allowedHosts.stream().map(h -> {
            String host = h == null ? "" : h.trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty() || host.contains("*") || host.contains(":") || host.contains("/")
                    || host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                throw new UiConfigurationException("ui.allowed-hosts holds an unusable entry '" + h
                        + "': use bare host names such as localhost, without port, scheme or *");
            }
            return host;
        }).toList();
    }
}
