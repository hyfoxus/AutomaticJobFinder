package com.gnemirko.jobapplier.digest.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gnemirko.jobapplier.digest.config.DigestEnabled;
import com.gnemirko.jobapplier.digest.config.DigestProperties;
import com.gnemirko.jobapplier.digest.dto.AssessmentItem;
import com.gnemirko.jobapplier.digest.dto.Digest;
import com.gnemirko.jobapplier.digest.dto.InterviewChance;
import com.gnemirko.jobapplier.digest.dto.Job;
import com.gnemirko.jobapplier.digest.dto.RunResult;
import com.gnemirko.jobapplier.digest.dto.RunResult.Outcome;
import com.gnemirko.jobapplier.digest.dto.Tier;
import com.gnemirko.jobapplier.digest.dto.UsageInfo;
import com.gnemirko.jobapplier.digest.entity.ScanCursorEntity;
import com.gnemirko.jobapplier.digest.exception.DeliveryException;
import com.gnemirko.jobapplier.digest.exception.ScoringApiException;
import com.gnemirko.jobapplier.digest.repository.ScanCursorRepository;
import com.gnemirko.jobapplier.digest.repository.SentAssessmentRepository;
import com.gnemirko.jobapplier.digest.source.ScoringApiClient;

import lombok.extern.slf4j.Slf4j;

/**
 * One digest run: scan the scoring API from the cursor, pick what qualifies and was not mailed, send it,
 * then record it and move the cursor in one transaction. Delivery is at-least-once: if the process dies
 * between the SMTP accept and the record, the next run mails the jobs again, but no job is ever lost.
 * Only one run at a time. In dry-run mode nothing is recorded and the cursor stays where it is.
 */
@Slf4j
@Component
@DigestEnabled
public class DigestRunner {

    static final int PAGE = 200;
    static final int MAX_PAGES = 500;

    private static final Comparator<Job> DISPLAY_ORDER = Comparator
            .comparing((Job j) -> j.tier().ordinal())
            .thenComparing(j -> rank(j.interviewChance()))
            .thenComparing(Job::scoredAt, Comparator.reverseOrder())
            .thenComparing(Job::id, Comparator.reverseOrder());

    private final ScoringApiClient client;
    private final DigestBuilder builder;
    private final DigestMailer mailer;
    private final SentAssessmentRepository sent;
    private final ScanCursorRepository cursors;
    private final DigestProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ReentrantLock lock = new ReentrantLock();

