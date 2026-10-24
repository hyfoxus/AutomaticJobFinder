package com.gnemirko.jobapplier.digest.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.digest.config.DigestEnabled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Ticks on {@code digest.cron} (the value {@code -} turns it off). A tick during a run is skipped. */
@Slf4j
@Component
@DigestEnabled
@RequiredArgsConstructor
public class DigestScheduler {

    private final DigestRunner runner;

    @Scheduled(cron = "${digest.cron:0 0 8 * * *}")
    public void tick() {
        try {
            if (runner.runNow().outcome() == com.gnemirko.jobapplier.digest.dto.RunResult.Outcome.BUSY) {
                log.info("Scheduled digest run skipped: another run is active");
            }
        } catch (Exception e) {
            log.error("Scheduled digest run failed: {}", e.toString(), e);
        }
    }
}
