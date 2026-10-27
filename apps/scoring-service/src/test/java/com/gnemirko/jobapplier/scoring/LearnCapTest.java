package com.gnemirko.jobapplier.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Shared cap of 40 micro-dollars: a learn call reserves about 28; the learn sub-cap is 20. */
@TestPropertySource(properties = {
        "scoring.learn.token=0123456789abcdef-test-token",
        "scoring.budget.daily-usd=0.00004",
        "scoring.learn.max-tokens=1000",
        "scoring.learn.daily-usd=0.00002"
})
class LearnCapTest extends WiringTestBase {

    @Autowired MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions call(String host) throws Exception {
        return mvc.perform(post("/api/llm/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"host\":\"" + host + "\",\"skeleton\":\"x\"}")
                .header("X-Learner-Token", "0123456789abcdef-test-token").header("X-JobApplier-Run", "1"));
    }

    @Test
    void atTheSharedCapAnswers402AndMakesNoCall() throws Exception {
        usage.save(new com.gnemirko.jobapplier.scoring.entity.LlmUsageEntity(clock.instant(),
                java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC), "test-model", 0, 0, 39,
                false, "other"));
        call("a.example.com").andExpect(status().isPaymentRequired());
        assertThat(model.calls()).isZero();
        assertThat(usage.count()).isEqualTo(1);
    }

    @Test
    void learnUsdSubCapAnswers429() throws Exception {
        call("a.example.com").andExpect(status().isTooManyRequests());
        assertThat(model.calls()).isZero();
        assertThat(usage.count()).isZero();
    }
}
