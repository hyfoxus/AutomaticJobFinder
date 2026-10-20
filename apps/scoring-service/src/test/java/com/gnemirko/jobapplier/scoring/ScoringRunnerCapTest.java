package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gnemirko.jobapplier.scoring.dto.AssessmentStatus;
import com.gnemirko.jobapplier.scoring.entity.IngestCursorEntity;
import com.gnemirko.jobapplier.scoring.repository.IngestCursorRepository;
import com.gnemirko.jobapplier.scoring.service.ScoringRunner;

/** Cap 0.10 and 0.02 per call: the budget allows exactly chunk 1's four calls. */
@SpringBootTest(properties = {
        "scoring.enabled=true",
        "scoring.profile-path=src/test/resources/profile.test.yml",
        "scoring.llm.model=test-model",
        "scoring.llm.max-tokens=1000",
        "scoring.llm.prices[test-model].input-per-million=10",
        "scoring.llm.prices[test-model].output-per-million=10",
        "scoring.budget.daily-usd=0.10",
        "scoring.budget.zone=UTC",
        "scoring.ingestion.cron=-",
        "scoring.ingestion.chunk-size=4"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScoringTestConfig.class})
class ScoringRunnerCapTest extends ScoringTestSupport {

    static final StubIngestion STUB = new StubIngestion();

    @Autowired ScoringRunner runner;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.gnemirko.jobapplier.scoring.service.UsageLedger ledger;
    @Autowired IngestCursorRepository cursors;

    @DynamicPropertySource
    static void ingestion(DynamicPropertyRegistry registry) {
        registry.add("scoring.ingestion.base-url", STUB::baseUrl);
    }

    @Test
    void capReachedInChunkTwoStopsTheRunAndCursorCoversOnlyProcessedChunks() {
        cursors.deleteAll();
        STUB.reset();
        STUB.jobs = ScoringRunnerTest.many(12);

        runner.runNow();

        assertThat(model.calls()).isEqualTo(4);
        assertThat(assessments.count()).isEqualTo(8); // chunk 3 was never sent
        assertThat(assessments.findAll().stream().filter(a -> a.getStatus() == AssessmentStatus.UNSCORED_BUDGET)).hasSize(4);
        Instant cursor = cursors.findById(IngestCursorEntity.ID).orElseThrow().getLastFirstSeenAt();
        assertThat(cursor).isEqualTo(ScoringRunnerTest.BASE.plusSeconds(7));
    }

    @Test
    void usageEndpointReportsNearCapAndCapReached() throws Exception {
        ledger.record("test-model", 0, 0, 79_000, false, "k1");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/usage/today"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.budgetState").value("OK"));
        ledger.record("test-model", 0, 0, 1_000, false, "k2");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/usage/today"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.budgetState").value("NEAR_CAP"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.spentUsd").value("0.080000"));
        ledger.record("test-model", 0, 0, 20_000, false, "k3");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/usage/today"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.budgetState").value("CAP_REACHED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.calls").value(3));
    }
}