    public DigestRunner(ScoringApiClient client, DigestBuilder builder, DigestMailer mailer,
                        SentAssessmentRepository sent, ScanCursorRepository cursors, DigestProperties props,
                        Clock clock, PlatformTransactionManager txManager) {
        this.client = client;
        this.builder = builder;
        this.mailer = mailer;
        this.sent = sent;
        this.cursors = cursors;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Runs once unless a run is active (then {@code BUSY}). Never throws for expected failures. */
    public RunResult runNow() {
        if (!lock.tryLock()) {
            return RunResult.of(Outcome.BUSY);
        }
        try {
            return run();
        } finally {
            lock.unlock();
        }
    }

    private RunResult run() {
        boolean persist = props.mode() == DigestProperties.Mode.SMTP;
        Instant now = clock.instant();
        Position start = cursors.findById(ScanCursorEntity.ID)
                .map(c -> new Position(c.getScoredAt(), c.getAfterId()))
                .orElseGet(() -> new Position(now.minus(props.initialLookback()).truncatedTo(ChronoUnit.MICROS), 0));

        List<Job> qualifying = new ArrayList<>();
        Position scannedEnd;
        UsageInfo usage;
        try {
            scannedEnd = scan(start, qualifying);
        } catch (ScoringApiException e) {
            log.warn("Digest run skipped, scoring API not usable: {}", e.getMessage());
            return RunResult.of(Outcome.SCORING_UNAVAILABLE);
        }

        qualifying.sort(DISPLAY_ORDER);
        int take = Math.min(props.maxItems(), qualifying.size());
        List<Job> include = List.copyOf(qualifying.subList(0, take));
        List<Job> leftover = qualifying.subList(take, qualifying.size());

        // Next scan starts at the end of this one minus the overlap; with leftovers it must not skip past the
        // oldest leftover. It never goes below where this scan started.
        Position next = null;
        if (scannedEnd != null) {
            next = new Position(scannedEnd.at().minus(props.overlap()), 0);
            if (!leftover.isEmpty()) {
                Position oldest = leftover.stream().map(j -> new Position(j.scoredAt(), j.id()))
                        .min(Comparator.naturalOrder()).orElseThrow();
                Position before = new Position(oldest.at(), oldest.id() - 1);
                next = before.compareTo(next) < 0 ? before : next;
            }
            next = next.compareTo(start) > 0 ? next : null;
        }

        if (include.isEmpty()) {
            if (persist && next != null) {
                moveCursor(next);
            }
            log.info("Digest run: nothing new to send");
            return RunResult.of(Outcome.NOTHING_NEW);
        }

        try {
            usage = client.fetchUsage();
        } catch (ScoringApiException e) {
            log.info("Digest goes out without the usage line: {}", e.getMessage());
            usage = null;
        }
        UUID digestId = UUID.randomUUID();
        Digest digest = builder.build(digestId, include, leftover.size(), usage);
        try {
            mailer.deliver(digest);
        } catch (DeliveryException e) {
            log.warn("Digest not delivered, nothing recorded, the next run retries: {}", e.getMessage());
            return RunResult.of(Outcome.DELIVERY_FAILED);
        }
        if (!persist) {
            return new RunResult(Outcome.DRY_RUN, include.size(), leftover.size());
        }
        final Position cursorAfter = next;
        try {
            tx.executeWithoutResult(status -> {
                for (Job j : include) {
                    sent.record(j.id(), now, digestId);
                }
                if (cursorAfter != null) {
                    cursors.move(cursorAfter.at(), cursorAfter.id());
                }
            });
        } catch (RuntimeException e) {
            log.error("Digest {} was accepted by the mail server but could not be recorded; the next run will "
                    + "send these jobs again: {}", digestId, e.toString());
            return new RunResult(Outcome.RECORD_FAILED, include.size(), leftover.size());
        }
        log.info("Digest {} sent with {} jobs, {} waiting", digestId, include.size(), leftover.size());
        return new RunResult(Outcome.SENT, include.size(), leftover.size());
    }

    /** Pages through SCORED rows from {@code start}; fills {@code qualifying}; returns the last position seen, or null. */
    private Position scan(Position start, List<Job> qualifying) {
        Set<Tier> tiers = Set.copyOf(props.tiers());
        Position pos = start;
        Position end = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<AssessmentItem> items = client.fetchScored(pos.at(), pos.id(), PAGE);
            Position last = null;
            List<Job> jobs = new ArrayList<>();
            for (AssessmentItem item : items) {
                if (item != null && item.id() != null && item.scoredAt() != null) {
                    last = new Position(item.scoredAt(), item.id());
                }
                Job.from(item).ifPresentOrElse(jobs::add, () -> log.info("Skipping assessment {}: unusable row",
                        item == null ? null : item.id()));
            }
            if (last == null || last.compareTo(pos) <= 0) {
                if (!items.isEmpty()) {
                    log.warn("Scoring API page did not move past {}; scan stopped", pos);
                }
                break;
            }
            List<Job> inTier = jobs.stream().filter(j -> tiers.contains(j.tier())).toList();
            if (!inTier.isEmpty()) {
                Set<Long> already = sent.findSentIds(inTier.stream().map(Job::id).collect(Collectors.toSet()));
                inTier.stream().filter(j -> !already.contains(j.id())).forEach(qualifying::add);
            }
            end = last;
            pos = last;
            if (items.size() < PAGE) {
                break;
            }
            if (page == MAX_PAGES - 1) {
                log.warn("Scan stopped after {} pages without reaching the end; the rest is read on the next run",
                        MAX_PAGES);
            }
        }
        return end;
    }

    private void moveCursor(Position p) {
        try {
            tx.executeWithoutResult(status -> cursors.move(p.at(), p.id()));
        } catch (RuntimeException e) {
            log.warn("Could not move the scan cursor: {}", e.toString());
        }
    }

    private static int rank(InterviewChance c) {
        if (c == null) {
            return 3;
        }
        return switch (c) {
            case LIKELY -> 0;
            case POSSIBLE -> 1;
            case UNLIKELY -> 2;
        };
    }

    /** A place in scoring's order: scoredAt, then id. */
    record Position(Instant at, long id) implements Comparable<Position> {
        @Override
        public int compareTo(Position o) {
            int c = at.compareTo(o.at);
            return c != 0 ? c : Long.compare(id, o.id);
        }
    }
}
