package com.gnemirko.jobapplier.scoring.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.dto.Profile;
import com.gnemirko.jobapplier.scoring.util.TimeLimitedCharSequence;
import com.gnemirko.jobapplier.scoring.util.TimeLimitedCharSequence.MatchTimeoutException;

import lombok.extern.slf4j.Slf4j;

/** The user's non-negotiables, applied in code before any model call. */
@Slf4j
@Component
@ScoringEnabled
public class HardFilter {

    private static final long MATCH_LIMIT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int MAX_REASON = 200;

    private final List<Pattern> excludedTitles;
    private final List<String> excludedCompanies;
    private final List<String> allowedLocations;

    public HardFilter(Profile profile) {
        this.excludedTitles = profile.filters().excludeTitleRegex().stream()
                .map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)).toList();
        this.excludedCompanies = profile.filters().excludeCompanies().stream().map(HardFilter::norm).toList();
        this.allowedLocations = profile.filters().allowedLocationKeywords().stream().map(HardFilter::norm).toList();
    }

    /** Returns the reason the job is rejected, or empty when it passes. */
    public Optional<String> reject(JobPosting job) {
        String title = job.title() == null ? "" : job.title();
        for (Pattern p : excludedTitles) {
            try {
                if (p.matcher(TimeLimitedCharSequence.limited(title, MATCH_LIMIT_NANOS)).find()) {
                    return Optional.of(cut("title matches excluded pattern " + p.pattern()));
                }
            } catch (MatchTimeoutException e) {
                log.warn("Title filter {} timed out; rejecting the job (fail closed)", p.pattern());
                return Optional.of(cut("title filter timed out (rejected, fail closed): " + p.pattern()));
            }
        }
        if (job.company() != null && excludedCompanies.contains(norm(job.company()))) {
            return Optional.of(cut("company is excluded: " + job.company().strip()));
        }
        if (!allowedLocations.isEmpty() && job.location() != null && !job.location().isBlank()) {
            String loc = norm(job.location());
            if (allowedLocations.stream().noneMatch(loc::contains)) {
                return Optional.of(cut("location matches no allowed keyword: " + job.location().strip()));
            }
        }
        return Optional.empty();
    }

    private static String norm(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
    }

    private static String cut(String s) {
        return s.length() <= MAX_REASON ? s : s.substring(0, MAX_REASON);
    }
}
