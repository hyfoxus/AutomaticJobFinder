package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class ScoringApiTest extends WiringTestBase {

    @Autowired MockMvc mvc;
    @Autowired com.gnemirko.jobapplier.scoring.service.ScoringRunner runner;

    @Test
    void listIsNewestFirstAndNeverShowsDescriptionOrPrompt() throws Exception {
        scoring.score(jobs("a", "b", "c"));

        MvcResult res = mvc.perform(get("/api/assessments?limit=2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Java Developer"))
                .andExpect(jsonPath("$[0].status").value("SCORED"))
                .andExpect(jsonPath("$[0].tier").value("STRONG"))
                .andExpect(jsonPath("$[0].metMustHaves[0]").value(1))
                .andExpect(jsonPath("$[0].reason").value("Good fit."))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body).doesNotContain("description").doesNotContain("prompt").doesNotContain("Build services with Spring Boot");
        assertThat(body.indexOf("/a")).isLessThan(body.indexOf("/b")); // a is the newest
    }

    @Test
    void statusFilterIsCaseInsensitiveAndUnknownIsRejected() throws Exception {
        scoring.score(jobs("a"));

        mvc.perform(get("/api/assessments?status=scored")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/assessments?status=failed")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/assessments?status=nope")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void badLimitsAre400AndHugeLimitIsClamped() throws Exception {
        mvc.perform(get("/api/assessments?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?limit=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?limit=abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/assessments?limit=100000")).andExpect(status().isOk());
    }

    @Test
    void nonPendingStatusesShowAFixedLabelInsteadOfStoredText() throws Exception {
        model.fail(new RuntimeException("secret provider text"));
        scoring.score(jobs("a"));

        MvcResult res = mvc.perform(get("/api/assessments?status=FAILED")).andExpect(status().isOk()).andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain("secret provider text")
                .contains("Scoring failed");
    }

    @Test
    void usageReportsDollarsAsStrings() throws Exception {
        scoring.score(jobs("a"));

        mvc.perform(get("/api/usage/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capUsd").value("10.000000"))
                .andExpect(jsonPath("$.spentUsd").isString())
                .andExpect(jsonPath("$.budgetState").value("OK"))
                .andExpect(jsonPath("$.calls").value(1));
    }

    @Test
    void foreignHostIsRefused() throws Exception {
        mvc.perform(get("/api/usage/today").header("Host", "evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/usage/today").header("Host", "127.0.0.1:8082")).andExpect(status().isOk());
    }

    @Test
    void hostGuardDoesNotDependOnTheRawUri() throws Exception {
        mvc.perform(get("/%61pi/usage/today").header("Host", "evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api;x=1/assessments").header("Host", "evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health").header("Host", "evil.example")).andExpect(status().isForbidden());
    }

    @Test
    void hugeLimitIsCappedAt200() throws Exception {
        java.util.List<com.gnemirko.jobapplier.scoring.entity.AssessmentEntity> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 230; i++) {
            rows.add(com.gnemirko.jobapplier.scoring.entity.AssessmentEntity.pending(
                    ScoringRunnerTest.at("m" + i, ScoringRunnerTest.BASE.plusSeconds(i)), clock.instant()));
        }
        assessments.saveAll(rows);
        mvc.perform(get("/api/assessments?limit=100000")).andExpect(jsonPath("$.length()").value(200));
        mvc.perform(get("/api/assessments?limit=99999999999999999999999")).andExpect(jsonPath("$.length()").value(200));
    }

    @Test
    void errorsAreAlwaysJson() throws Exception {
        mvc.perform(post("/api/assessments")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/nothing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/usage/today").accept("text/html")).andExpect(status().isNotAcceptable());
    }

    @Test
    void triggerNeedsTheHeaderAndAnswers409WhileBusy() throws Exception {
        mvc.perform(post("/api/scoring/run")).andExpect(status().isBadRequest());

        STUB.jobs = ScoringRunnerTest.many(1);
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        model.onCall = p -> {
            inCall.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        mvc.perform(post("/api/scoring/run").header("X-JobApplier-Run", "1")).andExpect(status().isAccepted());
        assertThat(inCall.await(10, TimeUnit.SECONDS)).isTrue();
        mvc.perform(post("/api/scoring/run").header("X-JobApplier-Run", "1")).andExpect(status().isConflict());
        release.countDown();
        long end = System.currentTimeMillis() + 10_000;
        while (runner.isRunning() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertThat(runner.isRunning()).isFalse(); // no run outlives the test
    }

    @Test
    void writesOtherThanTheTriggerAreNotAvailable() throws Exception {
        mvc.perform(post("/api/assessments")).andExpect(status().isMethodNotAllowed());
    }
}
