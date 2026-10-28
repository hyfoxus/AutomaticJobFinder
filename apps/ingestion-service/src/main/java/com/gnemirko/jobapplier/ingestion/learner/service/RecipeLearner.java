package com.gnemirko.jobapplier.ingestion.learner.service;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.dto.Completion;
import com.gnemirko.jobapplier.ingestion.learner.dto.LearnStarted;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnBusyException;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnRejectedException;
import com.gnemirko.jobapplier.ingestion.learner.exception.LlmException;
import com.gnemirko.jobapplier.ingestion.learner.source.LlmClient;
import com.gnemirko.jobapplier.ingestion.learner.source.SampleRunner;
import com.gnemirko.jobapplier.ingestion.learner.util.Text;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.service.RecipeValidator;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Learns a recipe for one allow-listed careers site: fetch the start page politely, reduce it to a skeleton, ask the
 * model (at most twice in total), validate, try the recipe on a small sample, and save it disabled in the review
 * folder together with a report. One run at a time. The result goes to files, never into the HTTP answer.
 */
@Slf4j
@Service
@LearnerEnabled
public class RecipeLearner {

    public static final int MAX_MODEL_CALLS = 2;
    static final int FEEDBACK_CHARS = 600;
    static final int REPORT_CHARS = 20_000;

    enum Outcome {
        PROPOSED("recipe proposed"),
        NO_STRATEGY("no fitting strategy"),
        ROBOTS("blocked by robots.txt"),
        CHALLENGE("blocked: the site answered with a bot challenge or refusal"),
        FETCH_FAILED("fetch failed"),
        BUDGET("budget reached"),
        MODEL_UNAVAILABLE("model unavailable"),
        CUT_OFF("the model reply was cut off"),
        INVALID("no valid recipe after the allowed model calls"),
        SAMPLE_FAILED("sample run failed after the allowed model calls"),
        ERROR("internal error");

        final String text;

        Outcome(String text) {
            this.text = text;
        }
    }

    private final LearnerProperties props;
    private final PoliteFetcher fetcher;
    private final UrlPolicy policy;
    private final LlmClient llm;
    private final RecipeValidator validator;
    private final SampleRunner sampler;
    private final ProposalWriter writer;
    private final Executor executor;
    private final ExecutorService owned;
    private final AtomicBoolean busy = new AtomicBoolean();

    @Autowired
    public RecipeLearner(LearnerProperties props, PoliteFetcher fetcher, UrlPolicy policy, LlmClient llm,
            RecipeValidator validator, SampleRunner sampler, ProposalWriter writer) {
        this(props, fetcher, policy, llm, validator, sampler, writer, null);
    }

