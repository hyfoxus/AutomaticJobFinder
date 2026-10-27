package com.gnemirko.jobapplier.ingestion.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.gnemirko.jobapplier.ingestion.TestcontainersConfiguration;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeLearner;

/** The HTTP surface of the learner, end to end with a stub site and a stub scoring service. */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LearnerApiTest {

    static final StubSite SITE;
    static final StubScoring SCORING;
    static final Path DIR;

    static {
        try {
            SITE = new StubSite();
            SCORING = new StubScoring();
            DIR = Files.createTempDirectory("learner-api");
            Files.writeString(DIR.resolve("token"), "0123456789abcdef-api-test");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("ingestion.learner.enabled", () -> "true");
        r.add("ingestion.learner.allowed-hosts", () -> "127.0.0.1");
        r.add("ingestion.learner.token-file", () -> DIR.resolve("token").toString());
        r.add("ingestion.learner.output-dir", () -> DIR.resolve("proposed").toString());
        r.add("ingestion.learner.scoring-base-url", SCORING::baseUrl);
        r.add("ingestion.recipes.dir", () -> DIR.resolve("recipes").toString());
        r.add("ingestion.fetch.allow-insecure-hosts", () -> "127.0.0.1");
        r.add("ingestion.fetch.min-delay", () -> "0ms");
    }

    @AfterAll
    static void close() throws Exception {
        SITE.close();
        SCORING.close();
    }

    @Autowired MockMvc mvc;
    @Autowired RecipeLearner learner;

    private static final String BODY = "{\"url\":\"%s/careers\"}";

    @Test
    void guardsAndValidation() throws Exception {
        mvc.perform(post("/api/learn").contentType("application/json").content(BODY.formatted(SITE.base())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/learn").header("X-JobApplier-Run", "1").contentType("application/json")
                .content(BODY.formatted(SITE.base())).with(r -> {
                    r.setServerName("evil.example.com");
                    return r;
                })).andExpect(status().isForbidden());
        mvc.perform(post("/api/learn").header("X-JobApplier-Run", "1").contentType("application/json")
                .content("{\"url\":\"https://not-listed.example.com/jobs\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/learn").header("X-JobApplier-Run", "1").contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(java.net.URI.create("/api/%6Cearn")).header("X-JobApplier-Run", "1").contentType("application/json")
                .content(BODY.formatted(SITE.base())).with(r -> {
                    r.setServerName("evil.example.com");
                    return r;
                })).andExpect(status().isForbidden());
        assertThat(SITE.hits).isEmpty();
        // The scoring service reads /api/jobs under the Host ingestion-service; the learner guard must not touch it.
        mvc.perform(get("/api/jobs").with(r -> {
            r.setServerName("ingestion-service");
            return r;
        })).andExpect(status().isOk());
    }

    @Test
    void goodRunAnswers202AndWritesTheRecipe() throws Exception {
        SITE.standardCareers(4);
        SCORING.reply(SITE.goodReply());
        MvcResult res = mvc.perform(post("/api/learn").header("X-JobApplier-Run", "1")
                .contentType("application/json").content(BODY.formatted(SITE.base())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.name").value("127-0-0-1"))
                .andExpect(jsonPath("$.runId").isNotEmpty())
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain("Engineer");
        Path recipe = DIR.resolve("proposed/127-0-0-1.json");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while ((learner.isBusy() || !Files.exists(recipe)) && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertThat(recipe).exists();
        assertThat(Files.readString(recipe)).contains("\"enabled\" : false");
        assertThat(Files.readString(DIR.resolve("proposed/127-0-0-1.report.txt"))).contains("Engineer 1");
        assertThat(DIR.resolve("recipes")).doesNotExist();
    }
}
