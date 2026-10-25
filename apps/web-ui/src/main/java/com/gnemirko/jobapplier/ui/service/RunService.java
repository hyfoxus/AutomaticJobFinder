package com.gnemirko.jobapplier.ui.service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ui.config.UiEnabled;
import com.gnemirko.jobapplier.ui.config.UiProperties;
import com.gnemirko.jobapplier.ui.dto.RunOutcome;
import com.gnemirko.jobapplier.ui.exception.ScoringApiException;
import com.gnemirko.jobapplier.ui.source.ScoringApiClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Starts a scoring run. A click inside the cooldown (default 60 s, also after a failed attempt) is answered BUSY without calling scoring, so a
 * double click or a loop of requests cannot hammer it; the daily cost cap in scoring stays the real safety net.
 * The call is made once and never retried.
 */
@Slf4j
@Service
@UiEnabled
public class RunService {

    private final ScoringApiClient scoring;
    private final Clock clock;
    private final UiProperties props;
    private final AtomicReference<Instant> lastAttempt = new AtomicReference<>();

    public RunService(ScoringApiClient scoring, Clock clock, UiProperties props) {
        this.scoring = scoring;
        this.clock = clock;
        this.props = props;
    }

    public RunOutcome trigger() {
        Instant now = clock.instant();
        Instant previous = lastAttempt.get();
        if (previous != null && now.isBefore(previous.plus(props.runCooldown()))) {
            return RunOutcome.BUSY;
        }
        if (!lastAttempt.compareAndSet(previous, now)) {
            return RunOutcome.BUSY;
        }
        try {
            return scoring.startRun();
        } catch (ScoringApiException e) {
            // The cooldown stays after a failed attempt too: a broken scoring service is not hammered.
            log.warn("Run request to scoring failed: {}", e.getMessage());
            return RunOutcome.FAILED;
        }
    }
}
