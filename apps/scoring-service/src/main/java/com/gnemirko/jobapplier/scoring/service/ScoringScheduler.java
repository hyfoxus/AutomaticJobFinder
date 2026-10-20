package com.gnemirko.jobapplier.scoring.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Ticks on {@code scoring.ingestion.cron} (the value {@code -} turns it off). A tick during a run is skipped. */
@Slf4j
@Component
@ScoringEnabled
@RequiredArgsConstructor
public class ScoringScheduler {

    private final ScoringRunner runner;

    @Scheduled(cron = "${scoring.ingestion.cron:0 15 */6 * * *}")
    public void tick() {
        try {
            if (!runner.runNow()) {
                log.info("Scheduled scoring run skipped: another run is active");
            }
        } catch (Exception e) {
            log.error("Scheduled scoring run failed: {}", e.toString(), e);
        }
    }
}
