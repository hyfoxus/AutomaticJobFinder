package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.dto.BudgetState;
import com.gnemirko.jobapplier.scoring.dto.ScoringSummary;
import com.gnemirko.jobapplier.scoring.entity.LlmUsageEntity;

/** $0.05 cap; every fake call costs $0.02 (1000 in and 1000 out tokens at 10 micro-dollars each). */
@SpringBootTest(properties = {
        "scoring.enabled=true",
        "scoring.profile-path=src/test/resources/profile.test.yml",
        "scoring.llm.model=test-model",
        "scoring.llm.max-tokens=1000",
        "scoring.llm.prices[test-model].input-per-million=10",
        "scoring.llm.prices[test-model].output-per-million=10",
        "scoring.budget.daily-usd=0.05",
        "scoring.budget.zone=UTC"
})
@Import({TestcontainersConfiguration.class, ScoringTestConfig.class})
class ScoringBudgetTest extends ScoringTestSupport {

    @Test
    void capStopsCallsAndSpendNeverExceedsIt() {
        ScoringSummary s = scoring.score(jobs("a", "b", "c", "d", "e"));

        assertThat(s.scored()).isEqualTo(2);
        assertThat(s.unscoredBudget()).isEqualTo(3);
        assertThat(model.calls()).isEqualTo(2);
        assertThat(s.budgetState()).isEqualTo(BudgetState.CAP_REACHED);
        assertThat(s.stopReason()).contains("budget");
        assertThat(usage.findAll().stream().mapToLong(LlmUsageEntity::getMicros).sum())
                .isEqualTo(40_000).isLessThanOrEqualTo(50_000);
        assertThat(assessments.findAll().stream().filter(r -> r.getStatus() == AssessmentStatus.UNSCORED_BUDGET))
                .hasSize(3).allMatch(r -> r.getAttempts() == 0);
    }

    @Test
    void nextDayScoresLeftoverRowsFromStoredPayloadWithAnEmptyBatch() {
        scoring.score(jobs("a", "b", "c", "d", "e"));
        assertThat(model.calls()).isEqualTo(2);

        clock.advance(Duration.ofDays(1));
        ScoringSummary s = scoring.score(List.of());

        assertThat(s.scored()).isEqualTo(2);       // the new day starts at zero; two more fit
        assertThat(s.unscoredBudget()).isEqualTo(1);
        assertThat(s.spentTodayMicros()).isEqualTo(40_000);
        assertThat(assessments.findAll().stream().filter(r -> r.getStatus() == AssessmentStatus.SCORED)).hasSize(4);

        // Newest firstSeenAt goes first: a and b were scored on day one, c and d on day two, e is left.
        assertThat(row("e").getStatus()).isEqualTo(AssessmentStatus.UNSCORED_BUDGET);
    }

    @Test
    void nearCapReportsStateAndKeepsScoring() {
        ScoringSummary s = scoring.score(jobs("a", "b"));

        assertThat(s.scored()).isEqualTo(2);
        assertThat(s.spentTodayMicros()).isEqualTo(40_000);
        assertThat(s.budgetState()).isEqualTo(BudgetState.NEAR_CAP);
        assertThat(s.stopReason()).isNull();
    }

    @Test
    void realUsageAboveTheEstimateIsRecordedInFullAndBlocksTheNextCall() {
        // The prompt is estimated at roughly 1.2k tokens; the provider says 3k in, 1k out: $0.04.
        model.reply(FakeChatModel.VALID, 3000, 1000);

        ScoringSummary s = scoring.score(jobs("a", "b"));

        assertThat(s.scored()).isEqualTo(1);
        assertThat(s.unscoredBudget()).isEqualTo(1);
        assertThat(model.calls()).isEqualTo(1);
        assertThat(usage.findAll()).singleElement().satisfies(u -> {
            assertThat(u.getMicros()).isEqualTo(40_000); // never less than the real usage
            assertThat(u.isEstimated()).isFalse();
        });
        assertThat(s.spentTodayMicros()).isLessThanOrEqualTo(50_000);
    }

    @Test
    void retryThatDoesNotFitCountsTheChargedAttempt() {
        model.reply("garbage", 3000, 1000); // $0.04 spent; the retry cannot fit in the remaining $0.01

        ScoringSummary s = scoring.score(jobs("a"));

        assertThat(model.calls()).isEqualTo(1);
        assertThat(s.unscoredBudget()).isEqualTo(1);
        assertThat(row("a").getStatus()).isEqualTo(AssessmentStatus.UNSCORED_BUDGET);
        assertThat(row("a").getAttempts()).isEqualTo(1);
    }
}
