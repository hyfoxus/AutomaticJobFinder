package com.gnemirko.jobapplier.digest.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.digest.config.DigestEnabled;
import com.gnemirko.jobapplier.digest.dto.Digest;
import com.gnemirko.jobapplier.digest.dto.GapKind;
import com.gnemirko.jobapplier.digest.dto.Job;
import com.gnemirko.jobapplier.digest.dto.Tier;
import com.gnemirko.jobapplier.digest.dto.UsageInfo;
import com.gnemirko.jobapplier.digest.util.Escape;

/**
 * Builds the HTML and text bodies. All job and model text goes through {@link Escape}; the subject is a fixed
 * template with counts only; the HTML has no images, scripts or styles from outside, and links only for safe urls.
 */
@Component
@DigestEnabled
public class DigestBuilder {

    /**
     * @param jobs    the jobs to show, already in display order
     * @param waiting qualifying jobs that did not fit and stay for the next run
     * @param usage   today's spend, or null when it could not be read
     */
    public Digest build(UUID id, List<Job> jobs, int waiting, UsageInfo usage) {
        long strong = count(jobs, Tier.STRONG);
        long partial = count(jobs, Tier.PARTIAL);
        long weak = count(jobs, Tier.WEAK);
        String subject = "JobApplier digest: " + jobs.size() + " new jobs (" + strong + " strong, " + partial
                + " partial, " + weak + " weak)";
        return new Digest(id, subject, html(jobs, waiting, usage), text(jobs, waiting, usage), List.copyOf(jobs));
    }

    private static long count(List<Job> jobs, Tier tier) {
        return jobs.stream().filter(j -> j.tier() == tier).count();
    }

    private String html(List<Job> jobs, int waiting, UsageInfo usage) {
        StringBuilder h = new StringBuilder(4096);
        h.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>JobApplier digest</title></head>")
                .append("<body style=\"font-family:sans-serif;max-width:720px;margin:0 auto;padding:12px\">");
        h.append("<h2>").append(jobs.size()).append(" new jobs</h2>");
        for (Job j : jobs) {
            h.append("<div style=\"border-top:1px solid #ccc;padding:10px 0\">");
            h.append("<div><strong>").append(j.tier()).append("</strong> &middot; ");
            Optional<String> url = Escape.safeUrl(j.url());
            String title = Escape.html(orDash(j.title()));
            if (url.isPresent()) {
                h.append("<a href=\"").append(Escape.html(url.get(), Escape.MAX_URL))
                        .append("\" rel=\"noopener noreferrer\">").append(title).append("</a>");
            } else {
                h.append(title);
            }
            h.append("</div>");
            h.append("<div>").append(Escape.html(orDash(j.company()))).append(" &middot; ")
                    .append(Escape.html(orDash(j.location()))).append("</div>");
            if (j.interviewChance() != null) {
                h.append("<div>Interview chance (heuristic): ").append(j.interviewChance()).append("</div>");
            }
            if (j.reason() != null && !j.reason().isBlank()) {
                h.append("<div>").append(Escape.html(j.reason())).append("</div>");
            }
            gapsHtml(h, "Hard gaps", j, GapKind.HARD);
            gapsHtml(h, "Learnable gaps", j, GapKind.LEARNABLE);
            h.append("</div>");
        }
        if (waiting > 0) {
            h.append("<p>").append(waiting).append(" more waiting for the next digest.</p>");
        }
        usageLine(usage).ifPresent(u -> h.append("<p style=\"color:#555\">").append(Escape.html(u, 400)).append("</p>"));
        h.append("</body></html>");
        return h.toString();
    }

    private static void gapsHtml(StringBuilder h, String label, Job j, GapKind kind) {
        String items = j.gaps().stream().filter(g -> g.kind() == kind)
                .map(g -> Escape.html(g.text())).filter(s -> !s.isEmpty()).collect(Collectors.joining("; "));
        if (!items.isEmpty()) {
            h.append("<div>").append(label).append(": ").append(items).append("</div>");
        }
    }

    private String text(List<Job> jobs, int waiting, UsageInfo usage) {
        StringBuilder t = new StringBuilder(2048);
        t.append(jobs.size()).append(" new jobs\n");
        int n = 1;
        for (Job j : jobs) {
            t.append('\n').append(n++).append(". [").append(j.tier()).append("] ")
                    .append(Escape.text(orDash(j.title()))).append('\n');
            t.append("   ").append(Escape.text(orDash(j.company()))).append(" - ")
                    .append(Escape.text(orDash(j.location()))).append('\n');
            Escape.safeUrl(j.url()).ifPresent(u -> t.append("   ").append(Escape.text(u, Escape.MAX_URL)).append('\n'));
            if (j.interviewChance() != null) {
                t.append("   Interview chance (heuristic): ").append(j.interviewChance()).append('\n');
            }
            if (j.reason() != null && !j.reason().isBlank()) {
                t.append("   ").append(Escape.text(j.reason())).append('\n');
            }
            gapsText(t, "Hard gaps", j, GapKind.HARD);
            gapsText(t, "Learnable gaps", j, GapKind.LEARNABLE);
        }
        if (waiting > 0) {
            t.append('\n').append(waiting).append(" more waiting for the next digest.\n");
        }
        usageLine(usage).ifPresent(u -> t.append('\n').append(Escape.text(u, 400)).append('\n'));
        return t.toString();
    }

    private static void gapsText(StringBuilder t, String label, Job j, GapKind kind) {
        String items = j.gaps().stream().filter(g -> g.kind() == kind)
                .map(g -> Escape.text(g.text())).filter(s -> !s.isEmpty()).collect(Collectors.joining("; "));
        if (!items.isEmpty()) {
            t.append("   ").append(label).append(": ").append(items).append('\n');
        }
    }

    /** The raw line; callers escape it for their format. */
    private static Optional<String> usageLine(UsageInfo u) {
        if (u == null) {
            return Optional.empty();
        }
        return Optional.of("Scoring spend today: $" + nz(u.spentUsd()) + " of $" + nz(u.capUsd()) + " ("
                + (u.calls() == null ? "?" : u.calls()) + " calls, budget " + nz(u.budgetState()) + ")");
    }

    private static String nz(String s) {
        return s == null ? "?" : s;
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
