package com.gnemirko.jobapplier.scoring.controller;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.dto.AssessmentView;
import com.gnemirko.jobapplier.scoring.entity.AssessmentEntity;
import com.gnemirko.jobapplier.scoring.exception.BadRequestException;
import com.gnemirko.jobapplier.scoring.repository.AssessmentRepository;

import lombok.RequiredArgsConstructor;

@RestController
@ScoringEnabled
@RequiredArgsConstructor
@RequestMapping(path = "/api/assessments", produces = MediaType.APPLICATION_JSON_VALUE)
public class AssessmentController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final AssessmentRepository assessments;

    /**
     * Newest first. {@code status} is optional and case-insensitive; {@code limit} is 1..200 (default 50).
     * With {@code scoredSince} (ISO-8601 instant, kept to microseconds) the answer is SCORED rows only, oldest
     * scored first ({@code scoredAt asc, id asc}), from that instant on; {@code afterId} skips ids up to it on rows
     * scored at exactly that instant.
     */
    @GetMapping
    public List<AssessmentView> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String limit,
                                     @RequestParam(required = false) String scoredSince,
                                     @RequestParam(required = false) String afterId) {
        int size = parseLimit(limit);
        if (scoredSince != null) {
            if (status != null && !status.isBlank() && parseStatus(status) != AssessmentStatus.SCORED) {
                throw new BadRequestException("scoredSince only applies to status SCORED");
            }
            return assessments.findScoredAfter(parseSince(scoredSince), parseAfterId(afterId), PageRequest.of(0, size))
                    .stream().map(AssessmentView::of).toList();
        }
        if (afterId != null) {
            throw new BadRequestException("afterId needs scoredSince");
        }
        Pageable page = PageRequest.of(0, size, Sort.by(Sort.Order.desc("firstSeenAt"), Sort.Order.desc("id")));
        Iterable<AssessmentEntity> rows = status == null || status.isBlank()
                ? assessments.findAllBy(page)
                : assessments.findByStatus(parseStatus(status), page);
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false).map(AssessmentView::of).toList();
    }

    private static java.time.Instant parseSince(String raw) {
        try {
            return java.time.Instant.parse(raw.trim()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        } catch (java.time.DateTimeException e) {
            throw new BadRequestException("scoredSince must be an ISO-8601 instant such as 2026-01-01T00:00:00Z");
        }
    }

    private static long parseAfterId(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long id = Long.parseLong(raw.trim());
            if (id < 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException e) {
            throw new BadRequestException("afterId must be a whole number, 0 or more");
        }
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        java.math.BigInteger n;
        try {
            n = new java.math.BigInteger(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("limit must be a whole number from 1 to " + MAX_LIMIT);
        }
        if (n.signum() < 1) {
            throw new BadRequestException("limit must be at least 1");
        }
        return n.compareTo(java.math.BigInteger.valueOf(MAX_LIMIT)) > 0 ? MAX_LIMIT : n.intValue();
    }

    private static AssessmentStatus parseStatus(String raw) {
        try {
            return AssessmentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("unknown status; use one of " + java.util.Arrays.toString(AssessmentStatus.values()));
        }
    }
}