    /** With an executor the caller owns (tests pass a direct one); without, a single daemon thread. */
    public RecipeLearner(LearnerProperties props, PoliteFetcher fetcher, UrlPolicy policy, LlmClient llm,
            RecipeValidator validator, SampleRunner sampler, ProposalWriter writer, Executor executor) {
        this.props = props;
        this.fetcher = fetcher;
        this.policy = policy;
        this.llm = llm;
        this.validator = validator;
        this.sampler = sampler;
        this.writer = writer;
        if (executor == null) {
            this.owned = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "recipe-learner");
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

    public boolean isBusy() {
        return busy.get();
    }

    /** The single run slot, shared with the clone run: true when it was free and is now taken. */
    public boolean tryAcquireSlot() {
        return busy.compareAndSet(false, true);
    }

    public void releaseSlot() {
        busy.set(false);
    }

    /**
     * Checks the URL and the allow-list (nothing is fetched before they pass), claims the single run slot and the
     * output name, and starts the run in the background.
     */
    public LearnStarted start(String url) {
        URI target = parse(url);
        if (!busy.compareAndSet(false, true)) {
            throw new LearnBusyException();
        }
        ProposalWriter.Claim claim;
        try {
            claim = writer.claim(UrlPolicy.normalizeHost(target.getHost()));
            if (validator.isTaken(claim.name())) {
                writer.discard(claim);
                busy.set(false);
                throw new LearnRejectedException("a recipe or built-in source named '" + claim.name()
                        + "' is already loaded; rename or remove it first. Nothing was fetched and no model call was made");
            }
        } catch (LearnRejectedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            busy.set(false);
            log.warn("Learner cannot reserve an output file: {}", e.toString());
            throw new IllegalStateException("cannot write to the review folder", e);
        }
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try {
            executor.execute(() -> {
                try {
                    run(runId, target, claim);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            writer.discard(claim);
            busy.set(false);
            throw new IllegalStateException("the learner is shutting down", e);
        }
        return new LearnStarted(runId, claim.name());
    }

    private URI parse(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            throw new LearnRejectedException("url is required");
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
            policy.validate(uri);
        } catch (IllegalArgumentException | FetchException e) {
            throw new LearnRejectedException("url is not acceptable: " + Text.clean(e.getMessage(), 120));
        }
        String host = UrlPolicy.normalizeHost(uri.getHost());
        if (!props.isAllowed(host)) {
            throw new LearnRejectedException("host is not in ingestion.learner.allowed-hosts");
        }
        return uri;
    }

    private void run(String runId, URI target, ProposalWriter.Claim claim) {
        Report report = new Report(runId, target, claim.name());
        Outcome outcome;
        try {
            outcome = learn(target, claim, report);
        } catch (RuntimeException e) {
            log.warn("Learn run {} failed unexpectedly: {}", runId, e.toString());
            report.note("internal error: " + Text.clean(e.getClass().getSimpleName(), 80));
            outcome = Outcome.ERROR;
        }
        report.outcome = outcome;
        try {
            writer.writeReport(claim, report.render());
        } catch (IOException | RuntimeException e) {
            log.warn("Learn run {} could not write its report: {}", runId, e.toString());
        }
        log.info("Learn run {} for {} finished: {} (model calls: {})", runId, target.getHost(), outcome.text,
                report.modelCalls);
    }

    private Outcome learn(URI target, ProposalWriter.Claim claim, Report report) {
        String host = UrlPolicy.normalizeHost(target.getHost());
        FetchedPage page;
        byte[] robots = null;
        try {
            page = fetcher.fetch(target);
            if (page.status() / 100 != 2) {
                report.note("the start page answered HTTP " + page.status());
                return Outcome.FETCH_FAILED;
            }
            robots = fetchRobots(target);
        } catch (RobotsDisallowedException e) {
            report.note("robots.txt disallows the start URL; no model call was made");
            return Outcome.ROBOTS;
        } catch (FetchBlockedException e) {
            report.note(Text.clean(e.getMessage(), 200));
            return Outcome.CHALLENGE;
        } catch (FetchException e) {
            report.note("fetch failed: " + Text.clean(e.getMessage(), 200));
            return Outcome.FETCH_FAILED;
        }
        String skeleton = PageSkeleton.build(page.finalUri(), page.body(), null, robots, props.maxInputChars());

        String feedback = null;
        Outcome last = Outcome.INVALID;
        for (int call = 1; call <= MAX_MODEL_CALLS; call++) {
            Completion reply;
            try {
                report.modelCalls++;
                reply = llm.complete(host, skeleton, feedback);
            } catch (LlmException e) {
                report.note(Text.clean(e.getMessage(), 200));
                return e.kind() == LlmException.Kind.BUDGET ? Outcome.BUDGET : Outcome.MODEL_UNAVAILABLE;
            }
            if (reply.cutOff()) {
                report.note("the model hit its output limit; a cut-off reply is not used");
                return Outcome.CUT_OFF;
            }
            RecipeProposal.Result result = RecipeProposal.parse(reply.text(), target, claim.name(), validator);
            if (result instanceof RecipeProposal.NoStrategy) {
                return Outcome.NO_STRATEGY;
            }
            if (result instanceof RecipeProposal.Rejected rejected) {
                report.note("attempt " + call + " rejected: " + Text.clean(rejected.reason(), 300));
                feedback = rejected.feedback();
                last = Outcome.INVALID;
                continue;
            }
            RecipeProposal.Accepted accepted = (RecipeProposal.Accepted) result;
            SampleRunner.Sample sample;
            try {
                sample = sampler.run(accepted.recipe());
            } catch (FetchBlockedException e) {
                report.note("the sample run was refused by the site: " + Text.clean(e.getMessage(), 200));
                return Outcome.CHALLENGE;
            }
            report.strategy = accepted.recipe().strategy();
            if (!sample.ran()) {
                sample.notes().forEach(report::note);
                report.note("attempt " + call + " proposed recipe: " + compact(accepted.json()));
                report.note("the sample run could not fetch the site; no second model call was made");
                return Outcome.FETCH_FAILED;
            }
            report.sampleGood = sample.good();
            report.titles = sample.titles();
            sample.notes().forEach(report::note);
            if (!sample.passed()) {
                report.note("attempt " + call + " proposed recipe: " + compact(accepted.json()));
                report.note("attempt " + call + " sample run found " + sample.good() + " usable postings (need "
                        + SampleRunner.MIN_GOOD + ")");
                feedback = "a sample run of that recipe found only " + sample.good()
                        + " postings with a title and a url; at least " + SampleRunner.MIN_GOOD + " are needed";
                last = Outcome.SAMPLE_FAILED;
                continue;
            }
            try {
                writer.writeRecipe(claim, accepted.json());
            } catch (IOException | RuntimeException e) {
                report.note("could not write the recipe file: " + Text.clean(e.getMessage(), 200));
                return Outcome.ERROR;
            }
            return Outcome.PROPOSED;
        }
        return last;
    }

    /** The clamped, re-serialised recipe (never the raw reply) on one line, cleaned and capped. */
    private static String compact(String json) {
        try {
            return Text.clean(new tools.jackson.databind.json.JsonMapper().readTree(json).toString(), 4000);
        } catch (RuntimeException e) {
            return "(not shown)";
        }
    }

    private byte[] fetchRobots(URI target) {
        try {
            FetchedPage robots = fetcher.fetch(target.resolve("/robots.txt"));
            return robots.status() / 100 == 2 ? robots.body() : null;
        } catch (FetchBlockedException e) {
            throw e;
        } catch (FetchException e) {
            return null;
        }
    }

    private static final class Report {
        final String runId;
        final URI target;
        final String name;
        final List<String> notes = new ArrayList<>();
        Outcome outcome = Outcome.ERROR;
        String strategy = "none";
        int modelCalls;
        int sampleGood;
        List<String> titles = List.of();

        Report(String runId, URI target, String name) {
            this.runId = runId;
            this.target = target;
            this.name = name;
        }

        void note(String s) {
            notes.add(Text.clean(s, s.contains("proposed recipe: ") ? 4100 : 400));
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("Recipe learner report\n");
            sb.append("run: ").append(runId).append('\n');
            sb.append("start url: ").append(Text.clean(target.toString(), 300)).append('\n');
            sb.append("name: ").append(name).append('\n');
            sb.append("outcome: ").append(outcome.text).append('\n');
            sb.append("strategy: ").append(strategy).append('\n');
            sb.append("model calls: ").append(modelCalls).append('\n');
            if (outcome == Outcome.PROPOSED) {
                sb.append("recipe file: ").append(name).append(".json (enabled=false; review it, then move it into the"
                        + " recipes folder and restart ingestion)\n");
            } else {
                sb.append("recipe file: none\n");
            }
            sb.append("sample postings with title and url: ").append(sampleGood).append('\n');
            sb.append("sample titles:\n");
            titles.forEach(t -> sb.append("  - ").append(t).append('\n'));
            sb.append("notes:\n");
            notes.forEach(n -> sb.append("  - ").append(n).append('\n'));
            return Text.cleanMultiline(sb.toString(), REPORT_CHARS);
        }
    }
}
