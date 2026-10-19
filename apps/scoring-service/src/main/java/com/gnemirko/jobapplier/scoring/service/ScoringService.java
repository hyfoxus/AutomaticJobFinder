package com.gnemirko.jobapplier.scoring.service;

import java.time.Clock;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;
import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.dto.BuiltPrompt;
import com.gnemirko.jobapplier.scoring.dto.CallResult;
import com.gnemirko.jobapplier.scoring.dto.ScoringSummary;
import com.gnemirko.jobapplier.scoring.entity.AssessmentEntity;
import com.gnemirko.jobapplier.scoring.repository.AssessmentRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Stores a batch, drops what fails the hard filters, asks the model about the rest one job at a
 * time and keeps the spend under the daily cap. Rows left over from earlier runs (pending, failed
 * with attempts left, or out of budget) are processed too, from their stored payload.
 */
@Slf4j
@Service
@ScoringEnabled
public class ScoringService {

    static final int MAX_CONSECUTIVE_PROVIDER_FAILURES = 3;
    private static final Set<AssessmentStatus> RETRYABLE = EnumSet.of(
            AssessmentStatus.PENDING, AssessmentStatus.UNSCORED_BUDGET, AssessmentStatus.FAILED);

    private final AssessmentRepository assessments;
    private final HardFilter hardFilter;
    private final PromptBuilder promptBuilder;
    private final FitAssessor fitAssessor;
    private final CostModel costModel;
    private final UsageLedger ledger;
    private final BudgetGuard budget;
    private final ScoringProperties props;
    private final Clock clock;

    public ScoringService(AssessmentRepository assessments, HardFilter hardFilter, PromptBuilder promptBuilder,
                          FitAssessor fitAssessor, CostModel costModel, UsageLedger ledger, BudgetGuard budget,
                          ScoringProperties props, Clock clock) {
        this.assessments = assessments;
        this.hardFilter = hardFilter;
        this.promptBuilder = promptBuilder;
        this.fitAssessor = fitAssessor;
        this.costModel = costModel;
        this.ledger = ledger;
        this.budget = budget;
        this.props = props;
        this.clock = clock;
    }

    /** Runs one batch. Calls are sequential; run one batch at a time. */
    public ScoringSummary score(List<JobRecord> batch) {
        Run run = new Run();
        List<JobRecord> jobs = batch == null ? List.of() : batch;
        run.received = jobs.size();
        store(jobs, run);

        List<AssessmentEntity> rows = assessments.findByStatusInOrderByFirstSeenAtDescIdDesc(RETRYABLE);
        run.startSpent = budget.spentToday();
        for (int i = 0; i < rows.size(); i++) {
            AssessmentEntity row = rows.get(i);
            if (row.getStatus() == AssessmentStatus.FAILED && row.getAttempts() >= props.maxAttempts()) {
                row.makePermanent("attempts used up", clock.instant());
                assessments.save(row);
                continue;
            }
            var rejection = hardFilter.reject(row.toRecord().posting());
            if (rejection.isPresent()) {
                row.reject(rejection.get(), clock.instant());
                assessments.save(row);
                run.rejected++;
                continue;
            }
            if (run.capReached) {
                leaveForBudget(row, run);
                continue;
            }
            Step step = scoreRow(row, run);
            if (step != Step.CONTINUE) {
                run.unprocessed = rows.size() - (step == Step.STOP_KEEP_ROW ? i : i + 1);
                break;
            }
        }
        return summary(run);
    }

    private void store(List<JobRecord> jobs, Run run) {
        Set<String> seen = new HashSet<>();
        for (JobRecord rec : jobs) {
            JobPosting p = rec == null ? null : rec.posting();
            if (p == null || p.source() == null || p.externalId() == null || p.title() == null
                    || p.url() == null || rec.firstSeenAt() == null) {
                log.warn("Skipping a job record with missing source, id, title, url or firstSeenAt");
                continue;
            }
            if (!seen.add(p.source() + '\u0000' + p.externalId())) {
                run.duplicates++;
                continue;
            }
            if (assessments.findBySourceAndExternalId(p.source(), p.externalId()).isEmpty()) {
                assessments.save(AssessmentEntity.pending(rec, clock.instant()));
                run.stored++;
            }
        }
    }

    private enum Step { CONTINUE, STOP_KEEP_ROW, STOP_ROW_DONE }

