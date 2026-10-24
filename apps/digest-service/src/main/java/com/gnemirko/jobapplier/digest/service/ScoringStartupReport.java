package com.gnemirko.jobapplier.digest.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.digest.config.DigestEnabled;
import com.gnemirko.jobapplier.digest.config.DigestProperties;
import com.gnemirko.jobapplier.digest.exception.ScoringApiException;
import com.gnemirko.jobapplier.digest.source.ScoringApiClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Logs once at startup whether the scoring API answers. It never stops the service. */
@Slf4j
@Component
@DigestEnabled
@RequiredArgsConstructor
public class ScoringStartupReport {

    private final ScoringApiClient client;
    private final DigestProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        try {
            client.fetchUsage();
            log.info("Digest enabled in {} mode; scoring API at {} answers", props.mode(), props.scoring().baseUrl());
        } catch (ScoringApiException e) {
            log.warn("Digest enabled in {} mode; scoring API at {} is not usable yet (scoring must be enabled "
                    + "and reachable): {}", props.mode(), props.scoring().baseUrl(), e.getMessage());
        }
    }
}
