package com.gnemirko.jobapplier.scoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** With defaults the new endpoints do not exist. */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ScoringApiDisabledTest {

    @Autowired MockMvc mvc;

    @Test
    void endpointsAre404() throws Exception {
        mvc.perform(get("/api/assessments")).andExpect(status().isNotFound());
        mvc.perform(get("/api/usage/today")).andExpect(status().isNotFound());
        mvc.perform(post("/api/llm/complete").header("X-JobApplier-Run", "1")).andExpect(status().isNotFound());
        mvc.perform(post("/api/scoring/run").header("X-JobApplier-Run", "1")).andExpect(status().isNotFound());
    }
}
