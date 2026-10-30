package com.gnemirko.jobapplier.ingestion.learner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.service.RecipeValidator;

import tools.jackson.databind.json.JsonMapper;

/** Every place that rebuilds a Recipe field by field keeps the jsonApi block. */
class JsonApiRebuildTest {

    private static Recipe.JsonApi api(String url) {
        return new Recipe.JsonApi(url, "POST", Map.of("p", "${page}"),
                JsonMapper.builder().build().readTree("{\"page\":\"${page}\"}"),
                new Recipe.Paging("page", 1, 20, 30, null), "items",
                Map.of("id", JsonMapper.builder().build().readTree("\"id\""),
                        "title", JsonMapper.builder().build().readTree("\"title\""),
                        "url", JsonMapper.builder().build().readTree("\"link\"")),
                null, 500);
    }

    private static Recipe recipe(String url) {
        return new Recipe("api-x", false, Recipe.STRATEGY_JSON_API, null, null, null, null, new Recipe.Limits(50), api(url));
    }

    @Test
    void lockAndClampKeepsJsonApiAndLocksItsHost() {
        URI target = URI.create("https://api.example.com/careers");

        Recipe kept = RecipeProposal.lockAndClamp(recipe("https://api.example.com/jobs"), target, "api-y");

        assertThat(kept.jsonApi()).isEqualTo(api("https://api.example.com/jobs"));
        assertThat(kept.name()).isEqualTo("api-y");
        assertThatThrownBy(() -> RecipeProposal.lockAndClamp(recipe("https://evil.example.org/jobs"), target, "api-y"))
                .hasMessageContaining("jsonApi.url");
    }

    @Test
    void aModelReplyWithAValidJsonApiBlockIsRefusedForItsStrategy() {
        String reply = RecipeProposal.serialize(recipe("https://api.example.com/jobs"));

        RecipeProposal.Result r = RecipeProposal.parse(reply, URI.create("https://api.example.com/"), "x",
                new RecipeValidator(List.of()));

        assertThat(r).isInstanceOf(RecipeProposal.Rejected.class);
        assertThat(((RecipeProposal.Rejected) r).reason()).contains("json-api strategy is not allowed");
    }

    @Test
    void theEnabledCopyKeepsJsonApi(@TempDir Path tmp) throws Exception {
        Path auto = Files.createDirectory(tmp.resolve("auto"));
        ProposalWriter writer = new ProposalWriter(tmp.resolve("out"), tmp.resolve("recipes"), auto);

        assertThat(writer.writeEnabled(recipe("https://api.example.com/jobs"), new RecipeValidator(List.of()))).isTrue();

        String written = Files.readString(auto.resolve("api-x.json"));
        Recipe back = JsonMapper.builder().build().readValue(written, Recipe.class);
        assertThat(back.enabled()).isTrue();
        assertThat(back.jsonApi()).isEqualTo(api("https://api.example.com/jobs"));
    }

    @Test
    void serializedRecipesRoundTrip() {
        Recipe r = recipe("https://api.example.com/jobs");

        Recipe back = JsonMapper.builder().build().readValue(RecipeProposal.serialize(r), Recipe.class);

        assertThat(back).isEqualTo(r);
    }
}
