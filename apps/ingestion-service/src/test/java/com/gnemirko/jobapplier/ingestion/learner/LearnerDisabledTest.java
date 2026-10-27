package com.gnemirko.jobapplier.ingestion.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.gnemirko.jobapplier.ingestion.TestcontainersConfiguration;
import com.gnemirko.jobapplier.ingestion.config.FetchProperties;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeLearner;

/** With default settings the learner does not exist and ingestion is unchanged. */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LearnerDisabledTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired FetchProperties fetch;

    @Test
    void noLearnerBeansAndEndpointIs404() throws Exception {
        assertThat(context.getBeanNamesForType(RecipeLearner.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LearnerProperties.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.ingestion.service.RecipeValidator.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.gnemirko.jobapplier.ingestion.learner.source.LlmClient.class)).isEmpty();
        mvc.perform(post("/api/learn").header("X-JobApplier-Run", "1").contentType("application/json")
                .content("{\"url\":\"https://example.com/\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs")).andExpect(status().isOk());
    }

    @Test
    void insecureHostOverrideIsEmptyByDefault() {
        assertThat(fetch.allowInsecureHosts()).isEmpty();
    }
}
