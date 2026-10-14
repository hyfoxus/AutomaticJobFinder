package com.gnemirko.jobapplier.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecipeRegistryTest {

    @TempDir
    Path dir;

    private static String recipe(String name, String enabled, String urlTemplate, String linkPattern, String selector,
            String extra) {
        return """
                {"name":"%s","enabled":%s,"strategy":"paginated-listing",
                 "listing":{"urlTemplate":"%s","maxPages":5,"linkSelector":"%s","linkPattern":"%s"},
                 "detail":{"extractor":"json-ld","idFrom":"jsonld"}%s}""".formatted(
                name, enabled, urlTemplate, selector, linkPattern, extra);
    }

    private static String good(String name) {
        return recipe(name, "true", "https://a.example/jobs?page={page}", "/jobs/[a-z]+-[0-9]+$", "a[href]", "");
    }

    private void write(String file, String content) throws Exception {
        Files.writeString(dir.resolve(file), content);
    }

    private List<String> names() {
        return new RecipeRegistry(dir, List.of("remotive")).recipes().stream().map(r -> r.name()).toList();
    }

    @Test
    void loadsGoodRecipeWithDefaults() throws Exception {
        write("a.json", good("alpha"));
        var r = new RecipeRegistry(dir, List.of("remotive")).recipes().get(0);
        assertThat(r.listing().firstPage()).isEqualTo(1);
        assertThat(r.limits().maxDetailFetchesPerRun()).isEqualTo(200);
        assertThat(r.isEnabled()).isTrue();
    }

    @Test
    void badFilesAreSkippedAndOthersLoad() throws Exception {
        write("a-good.json", good("alpha"));
        write("b-invalid-json.json", "{nope");
        write("c-unknown-field.json", good("charlie").replace("\"name\"", "\"surprise\":1,\"name\""));
        write("d-bad-regex.json", recipe("delta", "true", "https://a.example/{page}", "(unclosed", "a", ""));
        write("e-bad-selector.json", recipe("echo", "true", "https://a.example/{page}", "x", "a[[", ""));
        write("f-dup.json", good("alpha"));
        write("g-reserved.json", good("Remotive"));
        write("h-http.json", recipe("hotel", "true", "http://a.example/{page}", "x", "a", ""));
        write("i-nested.json", recipe("india", "true", "https://a.example/{page}", "(a+)+$", "a", ""));
        write("j-long.json", recipe("juliet", "true", "https://a.example/{page}", "a".repeat(201), "a", ""));
        write("k-name.json", good("Bad_Name"));
        write("l-nopage.json", recipe("lima", "true", "https://a.example/jobs", "x", "a", ""));
        write("m-cap.json", recipe("mike", "true", "https://a.example/{page}", "x", "a",
                ",\"limits\":{\"maxDetailFetchesPerRun\":5000}"));
        write("z-good.json", good("zulu"));
        assertThat(names()).containsExactly("alpha", "zulu");
    }

    @Test
    void missingMaxPagesAndBadIdFromAreRejected() throws Exception {
        write("a.json", good("alpha").replace("\"maxPages\":5,", ""));
        write("b.json", good("bravo").replace("\"jsonld\"", "\"urlRegex:(a)(b)\""));
        write("c.json", good("charlie").replace("\"jsonld\"", "\"urlRegex:-([0-9]+)$\""));
        assertThat(names()).containsExactly("charlie");
    }

    @Test
    void missingDirectoryIsFine() {
        assertThat(new RecipeRegistry(dir.resolve("nope"), List.of()).recipes()).isEmpty();
    }

    @Test
    void shippedLuxoftRecipeLoadsDisabled() {
        var reg = new RecipeRegistry(Path.of("../../recipes"), List.of("remotive"));
        assertThat(reg.recipes()).hasSize(1);
        assertThat(reg.recipes().get(0).name()).isEqualTo("luxoft");
        assertThat(reg.recipes().get(0).isEnabled()).isFalse();
    }
}