    private Step scoreRow(AssessmentEntity row, Run run) {
        BuiltPrompt built = promptBuilder.build(row.toRecord().posting());
        long worst = costModel.worstCaseMicros(built.estimatedTokens());
        if (worst > budget.capMicros()) {
            // No amount of waiting helps: this one job can never fit. The other rows are unaffected.
            String why = "worst-case cost " + worst + " exceeds the whole daily budget " + budget.capMicros();
            log.warn("Job {} can never be scored: {}", row.jobKey(), why);
            row.makePermanent(why, clock.instant());
            assessments.save(row);
            run.failed++;
            run.failedPermanent++;
            return Step.CONTINUE;
        }
        for (int call = 1; call <= 2; call++) {
            // Check and charge in one step: the worst case is reserved in the ledger before the call.
            var reservation = budget.reserve(props.llm().model(), worst, row.jobKey());
            if (reservation.isEmpty()) {
                run.capReached = true;
                run.stopReason = "daily budget reached";
                if (call == 2) {
                    row.countAttempt(); // the first call was charged and produced nothing usable
                }
                leaveForBudget(row, run);
                return Step.CONTINUE;
            }
            long reservationId = reservation.getAsLong();
            CallResult res;
            try {
                res = fitAssessor.assess(built);
            } catch (RuntimeException e) {
                budget.release(reservationId);
                throw e;
            }
            charge(row, res, run, reservationId);
            switch (res.kind()) {
                case VALID -> {
                    row.score(res.assessment(), props.llm().model(), clock.instant());
                    assessments.save(row);
                    run.scored++;
                    run.consecutiveFailures = 0;
                    return Step.CONTINUE;
                }
                case INVALID -> run.consecutiveFailures = 0;
                case AUTH_FAILURE -> {
                    log.error("Provider rejected the credentials ({}); stopping the batch", res.error());
                    run.stopReason = "provider authentication failed";
                    return Step.STOP_KEEP_ROW;
                }
                case REQUEST_REJECTED -> {
                    log.error("Provider rejected the request ({}); stopping the batch", res.error());
                    run.stopReason = "provider rejected the request (non-transient 4xx): " + res.error();
                    return Step.STOP_KEEP_ROW;
                }
                case TIMEOUT, PROVIDER_FAILURE -> {
                    fail(row, res.kind() + ": " + res.error(), run);
                    if (++run.consecutiveFailures >= MAX_CONSECUTIVE_PROVIDER_FAILURES) {
                        log.error("{} provider failures in a row; stopping the batch", run.consecutiveFailures);
                        run.stopReason = run.consecutiveFailures + " provider failures in a row";
                        return Step.STOP_ROW_DONE;
                    }
                    return Step.CONTINUE;
                }
            }
        }
        fail(row, "model reply was invalid twice", run);
        return Step.CONTINUE;
    }

    private void charge(AssessmentEntity row, CallResult res, Run run, long reservationId) {
        run.calls++;
        if (!res.charged()) {
            budget.release(reservationId);
            return;
        }
        // Real usage is stored as reported, even when it is above the reservation.
        long micros = costModel.costMicros(res.inputTokens(), res.outputTokens());
        budget.settle(reservationId, res.inputTokens(), res.outputTokens(), micros, res.estimated());
        run.batchSpent += micros;
        if (!run.warned && budget.isNearCap(run.startSpent + run.batchSpent)) {
            run.warned = true;
            log.warn("Spent {} of {} micro-dollars today (80% or more of the daily cap)",
                    run.startSpent + run.batchSpent, budget.capMicros());
        }
    }

    private void fail(AssessmentEntity row, String why, Run run) {
        row.fail(why, props.maxAttempts(), clock.instant());
        assessments.save(row);
        run.failed++;
        if (row.getStatus() == AssessmentStatus.FAILED_PERMANENT) {
            run.failedPermanent++;
        }
    }

    private void leaveForBudget(AssessmentEntity row, Run run) {
        row.leaveUnscoredForBudget(clock.instant());
        assessments.save(row);
        run.unscoredBudget++;
    }

    private ScoringSummary summary(Run r) {
        long spentToday = budget.spentToday();
        if (budget.isNearCap(spentToday)) {
            log.warn("Budget state: {} of {} micro-dollars spent today", spentToday, budget.capMicros());
        }
        return new ScoringSummary(r.received, r.duplicates, r.stored, r.rejected, r.scored, r.failed,
                r.failedPermanent, r.unscoredBudget, r.unprocessed, r.calls, r.batchSpent, spentToday,
                budget.capMicros(), budget.state(spentToday, r.capReached), r.stopReason);
    }

    private static final class Run {
        int received;
        int duplicates;
        int stored;
        int rejected;
        int scored;
        int failed;
        int failedPermanent;
        int unscoredBudget;
        int unprocessed;
        int calls;
        long batchSpent;
        long startSpent;
        int consecutiveFailures;
        boolean capReached;
        boolean warned;
        String stopReason;
    }
}
