package com.gnemirko.jobapplier.ingestion.learner.service;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.learner.config.CloneProperties;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.dto.CloneStarted;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnBusyException;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnRejectedException;
import com.gnemirko.jobapplier.ingestion.learner.source.SampleRunner;
import com.gnemirko.jobapplier.ingestion.learner.util.Text;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.service.RecipeRegistry;
import com.gnemirko.jobapplier.ingestion.service.RecipeValidator;
import com.gnemirko.jobapplier.ingestion.source.recipe.SitemapReader;
import com.gnemirko.jobapplier.ingestion.util.UrlCanonicalizer;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Copies one working sitemap recipe to every sibling locale listed in the site's sitemap index, sample-tests each copy
 * and saves the passing ones disabled in the review folder, with one report that names every index entry. No model call.
 * Shares the single run slot with the learner.
 */
@Slf4j
@Service
@LearnerEnabled
public class RecipeCloner {

    public static final int MAX_RUNS_PER_DAY = 2;
    static final int MAX_REPORT_LINES = 2000;
    static final int REPORT_CHARS = 600_000;
    private static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z0-9-]{1,64}");
    private static final Pattern RECIPE_NAME = Pattern.compile("[a-z0-9-]{1,64}");
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}(-[a-z0-9]{2,4})?");
    private static final Pattern NESTED_INDEX = Pattern.compile(".*sitemap[-_]?index.*");

    enum Status { PENDING, COVERED, FAILED, SKIPPED }

    private static final class Line {
        final String loc;
        final String locale;
        Status status = Status.PENDING;
        String detail = "";

        Line(String loc, String locale) {
            this.loc = loc;
            this.locale = locale;
        }

        void set(Status s, String d) {
            status = s;
            detail = d;
        }
    }

    private final LearnerProperties props;
    private final CloneProperties cloneProps;
    private final RecipeRegistry registry;
    private final RecipeValidator validator;
    private final PoliteFetcher fetcher;
    private final UrlPolicy policy;
    private final SampleRunner sampler;
    private final ProposalWriter writer;
    private final RecipeLearner learner;
    private final Clock clock;
    private final Executor executor;
    private final ExecutorService owned;
    private LocalDate day;
    private int runsToday;

    @Autowired
    public RecipeCloner(LearnerProperties props, CloneProperties cloneProps, RecipeRegistry registry,
            RecipeValidator validator, PoliteFetcher fetcher, UrlPolicy policy, SampleRunner sampler,
            ProposalWriter writer, RecipeLearner learner, Clock clock) {
        this(props, cloneProps, registry, validator, fetcher, policy, sampler, writer, learner, clock, null);
    }

    /** With an executor the caller owns (tests pass a direct one); without, a single daemon thread. */
    public RecipeCloner(LearnerProperties props, CloneProperties cloneProps, RecipeRegistry registry,
            RecipeValidator validator, PoliteFetcher fetcher, UrlPolicy policy, SampleRunner sampler,
            ProposalWriter writer, RecipeLearner learner, Clock clock, Executor executor) {
        this.props = props;
        this.cloneProps = cloneProps;
        this.registry = registry;
        this.validator = validator;
        this.fetcher = fetcher;
        this.policy = policy;
        this.sampler = sampler;
        this.writer = writer;
        this.learner = learner;
        this.clock = clock;
        if (executor == null) {
            this.owned = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "recipe-cloner");
                t.setDaemon(true);
                return t;
            });
            this.executor = owned;
        } else {
            this.owned = null;
            this.executor = executor;
        }
    }

    @PreDestroy
    void shutdown() {
        if (owned != null) {
            owned.shutdownNow();
        }
    }

    /** What a valid request resolved to. */
    private record Plan(Recipe template, URI templateSitemap, String templateLocale, URI index) {
    }

    /** Checks everything that needs no fetch (nothing is fetched before it passes), then starts the run. */
    public CloneStarted start(String templateName, String indexUrl) {
        Plan plan = plan(templateName, indexUrl);
        if (!learner.tryAcquireSlot()) {
            throw new LearnBusyException();
        }
        ProposalWriter.Claim claim;
        try {
            synchronized (this) {
                LocalDate today = LocalDate.now(clock);
                if (!today.equals(day)) {
                    day = today;
                    runsToday = 0;
                }
                if (runsToday >= MAX_RUNS_PER_DAY) {
                    learner.releaseSlot();
                    throw new LearnRejectedException("at most " + MAX_RUNS_PER_DAY + " clone runs a day");
                }
                claim = writer.claimReport(plan.template().name() + "-clone");
                runsToday++;
            }
        } catch (LearnRejectedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            learner.releaseSlot();
            log.warn("Cloner cannot reserve a report file: {}", e.toString());
            throw new IllegalStateException("cannot write to the review folder", e);
        }
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try {
            executor.execute(() -> {
                try {
                    run(runId, plan, claim);
                } finally {
                    learner.releaseSlot();
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (this) {
                runsToday--;
            }
            writer.discard(claim);
            learner.releaseSlot();
            throw new IllegalStateException("the cloner is shutting down", e);
        }
        return new CloneStarted(runId, claim.name());
    }

    private Plan plan(String templateName, String indexUrl) {
        if (templateName == null || !TEMPLATE_NAME.matcher(templateName).matches()) {
            throw new LearnRejectedException("template must match [a-z0-9-]{1,64}");
        }
        Recipe template = registry.recipes().stream()
                .filter(r -> r.name().equalsIgnoreCase(templateName)).findFirst()
                .orElseThrow(() -> new LearnRejectedException("template is not a loaded recipe"));
        if (!Recipe.STRATEGY_SITEMAP.equals(template.strategy()) || template.sitemap() == null) {
            throw new LearnRejectedException("template is not a sitemap recipe");
        }
        URI sitemap;
        try {
            sitemap = URI.create(template.sitemap().url());
        } catch (IllegalArgumentException e) {
            throw new LearnRejectedException("template sitemap url is not usable");
        }
        if (sitemap.getHost() == null || !props.isAllowed(sitemap.getHost())) {
            throw new LearnRejectedException("template host is not in ingestion.learner.allowed-hosts");
        }
        String locale = firstSegment(sitemap.getRawPath());
        String marker = locale == null ? null : "/" + locale + "/";
        if (locale == null || !LOCALE.matcher(locale).matches()
                || !sitemap.getRawPath().startsWith(marker)
                || count(template.sitemap().url(), marker) != 1
                || count(template.sitemap().urlPattern(), marker) != 1) {
            throw new LearnRejectedException("template is not locale-specific");
        }
        if (indexUrl == null || indexUrl.isBlank() || indexUrl.length() > 2048) {
            throw new LearnRejectedException("indexUrl is required");
        }
        URI index;
        try {
            index = URI.create(indexUrl.strip());
            if (!"https".equalsIgnoreCase(index.getScheme())) {
                throw new LearnRejectedException("indexUrl must be https");
            }
            policy.validate(index);
        } catch (IllegalArgumentException | FetchException e) {
            throw new LearnRejectedException("indexUrl is not acceptable: " + Text.clean(e.getMessage(), 120));
        }
        if (!UrlPolicy.hostKey(index).equals(UrlPolicy.hostKey(sitemap))) {
            throw new LearnRejectedException("indexUrl must be on the template's host");
        }
        return new Plan(template, sitemap, locale, index);
    }

    private static String firstSegment(String rawPath) {
        if (rawPath == null || !rawPath.startsWith("/")) {
            return null;
        }
        int end = rawPath.indexOf('/', 1);
        return end < 0 ? null : rawPath.substring(1, end);
    }

    private static int count(String s, String part) {
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    // ---- the run ----

    private static final class Report {
        final String runId;
        final Plan plan;
        final List<Line> lines = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        String outcome = "running";

        Report(String runId, Plan plan) {
            this.runId = runId;
            this.plan = plan;
        }

        String render() {
            int covered = 0;
            int failed = 0;
            int skipped = 0;
            int pending = 0;
            for (Line l : lines) {
                switch (l.status) {
                    case COVERED -> covered++;
                    case FAILED -> failed++;
                    case SKIPPED -> skipped++;
                    default -> pending++;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Recipe clone report\n");
            sb.append("run: ").append(runId).append('\n');
            sb.append("template: ").append(plan.template().name()).append(" (locale ").append(plan.templateLocale())
                    .append(")\n");
            sb.append("index url: ").append(Text.clean(plan.index().toString(), 300)).append('\n');
            sb.append("outcome: ").append(outcome).append('\n');
            sb.append("index entries: ").append(lines.size()).append('\n');
            sb.append("COVERED: ").append(covered).append('\n');
            sb.append("FAILED: ").append(failed).append('\n');
            sb.append("SKIPPED: ").append(skipped).append('\n');
            if (pending > 0) {
                sb.append("PENDING: ").append(pending).append('\n');
            }
            sb.append("Files are disabled proposals: review them, move them into the recipes folder, set"
                    + " \"enabled\": true and restart ingestion.\n");
            sb.append("notes:\n");
            notes.forEach(n -> sb.append("  - ").append(Text.clean(n, 400)).append('\n'));
            sb.append("entries:\n");
            int shown = 0;
            for (Line l : lines) {
                if (shown++ >= MAX_REPORT_LINES) {
                    sb.append("  ... ").append(lines.size() - MAX_REPORT_LINES)
                            .append(" more entries not listed (counts above include them)\n");
                    break;
                }
                sb.append("  ").append(l.status).append(' ').append(Text.clean(l.loc, 200));
                if (!l.detail.isEmpty()) {
                    sb.append(" : ").append(Text.clean(l.detail, 300));
                }
                sb.append('\n');
            }
            return Text.cleanMultiline(sb.toString(), REPORT_CHARS);
        }
    }

    private void run(String runId, Plan plan, ProposalWriter.Claim claim) {
        Report report = new Report(runId, plan);
        try {
            execute(plan, report, claim);
        } catch (RuntimeException e) {
            log.warn("Clone run {} failed unexpectedly: {}", runId, e.toString());
            report.notes.add("internal error: " + Text.clean(e.getClass().getSimpleName(), 80));
            report.outcome = "internal error";
        } finally {
            if (Thread.interrupted()) {
                // clear the flag so the final report write is not closed by it
                report.outcome = "interrupted";
            }
            // Anything still pending after an error is accounted for, so the counts add up.
            report.lines.stream().filter(l -> l.status == Status.PENDING)
                    .forEach(l -> l.set(Status.SKIPPED, "run did not finish"));
            save(report, claim);
        }
        log.info("Clone run {} for {} finished: {}", runId, plan.template().name(), report.outcome);
    }

    private void save(Report report, ProposalWriter.Claim claim) {
        try {
            writer.writeReport(claim, report.render());
        } catch (IOException | RuntimeException e) {
            log.warn("Clone run {} could not write its report: {}", report.runId, e.toString());
        }
    }

    private void execute(Plan plan, Report report, ProposalWriter.Claim claim) {
        Instant started = clock.instant();
        SitemapReader reader = new SitemapReader(fetcher);
        SitemapReader.IndexListing index;
        try {
            index = reader.readIndex(plan.index());
        } catch (FetchBlockedException e) {
            report.outcome = "host blocked: " + Text.clean(e.getMessage(), 200);
            return;
        } catch (RuntimeException e) {
            report.outcome = "index unreadable: " + Text.clean(e.getMessage(), 200);
            return;
        }

        Recipe template = plan.template();
        String templateUrl = template.sitemap().url();
        String templateCanonical = canon(URI.create(templateUrl));
        String marker = "/" + plan.templateLocale() + "/";
        String host = UrlPolicy.hostKey(plan.templateSitemap());
        Set<String> seen = new HashSet<>();
        List<Line> siblings = new ArrayList<>();
        boolean templateListed = false;
        for (String loc : index.locs()) {
            Line line = new Line(loc == null ? "(empty loc)" : loc, null);
            report.lines.add(line);
            if (loc == null) {
                line.set(Status.SKIPPED, "empty loc");
                continue;
            }
            URI resolved;
            try {
                resolved = index.finalUri().resolve(loc);
            } catch (IllegalArgumentException e) {
                line.set(Status.SKIPPED, "unusable URL");
                continue;
            }
            URI canonical = UrlCanonicalizer.canonicalize(resolved, true);
            if (canonical == null) {
                line.set(Status.SKIPPED, "unusable URL");
                continue;
            }
            if (!"https".equals(canonical.getScheme()) || !host.equals(UrlCanonicalizer.hostOf(canonical))) {
                line.set(Status.SKIPPED, "other host");
                continue;
            }
            if (!seen.add(canonical.toString())) {
                line.set(Status.SKIPPED, "duplicate entry");
                continue;
            }
            if (canonical.toString().equals(templateCanonical)) {
                templateListed = true;
                line.set(Status.SKIPPED, "template locale");
                continue;
            }
            String path = canonical.getRawPath();
            String last = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (NESTED_INDEX.matcher(last).matches()) {
                line.set(Status.SKIPPED, "nested index");
                continue;
            }
            String locale = firstSegment(path);
            if (locale == null) {
                line.set(Status.SKIPPED, "not a sibling: no locale segment");
                continue;
            }
            if (!LOCALE.matcher(locale).matches()) {
                line.set(Status.SKIPPED, "unsupported locale");
                continue;
            }
            URI candidate = UrlCanonicalizer.canonicalize(
                    URI.create(templateUrl.replace(marker, "/" + locale + "/")), true);
            if (candidate == null || !candidate.toString().equals(canonical.toString())) {
                line.set(Status.SKIPPED, "not a sibling: different file or query than the template's sitemap");
                continue;
            }
            Line sibling = new Line(line.loc, locale);
            // Swap the placeholder so the report keeps one line per index entry and the work list keeps its locale.
            report.lines.set(report.lines.size() - 1, sibling);
            siblings.add(sibling);
        }
        if (!templateListed) {
            report.notes.add("warning: the template's own sitemap is not listed in the index; check the index URL");
        }
        report.outcome = "running";
        save(report, claim);
        if (siblings.isEmpty()) {
            report.outcome = "finished: no sibling locales found";
            return;
        }

        boolean blocked = false;
        java.util.Set<String> templateIds = new HashSet<>();
        try {
            SampleRunner.Sample ts = sampler.run(template);
            templateIds.addAll(ts.ids());
            if (!ts.ran()) {
                report.notes.add("the template sample did not run, so shared-ids counts are not meaningful");
            }
        } catch (FetchBlockedException e) {
            blocked = true;
            report.notes.add("the site refused the template sample: " + Text.clean(e.getMessage(), 200));
        }
        int tried = 0;
        boolean interrupted = false;
        for (Line s : siblings) {
            if (blocked) {
                s.set(Status.SKIPPED, "run stopped: host blocked");
                continue;
            }
            if (interrupted || Thread.currentThread().isInterrupted()) {
                interrupted = true;
                s.set(Status.SKIPPED, "interrupted");
                continue;
            }
            String name = cloneName(template.name(), plan.templateLocale(), s.locale);
            if (name == null) {
                s.set(Status.SKIPPED, "clone name invalid");
                continue;
            }
            if (validator.isTaken(name)) {
                s.set(Status.SKIPPED, "a recipe named " + name + " is already loaded");
                continue;
            }
            if (writer.recipeExists(name)) {
                s.set(Status.SKIPPED, "already proposed");
                continue;
            }
            if (clock.instant().isAfter(started.plus(cloneProps.cloneMaxRun()))
                    || clock.instant().equals(started.plus(cloneProps.cloneMaxRun()))) {
                s.set(Status.SKIPPED, "time budget");
                continue;
            }
            if (tried >= cloneProps.maxClones()) {
                s.set(Status.SKIPPED, "cap");
                continue;
            }
            Recipe clone = build(template, plan.templateLocale(), s.locale, name);
            try {
                validator.validate(clone);
            } catch (IllegalArgumentException e) {
                s.set(Status.FAILED, "clone failed validation: " + Text.clean(e.getMessage(), 200));
                continue;
            }
            tried++;
            SampleRunner.Sample sample;
            try {
                sample = sampler.run(clone);
            } catch (FetchBlockedException e) {
                blocked = true;
                s.set(Status.FAILED, "blocked: " + Text.clean(e.getMessage(), 200));
                report.notes.add("the site refused a request; the rest of the run is skipped");
                save(report, claim);
                continue;
            }
            if (Thread.currentThread().isInterrupted()) {
                interrupted = true;
                s.set(Status.SKIPPED, "interrupted");
                continue;
            }
            evaluate(s, sample, clone, name, templateIds);
            save(report, claim);
        }
        report.outcome = blocked ? "stopped: host blocked" : interrupted ? "interrupted" : "finished";
    }

    private void evaluate(Line s, SampleRunner.Sample sample, Recipe clone, String name, Set<String> templateIds) {
        if (!sample.ran()) {
            String why = String.join("; ", sample.notes());
            s.set(Status.FAILED, why.toLowerCase(Locale.ROOT).contains("robots") ? "blocked: robots.txt disallows it"
                    : Text.clean(why, 300));
            return;
        }
        int matched = sample.matched();
        String counts = "matched=" + matched + " good=" + sample.good();
        if (matched < 1) {
            s.set(Status.FAILED, "the sitemap lists no job URLs matching the pattern (" + counts + ")");
            return;
        }
        int need = Math.min(Math.min(SampleRunner.MIN_GOOD, matched),
                Math.min(SampleRunner.SAMPLE_DETAILS, clone.limits().maxDetailFetchesPerRun()));
        if (sample.good() < need) {
            s.set(Status.FAILED, "sample found too few usable postings, need " + need + " (" + counts + ")");
            return;
        }
        long shared = sample.ids().stream().filter(templateIds::contains).count();
        try {
            if (!writer.writeNewRecipe(name, RecipeProposal.serialize(clone))) {
                s.set(Status.SKIPPED, "already proposed");
                return;
            }
        } catch (IOException | RuntimeException e) {
            s.set(Status.FAILED, "could not write the recipe file: " + Text.clean(e.getMessage(), 200));
            return;
        }
        s.set(Status.COVERED, name + ".json " + counts + " sample-ids-shared-with-template=" + shared);
    }

    static String cloneName(String template, String templateLocale, String locale) {
        String suffix = "-" + templateLocale;
        String base = template.endsWith(suffix) ? template.substring(0, template.length() - suffix.length()) : template;
        String name = base + "-" + locale;
        return RECIPE_NAME.matcher(name).matches() ? name : null;
    }

    /** The template with one path segment swapped in the sitemap URL and the pattern; disabled; limits kept. */
    static Recipe build(Recipe t, String from, String to, String name) {
        String marker = "/" + from + "/";
        String replacement = "/" + to + "/";
        Recipe.Sitemap sm = t.sitemap();
        Recipe.Sitemap swapped = new Recipe.Sitemap(sm.url().replace(marker, replacement),
                sm.urlPattern().replace(marker, replacement), sm.maxUrls(), sm.keepQuery());
        return new Recipe(name, false, t.strategy(), null, swapped, null, t.detail(), t.limits());
    }

    private static String canon(URI uri) {
        URI c = UrlCanonicalizer.canonicalize(uri, true);
        return c == null ? "" : c.toString();
    }
}
