package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.service.RecipeRegistry;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validation rows of the json-api matrix, through the registry's public validate and the strict file loader. */
class JsonApiValidationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static String recipeJson(String url, String method, String query, String body, String paging, String itemsPath,
            String fields, String jobUrlTemplate) {
        return "{\"name\":\"api-x\",\"strategy\":\"json-api\",\"jsonApi\":{\"url\":\"" + url + "\",\"method\":\"" + method
                + "\"" + (query == null ? "" : ",\"query\":" + query) + (body == null ? "" : ",\"bodyTemplate\":" + body)
                + ",\"paging\":" + paging + ",\"itemsPath\":\"" + itemsPath + "\",\"fields\":" + fields
                + (jobUrlTemplate == null ? "" : ",\"jobUrlTemplate\":\"" + jobUrlTemplate + "\"") + "}}";
    }

    private static final String PAGING = "{\"kind\":\"page\",\"size\":20}";
    private static final String ONE_PAGE = "{\"kind\":\"page\",\"size\":20,\"maxPages\":1}";
    private static final String PAGE_QUERY = "{\"p\":\"${page}\"}";
    private static final String FIELDS = "{\"id\":\"id\",\"title\":\"title\",\"url\":\"link\"}";

    private static Recipe parse(String json) {
        return JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
                .readValue(json, Recipe.class);
    }

    private static Recipe ok() {
        return parse(recipeJson("https://api.example.com/jobs", "GET", "{\"p\":\"${page}\"}", null, PAGING, "items",
                FIELDS, null));
    }

    private static void rejects(String json, String fragment) {
        assertThatThrownBy(() -> RecipeRegistry.validate(parse(json))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(fragment);
    }

    @Test
    void aGoodRecipeValidatesAndDefaultsApply() {
        Recipe r = ok();
        assertThatCode(() -> RecipeRegistry.validate(r)).doesNotThrowAnyException();
        assertThat(r.jsonApi().paging().start()).isEqualTo(1);
        assertThat(r.jsonApi().paging().maxPages()).isEqualTo(10);
        assertThat(r.jsonApi().maxItems()).isEqualTo(1000);
    }

    @Test
    void unknownFieldsAreRejectedByTheStrictLoader() {
        assertThatThrownBy(() -> parse(recipeJson("https://a.example.com/j", "GET", null, null, PAGING, "items", FIELDS, null)
                .replace("\"itemsPath\"", "\"headers\":{\"X\":\"y\"},\"itemsPath\"")))
                .hasMessageContaining("headers");
        assertThatThrownBy(() -> parse(recipeJson("https://a.example.com/j", "GET", null, null,
                "{\"kind\":\"page\",\"size\":20,\"cursor\":\"x\"}", "items", FIELDS, null))).hasMessageContaining("cursor");
    }

    @Test
    void badBodyTemplatesAreRejected() {
        String post = "POST";
        rejects(recipeJson("https://a.example.com/j", post, null, "{\"q\":\"page ${page}\"}", PAGING, "items", FIELDS, null),
                "whole value");
        rejects(recipeJson("https://a.example.com/j", post, null, "{\"a${page}\":1}", PAGING, "items", FIELDS, null), "key");
        rejects(recipeJson("https://a.example.com/j", post, null, "{\"q\":\"${token}\"}", PAGING, "items", FIELDS, null),
                "whole value");
        rejects(recipeJson("https://a.example.com/j", post, null, "{\"q\":\"${offset}\"}", PAGING, "items", FIELDS, null),
                "does not fit paging kind");
        rejects(recipeJson("https://a.example.com/j", post, null, "{\"q\":\"x\"}".replace("x", "x".repeat(5000)), PAGING,
                "items", FIELDS, null), "larger than");
        rejects(recipeJson("https://a.example.com/j", post, null, "[[[[[[[1]]]]]]]", PAGING, "items", FIELDS, null),
                "deeper");
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 60; i++) {
            many.append(i > 0 ? "," : "").append(i);
        }
        rejects(recipeJson("https://a.example.com/j", post, null, many + "]", PAGING, "items", FIELDS, null), "nodes");
        rejects(recipeJson("https://a.example.com/j", post, null, "{\"q\":\"a\\u0001b\"}", PAGING, "items", FIELDS, null),
                "control");
        rejects(recipeJson("https://a.example.com/j", "POST", null, null, PAGING, "items", FIELDS, null), "required for method POST");
        rejects(recipeJson("https://a.example.com/j", "GET", null, "{}", PAGING, "items", FIELDS, null), "does not belong");
    }

    @Test
    void badQueriesAndUrlsAreRejected() {
        rejects(recipeJson("https://a.example.com/j", "GET", "{\"p\":\"x${page}\"}", null, PAGING, "items", FIELDS, null),
                "whole value");
        rejects(recipeJson("https://a.example.com/j?x=1", "GET", null, null, PAGING, "items", FIELDS, null), "query");
        rejects(recipeJson("https://a.example.com/j#top", "GET", null, null, PAGING, "items", FIELDS, null), "fragment");
        rejects(recipeJson("https://user:pw@a.example.com/j", "GET", null, null, PAGING, "items", FIELDS, null), "credentials");
        rejects(recipeJson("http://a.example.com/j", "GET", null, null, PAGING, "items", FIELDS, null), "https");
        rejects(recipeJson("https://a.example.com/j", "PUT", null, null, PAGING, "items", FIELDS, null), "GET or POST");
    }

    @Test
    void unmappableKeysAndFieldProblemsAreRejected() {
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, PAGING, "items", "{\"id\":\"a b\",\"title\":\"t\",\"url\":\"u\"}", null),
                "bad key");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, PAGING, "items", "{\"id\":\"x@y\",\"title\":\"t\",\"url\":\"u\"}", null),
                "bad key");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, PAGING, "da ta", FIELDS, null), "bad key");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, PAGING, "items", "{\"id\":\"id\",\"url\":\"u\"}", null),
                "must map 'title'");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, PAGING, "items", "{\"id\":\"id\",\"title\":\"t\"}", null),
                "exactly one of fields.url and jobUrlTemplate");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, PAGING, "items", FIELDS, "https://a.example.com/{id}"),
                "exactly one of fields.url and jobUrlTemplate");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, PAGING, "items", "{\"id\":\"id\",\"title\":\"t\",\"bogus\":\"b\"}", "https://a.example.com/{id}"),
                "unmappable field");
    }

    @Test
    void pagingAndLimitsAreBounded() {
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, "{\"kind\":\"cursor\",\"size\":5}", "items", FIELDS, null), "paging.kind");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, "{\"kind\":\"page\",\"size\":5000}", "items", FIELDS, null), "paging.size");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, "{\"kind\":\"page\",\"size\":5,\"maxPages\":51}", "items", FIELDS, null), "maxPages");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, "{\"kind\":\"page\"}", "items", FIELDS, null), "paging.size");
        rejects(recipeJson("https://a.example.com/j", "GET", PAGE_QUERY, null, PAGING, "items", FIELDS, null)
                .replace(FIELDS + "}}", FIELDS + ",\"maxItems\":2001}}"), "maxItems");
        assertThatCode(() -> RecipeRegistry.validate(parse(recipeJson("https://a.example.com/j", "GET", PAGE_QUERY, null,
                PAGING, "items", FIELDS, null).replace(FIELDS + "}}", FIELDS + ",\"maxItems\":2000}}"))))
                .doesNotThrowAnyException();
    }

    @Test
    void jobUrlTemplateRules() {
        String f = "{\"id\":\"id\",\"title\":\"t\"}";
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items", f, "https://a.example.com/jobs"), "{id} once");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items", f, "http://a.example.com/{id}"), "https");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items", f, "https://{id}.example.com/x"), "path or query");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items", f, "https://a.example.com:{id}/x"), "path or query");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items", f, "https://a.example.com/a/../{id}"), "dot segment");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items", f, "https://a.example.com/%2e%2e/{id}"), "dot segment");
        Recipe hostForms = parse(recipeJson("https://a.example.com:443/j", "GET", null, null, ONE_PAGE, "items", f,
                "https://A.example.com./x/{id}?a=1"));
        Recipe hostFormsDetail = new Recipe("api-x", false, hostForms.strategy(), null, null, null,
                new Recipe.Detail(Recipe.EXTRACTOR_JSON_LD, Recipe.ID_JSONLD), hostForms.limits(), hostForms.jsonApi());
        assertThatCode(() -> RecipeRegistry.validate(hostFormsDetail)).doesNotThrowAnyException();
        Recipe r = parse(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items", f, "https://other.example.org/{id}"));
        assertThatCode(() -> RecipeRegistry.validate(r)).doesNotThrowAnyException();
        Recipe withDetail = new Recipe(r.name(), false, r.strategy(), null, null, null,
                new Recipe.Detail(Recipe.EXTRACTOR_JSON_LD, Recipe.ID_JSONLD), r.limits(), r.jsonApi());
        assertThatThrownBy(() -> RecipeRegistry.validate(withDetail)).hasMessageContaining("endpoint's host");
    }

    @Test
    void otherStrategiesRefuseAJsonApiBlock() {
        Recipe api = ok();
        Recipe sm = new Recipe("x", false, Recipe.STRATEGY_SITEMAP, null, new Recipe.Sitemap("https://a.example.com/s.xml", "/j/", 10),
                null, new Recipe.Detail(Recipe.EXTRACTOR_JSON_LD, Recipe.ID_JSONLD), new Recipe.Limits(5), api.jsonApi());
        assertThatThrownBy(() -> RecipeRegistry.validate(sm)).hasMessageContaining("jsonApi block does not belong");
    }

    @Test
    void theRegistryLoadsAJsonApiFileAndSkipsOneWithUnknownFields(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("good.json"), recipeJson("https://api.example.com/jobs", "GET", PAGE_QUERY, null, PAGING,
                "items", FIELDS, null).replace("\"api-x\"", "\"api-good\""));
        Files.writeString(dir.resolve("bad.json"), recipeJson("https://api.example.com/jobs", "GET", null, null, PAGING,
                "items", FIELDS, null).replace("\"itemsPath\"", "\"headers\":{},\"itemsPath\"").replace("\"api-x\"", "\"api-bad\""));

        RecipeRegistry registry = new RecipeRegistry(dir, java.util.List.of());

        assertThat(registry.recipes()).extracting(Recipe::name).containsExactly("api-good");
    }

    @Test
    void requestTemplateEncodesQueryNamesAndValues() {
        assertThat(RequestTemplate.renderQuery(Map.of("b", "${size}", "a", "x y&z", "c", "${page}"), 3, 40, 20))
                .isEqualTo("a=x%20y%26z&b=20&c=3");
        JsonNode t = MAPPER.readTree("{\"n\":\"${offset}\",\"l\":[\"${size}\",\"keep\"]}");
        assertThat(RequestTemplate.renderBody(t, 1, 40, 20).toString()).isEqualTo("{\"n\":40,\"l\":[20,\"keep\"]}");
    }

    @Test
    void pagingSlotBodyAndNullRules() {
        rejects(recipeJson("https://a.example.com/j", "GET", "{\"s\":\"${size}\"}", null, PAGING, "items", FIELDS, null),
                "${page} must appear");
        rejects(recipeJson("https://a.example.com/j", "POST", null, "{\"o\":\"${page}\"}",
                "{\"kind\":\"offset\",\"size\":5}", "items", FIELDS, null), "does not fit paging kind");
        rejects(recipeJson("https://a.example.com/j", "POST", null, "\"text\"", ONE_PAGE, "items", FIELDS, null),
                "JSON object or array");
        rejects(recipeJson("https://a.example.com/j", "POST", null, "null", ONE_PAGE, "items", FIELDS, null),
                "required for method POST");
        assertThatThrownBy(() -> parse(recipeJson("https://a.example.com/j", "GET", "{\"p\":null}", null, ONE_PAGE, "items",
                FIELDS, null))).hasMessageContaining("jsonApi.query has a null value");
        // a JSON null in fields reaches the mapper as a null node and gets its own clear message
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items",
                "{\"id\":null,\"title\":\"t\",\"url\":\"u\"}", null), "field 'id' has no mapping");
        rejects(recipeJson("https://a.example.com/j", "GET", null, null, ONE_PAGE, "items", "{\"id\":\"id\",\"url\":\"u\"}", null),
                "jsonApi.fields must map 'title'");
    }

    @Test
    void unsafePathsAreRecognised() {
        for (String bad : new String[] {"/a/./b", "/a/../b", "//x", "/a//b", "/a/%2e%2E/b", "/a%2Fb", "/a%5cb", "/a\\b",
                "https://h.example.com/a/..", "../x"}) {
            assertThat(JsonApiValidator.unsafePath(bad)).as(bad).isTrue();
        }
        for (String good : new String[] {"/jobs/1", "https://h.example.com/jobs/1?next=//x#a/../b", "jobs/1.html"}) {
            assertThat(JsonApiValidator.unsafePath(good)).as(good).isFalse();
        }
    }
}
