package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.dto.BudgetState;
import com.gnemirko.jobapplier.scoring.dto.ScoringSummary;

/** Cap $0.04: a job with a long description can never fit, a short one can. */
@SpringBootTest(properties = {
        "scoring.enabled=true",
        "scoring.profile-path=src/test/resources/profile.test.yml",
        "scoring.llm.model=test-model",
        "scoring.llm.max-tokens=1000",
        "scoring.llm.prices[test-model].input-per-million=10",
        "scoring.llm.prices[test-model].output-per-million=10",
        "scoring.budget.daily-usd=0.04",
        "scoring.budget.zone=UTC"
})
@Import({TestcontainersConfiguration.class, ScoringTestConfig.class})
class ScoringOversizeJobTest extends ScoringTestSupport {

    @Test
    void jobBiggerThanTheWholeCapIsPermanentAndOthersStillRun() {
        List<JobRecord> batch = List.of(
                job("big", "Java Developer", "Acme", "Remote", "w".repeat(6000), 0),
                job("small", "Java Developer", "Acme", "Remote", "short", 1));

        ScoringSummary s = scoring.score(batch);

        assertThat(row("big").getStatus()).isEqualTo(AssessmentStatus.FAILED_PERMANENT);
        assertThat(row("big").getReason()).contains("exceeds the whole daily budget");
        assertThat(row("small").getStatus()).isEqualTo(AssessmentStatus.SCORED);
        assertThat(s.budgetState()).isNotEqualTo(BudgetState.CAP_REACHED);
        assertThat(s.failedPermanent()).isEqualTo(1);
        assertThat(model.calls()).isEqualTo(1);
    }
}
