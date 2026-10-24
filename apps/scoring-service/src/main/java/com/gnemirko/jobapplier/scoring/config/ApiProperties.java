package com.gnemirko.jobapplier.scoring.config;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.gnemirko.jobapplier.scoring.exception.ScoringConfigurationException;

/**
 * @param allowedHosts Host header names the API answers to (the DNS-rebinding guard); a bare name, no port,
 *                     scheme or wildcard
 */
@ConfigurationProperties("scoring.api")
public record ApiProperties(@DefaultValue({"localhost", "127.0.0.1"}) List<String> allowedHosts) {

    /** The allow-list, lower-cased. Refuses a blank list, blank entries, wildcards, ports and schemes. */
    public List<String> normalizedHosts() {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new ScoringConfigurationException("scoring.api.allowed-hosts must not be empty");
        }
        return allowedHosts.stream().map(h -> {
            String host = h == null ? "" : h.trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty() || host.contains("*") || host.contains(":") || host.contains("/")
                    || host.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                throw new ScoringConfigurationException("scoring.api.allowed-hosts holds an unusable entry '" + h
                        + "': use bare host names such as localhost, without port, scheme or *");
            }
            return host;
        }).toList();
    }
}
