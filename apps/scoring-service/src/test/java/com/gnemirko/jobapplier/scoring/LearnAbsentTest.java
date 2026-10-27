package com.gnemirko.jobapplier.scoring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Scoring enabled but no learner token: the endpoint does not exist. */
class LearnAbsentTest extends WiringTestBase {

    @Autowired MockMvc mvc;

    @Test
    void endpointIsAbsentWithoutToken() throws Exception {
        mvc.perform(post("/api/llm/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"host\":\"a.example.com\",\"skeleton\":\"x\"}")
                .header("X-Learner-Token", "anything-anything-anything").header("X-JobApplier-Run", "1"))
                .andExpect(status().isNotFound());
    }
}
