package com.gnemirko.jobapplier.ingestion.learner.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;

import tools.jackson.databind.json.JsonMapper;

class SampleRunnerJsonApiTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Recipe recipe(String strategy) {
        Recipe.JsonApi api = new Recipe.JsonApi("https://api.example.com/jobs", "GET", Map.of("p", "${page}"), null,
                new Recipe.Paging("page", 1, 50, 40, "total"), "items",
                Map.of("id", MAPPER.readTree("\"id\""), "title", MAPPER.readTree("\"t\""), "url", MAPPER.readTree("\"u\"")),
                null, 900);
        return new Recipe("api-x", true, strategy, null, null, null, null, new Recipe.Limits(50), api);
    }

    @Test
    void theSampleCopyKeepsJsonApiAndClampsToTwoPagesAndTwentyItems() {
        Recipe copy = SampleRunner.clampedCopy(recipe(Recipe.STRATEGY_JSON_API));

        assertThat(copy.enabled()).isFalse();
        assertThat(copy.jsonApi().url()).isEqualTo("https://api.example.com/jobs");
        assertThat(copy.jsonApi().paging().maxPages()).isEqualTo(2);
        assertThat(copy.jsonApi().paging().size()).isEqualTo(50);
        assertThat(copy.jsonApi().paging().totalPath()).isEqualTo("total");
        assertThat(copy.jsonApi().maxItems()).isEqualTo(20);
        assertThat(copy.jsonApi().fields()).containsKeys("id", "title", "url");
    }

    @Test
    void anUnknownStrategyThrowsInsteadOfFallingBackToTheListingEngine() {
        SampleRunner runner = new SampleRunner(null, java.time.Clock.systemUTC(), java.time.Duration.ofSeconds(1));

        assertThatThrownBy(() -> runner.run(recipe("workday"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no sample run");
    }
}
