package com.gnemirko.jobapplier.scoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.entity.AssessmentEntity;

/** The tier and page parameters of GET /api/assessments. */
class ScoringPagingTest extends WiringTestBase {

    @Autowired MockMvc mvc;

    private void scoreThree() {
        // a is newest. a and c are PARTIAL, b is STRONG.
        model.reply(FakeChatModel.VALID.replace("STRONG", "PARTIAL"), 1000, 1000)
                .replyValid()
                .reply(FakeChatModel.VALID.replace("STRONG", "PARTIAL"), 1000, 1000);
        scoring.score(jobs("a", "b", "c"));
    }

    @Test
    void tierFiltersScoredRows() throws Exception {
        scoreThree();
        mvc.perform(get("/api/assessments?status=SCORED&tier=partial"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].url").value("https://example.com/a"))
                .andExpect(jsonPath("$[1].url").value("https://example.com/c"));
        mvc.perform(get("/api/assessments?tier=STRONG"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://example.com/b"));
        mvc.perform(get("/api/assessments?tier=WEAK")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void pagesAndHasMoreHeader() throws Exception {
        scoring.score(jobs("a", "b", "c"));
        mvc.perform(get("/api/assessments?limit=2&page=0"))
                .andExpect(header().string("X-Has-More", "true"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].url").value("https://example.com/a"));
        mvc.perform(get("/api/assessments?limit=2&page=1"))
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].url").value("https://example.com/c"));
        mvc.perform(get("/api/assessments?limit=2&page=5"))
                .andExpect(header().string("X-Has-More", "false"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void tiesOnFirstSeenAreOrderedByIdDescending() throws Exception {
        List<AssessmentEntity> rows = new ArrayList<>();
        for (String id : List.of("t1", "t2", "t3")) {
            rows.add(AssessmentEntity.pending(ScoringRunnerTest.at(id, ScoringRunnerTest.BASE), clock.instant()));
        }
        assessments.saveAll(rows);
        mvc.perform(get("/api/assessments?limit=2&page=0"))
                .andExpect(jsonPath("$[0].url").value("https://example.com/t3"))
                .andExpect(jsonPath("$[1].url").value("https://example.com/t2"));
        mvc.perform(get("/api/assessments?limit=2&page=1"))
                .andExpect(jsonPath("$[0].url").value("https://example.com/t1"));
    }

    @Test
    void badTierAndPageAre400() throws Exception {
        mvc.perform(get("/api/assessments?tier=great")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?page=x")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?page=99999999999999999999999")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?status=FAILED&tier=STRONG")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?scoredSince=2026-01-01T00:00:00Z&tier=STRONG")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?scoredSince=2026-01-01T00:00:00Z&page=0")).andExpect(status().isBadRequest());
    }

    @Test
    void blankPageIsLikeMissingWithScoredSince() throws Exception {
        mvc.perform(get("/api/assessments?scoredSince=2026-01-01T00:00:00Z&page=")).andExpect(status().isOk());
        mvc.perform(get("/api/assessments?scoredSince=2026-01-01T00:00:00Z").param("page", " ")).andExpect(status().isOk());
    }

    @Test
    void windowIsLimitedTo10000Rows() throws Exception {
        // limit 50: page 199 ends at row 10000, page 200 would start there.
        mvc.perform(get("/api/assessments?limit=50&page=199")).andExpect(status().isOk());
        mvc.perform(get("/api/assessments?limit=50&page=200")).andExpect(status().isBadRequest());
        // limit 200: page 49 ends at row 10000.
        mvc.perform(get("/api/assessments?limit=200&page=49")).andExpect(status().isOk());
        mvc.perform(get("/api/assessments?limit=200&page=50")).andExpect(status().isBadRequest());
        // A limit over 200 is clamped before the check.
        mvc.perform(get("/api/assessments?limit=1000&page=50")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?limit=7&page=1422")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?limit=7&page=1421")).andExpect(status().isOk());
    }
}
