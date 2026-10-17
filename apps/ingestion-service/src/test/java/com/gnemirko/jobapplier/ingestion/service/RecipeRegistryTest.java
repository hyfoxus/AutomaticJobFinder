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

    private static String sitemapRecipe(String name, String sitemapBlock, String detailBlock, String extra) {
        return """
                {"name":"%s","enabled":true,"strategy":"sitemap"%s,
                 "detail":%s}""".formatted(name, sitemapBlock, detailBlock).replace("}\"}", "}") + extra;
    }

    private static final String SITEMAP = ",\"sitemap\":{\"url\":\"https://a.example/sitemap.xml.gz\",\"urlPattern\":\"/vacancy/\"}";
    private static final String EMBEDDED = """
            {"extractor":"embedded-json","embeddedJson":{"selector":"script#d",
             "fields":{"id":"j.id","title":"j.name","location":{"join":["j.city","j.country[*]"]}},
             "constants":{"company":"Acme"},"expiredPath":"j.expired"}}""";

    @Test
    void sitemapRecipeLoadsWithDefaults() throws Exception {
        write("a.json", sitemapRecipe("alpha", SITEMAP, EMBEDDED, ""));
        var r = new RecipeRegistry(dir, List.of()).recipes().get(0);
        assertThat(r.sitemap().maxUrls()).isEqualTo(2000);
        assertThat(r.listing()).isNull();
        assertThat(r.detail().embeddedJson().selector()).isEqualTo("script#d");
        assertThat(r.detail().embeddedJson().constants()).containsEntry("company", "Acme");
    }

    @Test
    void badSitemapRecipesAreSkipped() throws Exception {
        String listing = ",\"listing\":{\"urlTemplate\":\"https://a.example/{page}\",\"maxPages\":2,\"linkSelector\":\"a\",\"linkPattern\":\"x\"}";
        write("a-good.json", sitemapRecipe("alpha", SITEMAP, EMBEDDED, ""));
        write("b-no-block.json", sitemapRecipe("bravo", "", EMBEDDED, ""));
        write("c-both-blocks.json", sitemapRecipe("charlie", SITEMAP + listing, EMBEDDED, ""));
        write("d-http.json", sitemapRecipe("delta", SITEMAP.replace("https", "http"), EMBEDDED, ""));
        write("e-no-pattern.json", sitemapRecipe("echo", SITEMAP.replace(",\"urlPattern\":\"/vacancy/\"", ""), EMBEDDED, ""));
        write("f-bad-pattern.json", sitemapRecipe("foxtrot", SITEMAP.replace("/vacancy/", "(unclosed"), EMBEDDED, ""));
        write("g-max-urls.json", sitemapRecipe("golf", SITEMAP.replace("}", ",\"maxUrls\":5001}"), EMBEDDED, ""));
        write("h-bad-path.json", sitemapRecipe("hotel", SITEMAP, EMBEDDED.replace("j.name", "j..name"), ""));
        write("i-bad-selector.json", sitemapRecipe("india", SITEMAP, EMBEDDED.replace("script#d", "a[["), ""));
        write("j-bad-expired.json", sitemapRecipe("juliet", SITEMAP, EMBEDDED.replace("j.expired", "j[x]"), ""));
        write("k-no-title.json", sitemapRecipe("kilo", SITEMAP, EMBEDDED.replace("\"title\":\"j.name\",", ""), ""));
        write("l-no-block.json", sitemapRecipe("lima", SITEMAP, "{\"extractor\":\"embedded-json\"}", ""));
        write("m-idfrom.json", sitemapRecipe("mike", SITEMAP, EMBEDDED.replace("\"embeddedJson\"", "\"idFrom\":\"jsonld\",\"embeddedJson\""), ""));
        write("n-wrong-block.json", sitemapRecipe("november", SITEMAP,
                "{\"extractor\":\"json-ld\",\"idFrom\":\"jsonld\",\"embeddedJson\":" + EMBEDDED.substring(EMBEDDED.indexOf("\"embeddedJson\":") + 15), ""));
        write("o-updated-at.json", sitemapRecipe("oscar", SITEMAP, EMBEDDED.replace("\"id\":\"j.id\"", "\"updatedAt\":\"j.u\",\"id\":\"j.id\""), ""));
        write("p-paginated-with-sitemap.json", good("papa").replace("\"detail\"", SITEMAP.substring(1) + ",\"detail\""));
        write("z-ok.json", sitemapRecipe("zulu", SITEMAP, EMBEDDED, ""));
        assertThat(names()).containsExactly("alpha", "zulu");
    }

    @Test
    void missingDirectoryIsFine() {
        assertThat(new RecipeRegistry(dir.resolve("nope"), List.of()).recipes()).isEmpty();
    }

    @Test
    void shippedLuxoftRecipeLoadsDisabled() {
        var reg = new RecipeRegistry(Path.of("../../recipes"), List.of("remotive"));
        var luxoft = reg.recipes().stream().filter(r -> r.name().equals("luxoft")).findFirst().orElseThrow();
        assertThat(luxoft.isEnabled()).isFalse();
    }

    @Test
    void shippedEpamRecipeLoadsDisabled() {
        var reg = new RecipeRegistry(Path.of("../../recipes"), List.of("remotive"));
        var epam = reg.recipes().stream().filter(r -> r.name().equals("epam")).findFirst().orElseThrow();
        assertThat(epam.isEnabled()).isFalse();
        assertThat(epam.strategy()).isEqualTo("sitemap");
        assertThat(epam.sitemap().maxUrls()).isEqualTo(600);
        assertThat(epam.limits().maxDetailFetchesPerRun()).isEqualTo(300);
    }
}
