package com.gnemirko.jobapplier.scoring.controller;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.dto.AssessmentView;
import com.gnemirko.jobapplier.scoring.dto.Tier;
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

    /** Largest offset served; with the page size it keeps page * limit + limit within 10000 rows. */
    static final int MAX_OFFSET = 9950;
    static final int MAX_WINDOW = 10000;

    /**
     * Newest first. {@code status} is optional and case-insensitive; {@code limit} is 1..200 (default 50).
     * {@code tier} (case-insensitive) needs status SCORED or no status; {@code page} (0 or more, default 0) skips
     * {@code page * limit} rows, up to a window of 10000 rows. Offset paging shifts when rows arrive between
     * two requests. The header {@code X-Has-More} says whether another page follows.
     * With {@code scoredSince} (ISO-8601 instant, kept to microseconds) the answer is SCORED rows only, oldest
     * scored first ({@code scoredAt asc, id asc}), from that instant on; {@code afterId} skips ids up to it on rows
     * scored at exactly that instant. {@code tier} and {@code page} do not apply to that form.
     */
    @GetMapping
    public ResponseEntity<List<AssessmentView>> list(@RequestParam(required = false) String status,
                                                     @RequestParam(required = false) String limit,
                                                     @RequestParam(required = false) String scoredSince,
                                                     @RequestParam(required = false) String afterId,
                                                     @RequestParam(required = false) String tier,
                                                     @RequestParam(required = false) String page) {
        int size = parseLimit(limit);
        boolean hasTier = tier != null && !tier.isBlank();
        if (scoredSince != null) {
            if (hasTier || (page != null && !page.isBlank())) {
                throw new BadRequestException("tier and page do not apply with scoredSince");
            }
            if (status != null && !status.isBlank() && parseStatus(status) != AssessmentStatus.SCORED) {
                throw new BadRequestException("scoredSince only applies to status SCORED");
            }
            return ResponseEntity.ok(assessments.findScoredAfter(parseSince(scoredSince), parseAfterId(afterId), PageRequest.of(0, size))
                    .stream().map(AssessmentView::of).toList());
        }
        if (afterId != null) {
            throw new BadRequestException("afterId needs scoredSince");
        }
        boolean hasStatus = status != null && !status.isBlank();
        AssessmentStatus st = hasStatus ? parseStatus(status) : null;
        Tier tr = hasTier ? parseTier(tier) : null;
        if (tr != null && st != null && st != AssessmentStatus.SCORED) {
            throw new BadRequestException("tier only applies to status SCORED");
        }
        int pageNo = parsePage(page, size);
        Pageable pageable = PageRequest.of(pageNo, size,
                Sort.by(Sort.Order.desc("firstSeenAt"), Sort.Order.desc("id")));
        Slice<AssessmentEntity> rows;
        if (tr != null) {
            rows = st == null ? assessments.findByTier(tr, pageable)
                    : assessments.findByStatusAndTier(st, tr, pageable);
        } else {
            rows = st == null ? assessments.findAllBy(pageable) : assessments.findByStatus(st, pageable);
        }
        return ResponseEntity.ok().header("X-Has-More", Boolean.toString(rows.hasNext()))
                .body(rows.getContent().stream().map(AssessmentView::of).toList());
    }

    private static int parsePage(String raw, int size) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        java.math.BigInteger n;
        try {
            n = new java.math.BigInteger(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("page must be a whole number, 0 or more");
        }
        if (n.signum() < 0) {
            throw new BadRequestException("page must be a whole number, 0 or more");
        }
        java.math.BigInteger offset = n.multiply(java.math.BigInteger.valueOf(size));
        if (offset.compareTo(java.math.BigInteger.valueOf(MAX_OFFSET)) > 0
                || offset.add(java.math.BigInteger.valueOf(size)).compareTo(java.math.BigInteger.valueOf(MAX_WINDOW)) > 0) {
            throw new BadRequestException("page * limit is beyond the first " + MAX_WINDOW + " rows");
        }
        return n.intValue();
    }

    private static Tier parseTier(String raw) {
        try {
            return Tier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("unknown tier; use one of " + java.util.Arrays.toString(Tier.values()));
        }
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
