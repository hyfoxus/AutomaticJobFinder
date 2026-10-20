package com.gnemirko.jobapplier.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gnemirko.jobapplier.scoring.repository.IngestCursorRepository;

/** Scoring enabled, a stub ingestion, a fake model, real Postgres. Roomy budget. */
@SpringBootTest(properties = {
        "scoring.enabled=true",
        "scoring.profile-path=src/test/resources/profile.test.yml",
        "scoring.llm.model=test-model",
        "scoring.llm.max-tokens=1000",
        "scoring.llm.prices[test-model].input-per-million=0.01",
        "scoring.llm.prices[test-model].output-per-million=0.01",
        "scoring.budget.daily-usd=10.00",
        "scoring.budget.zone=UTC",
        "scoring.ingestion.cron=-",
        "scoring.ingestion.chunk-size=50",
        "scoring.ingestion.read-timeout=1s",
        "scoring.ingestion.max-bytes=300000"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScoringTestConfig.class})
abstract class WiringTestBase extends ScoringTestSupport {

    static final StubIngestion STUB = new StubIngestion();

    @Autowired protected IngestCursorRepository cursors;

    @DynamicPropertySource
    static void ingestion(DynamicPropertyRegistry registry) {
        registry.add("scoring.ingestion.base-url", STUB::baseUrl);
    }

    @BeforeEach
    void cleanWiring() {
        cursors.deleteAll();
        STUB.reset();
    }
}
