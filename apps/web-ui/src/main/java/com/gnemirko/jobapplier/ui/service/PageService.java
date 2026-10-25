package com.gnemirko.jobapplier.ui.service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ui.config.UiEnabled;
import com.gnemirko.jobapplier.ui.dto.AssessmentItem;
import com.gnemirko.jobapplier.ui.dto.AssessmentPage;
import com.gnemirko.jobapplier.ui.dto.AssessmentStatus;
import com.gnemirko.jobapplier.ui.dto.BudgetState;
import com.gnemirko.jobapplier.ui.dto.DashboardView;
import com.gnemirko.jobapplier.ui.dto.GapItem;
import com.gnemirko.jobapplier.ui.dto.GapKind;
import com.gnemirko.jobapplier.ui.dto.InterviewChance;
import com.gnemirko.jobapplier.ui.dto.ListView;
import com.gnemirko.jobapplier.ui.dto.RowView;
import com.gnemirko.jobapplier.ui.dto.Tier;
import com.gnemirko.jobapplier.ui.dto.UsageInfo;
import com.gnemirko.jobapplier.ui.exception.ScoringApiException;
import com.gnemirko.jobapplier.ui.source.ScoringApiClient;
import com.gnemirko.jobapplier.ui.util.Escape;
import com.gnemirko.jobapplier.ui.util.SafeUrl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the view models. Everything that came from scoring is untrusted: text is cleaned and capped here, enums
 * become fixed labels, and an exception's text never reaches a view.
 */
@Slf4j
@Service
@UiEnabled
@RequiredArgsConstructor
public class PageService {

    public static final int PAGE_SIZE = 50;
    public static final int RECENT = 200;
    /** The scoring window is 10000 rows, so with 50 per page the last page is 199. */
    public static final int LAST_PAGE_WITH_NEXT = 199;

    private static final int MAX_GAPS = 20;
    private static final Pattern MONEY = Pattern.compile("\\d{1,12}(\\.\\d{1,6})?");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final ScoringApiClient scoring;

    public DashboardView dashboard() {
        boolean down = false;
        String spent = "?";
        String cap = "?";
        String budget = "unknown";
        String calls = "?";
        try {
            UsageInfo u = scoring.fetchUsage();
            spent = money(u.spentUsd());
            cap = money(u.capUsd());
            budget = budgetLabel(u.budgetState());
            calls = u.calls() == null || u.calls() < 0 ? "?" : Long.toString(u.calls());
        } catch (ScoringApiException e) {
            down = true;
            log.warn("Usage not available: {}", e.getMessage());
        }
        String total = "0";
        List<DashboardView.Count> counts = new ArrayList<>();
        try {
            AssessmentPage recent = scoring.fetchAssessments(null, null, 0, RECENT);
            Map<AssessmentStatus, Integer> byStatus = new EnumMap<>(AssessmentStatus.class);
            Map<Tier, Integer> byTier = new EnumMap<>(Tier.class);
            int unknown = 0;
            for (AssessmentItem i : recent.items()) {
                if (i.status() == null) {
                    unknown++;
                    continue;
                }
                byStatus.merge(i.status(), 1, Integer::sum);
                if (i.status() == AssessmentStatus.SCORED && i.tier() != null) {
                    byTier.merge(i.tier(), 1, Integer::sum);
                }
            }
            total = recent.items().size() >= RECENT || recent.hasMore() ? RECENT + "+" : Integer.toString(recent.items().size());
            for (Tier t : Tier.values()) {
                counts.add(new DashboardView.Count("Scored, tier " + tier(t), byTier.getOrDefault(t, 0)));
            }
            for (AssessmentStatus s : AssessmentStatus.values()) {
                counts.add(new DashboardView.Count(statusLabel(s), byStatus.getOrDefault(s, 0)));
            }
            if (unknown > 0) {
                counts.add(new DashboardView.Count("Unknown status", unknown));
            }
        } catch (ScoringApiException e) {
            down = true;
            log.warn("Recent rows not available: {}", e.getMessage());
        }
        return new DashboardView(down, spent, cap, budget, calls, total, counts);
    }

    public ListView list(AssessmentStatus status, Tier tier, int page) {
        try {
            AssessmentPage answer = scoring.fetchAssessments(status, tier, page, PAGE_SIZE);
            List<RowView> rows = answer.items().stream().limit(PAGE_SIZE).map(PageService::row).toList();
            return new ListView(false, status.name(), tier == null ? "" : tier.name(), page, page > 0,
                    answer.hasMore() && page < LAST_PAGE_WITH_NEXT, rows);
        } catch (ScoringApiException e) {
            log.warn("Assessments not available: {}", e.getMessage());
            return new ListView(true, status.name(), tier == null ? "" : tier.name(), page, page > 0, false, List.of());
        }
    }

    static RowView row(AssessmentItem i) {
        List<String> hard = new ArrayList<>();
        List<String> learnable = new ArrayList<>();
        if (i.gaps() != null) {
            int n = 0;
            for (GapItem g : i.gaps()) {
                if (g == null || g.text() == null || n++ >= MAX_GAPS) {
                    continue;
                }
                String text = Escape.text(g.text());
                if (text.isEmpty()) {
                    continue;
                }
                // An unknown kind counts as HARD, the cautious reading.
                (g.kind() == GapKind.LEARNABLE ? learnable : hard).add(text);
            }
        }
        int met = i.metMustHaves() == null ? 0 : (int) i.metMustHaves().stream().filter(java.util.Objects::nonNull).count();
        String timeLabel = i.scoredAt() != null ? "scored" : "first seen";
        java.time.Instant when = i.scoredAt() != null ? i.scoredAt() : i.firstSeenAt();
        String safe = null;
        String host = "";
        var uri = SafeUrl.parse(i.url());
        if (uri.isPresent()) {
            safe = uri.get().toString();
            host = Escape.text(uri.get().getHost(), 100);
        }
        return new RowView(Escape.text(i.title()), Escape.text(i.company()), Escape.text(i.location()),
                i.status() == null ? "unknown" : statusLabel(i.status()), i.tier() == null ? "none" : tier(i.tier()),
                chance(i.interviewChance()), Escape.text(i.reason(), Escape.MAX_REASON), hard, learnable, met,
                timeLabel, when == null ? "unknown" : TIME.format(when), safe, host);
    }

    static String money(String raw) {
        return raw != null && MONEY.matcher(raw.trim()).matches() ? raw.trim() : "?";
    }

    static String budgetLabel(BudgetState s) {
        if (s == null) {
            return "unknown";
        }
        return switch (s) {
            case OK -> "within budget";
            case NEAR_CAP -> "near the cap";
            case CAP_REACHED -> "cap reached";
        };
    }

    static String tier(Tier t) {
        return t.name().toLowerCase(Locale.ROOT);
    }

    static String chance(InterviewChance c) {
        return c == null ? "unknown" : c.name().toLowerCase(Locale.ROOT);
    }

    static String statusLabel(AssessmentStatus s) {
        return switch (s) {
            case PENDING -> "Pending";
            case SCORED -> "Scored";
            case REJECTED_FILTER -> "Rejected by filter";
            case FAILED -> "Failed";
            case FAILED_PERMANENT -> "Failed for good";
            case UNSCORED_BUDGET -> "Left unscored (budget)";
        };
    }
}
