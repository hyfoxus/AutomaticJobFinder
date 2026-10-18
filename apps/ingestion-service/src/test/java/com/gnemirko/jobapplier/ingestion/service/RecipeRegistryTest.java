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

    private static String workday(String name, String block, String extra) {
        return "{\"name\":\"" + name + "\",\"enabled\":false,\"strategy\":\"workday\",\"workday\":" + block + extra + "}";
    }

    private static final String WD = """
            {"host":"acme.wd1.myworkdayjobs.com","tenant":"acme","site":"Careers","searchText":"java","company":"Acme"}""";

    @Test
    void workdayRecipeLoadsWithDefaults() throws Exception {
        write("a.json", workday("alpha", WD, ""));
        var r = new RecipeRegistry(dir, List.of()).recipes().get(0);
        assertThat(r.workday().maxPages()).isEqualTo(50);
        assertThat(r.workday().company()).isEqualTo("Acme");
        assertThat(r.detail()).isNull();
        assertThat(r.limits().maxDetailFetchesPerRun()).isEqualTo(200);
    }

    @Test
    void badWorkdayRecipesAreSkipped() throws Exception {
        write("a-good.json", workday("alpha", WD, ""));
        write("b-no-block.json", "{\"name\":\"bravo\",\"strategy\":\"workday\"}");
        write("c-host.json", workday("charlie", WD.replace("acme.wd1.myworkdayjobs.com", "acme.example.com"), ""));
        write("d-host-suffix.json", workday("delta", WD.replace("acme.wd1.myworkdayjobs.com", "evilmyworkdayjobs.com"), ""));
        write("e-host-path.json", workday("echo", WD.replace("acme.wd1.myworkdayjobs.com", "a.myworkdayjobs.com/x"), ""));
        write("f-tenant.json", workday("foxtrot", WD.replace("\"acme\"", "\"a/b\""), ""));
        write("g-site.json", workday("golf", WD.replace("Careers", "Car eers"), ""));
        write("h-no-company.json", workday("hotel", WD.replace(",\"company\":\"Acme\"", ""), ""));
        write("i-blank-company.json", workday("india", WD.replace("\"Acme\"", "\" \""), ""));
        write("j-long-search.json", workday("juliet", WD.replace("java", "j".repeat(101)), ""));
        write("k-ctrl-search.json", workday("kilo", WD.replace("java", "ja\\u0007va"), ""));
        write("l-no-search.json", workday("lima", WD.replace("\"searchText\":\"java\",", ""), ""));
        write("m-pages-0.json", workday("mike", WD.replace("}", ",\"maxPages\":0}"), ""));
        write("n-pages-201.json", workday("november", WD.replace("}", ",\"maxPages\":201}"), ""));
        write("o-detail.json", workday("oscar", WD, ",\"detail\":{\"extractor\":\"json-ld\",\"idFrom\":\"jsonld\"}"));
        write("p-sitemap.json", workday("papa", WD, SITEMAP));
        write("q-on-listing.json", good("quebec").replace("\"detail\"", "\"workday\":" + WD + ",\"detail\""));
        write("r-on-sitemap.json", sitemapRecipe("romeo", SITEMAP, EMBEDDED, "").replace("\"detail\"", "\"workday\":" + WD + ",\"detail\""));
        write("s-unknown-field.json", workday("sierra", WD.replace("}", ",\"mapping\":{}}"), ""));
        write("t-cap.json", workday("tango", WD, ",\"limits\":{\"maxDetailFetchesPerRun\":5000}"));
        write("u-bad-strategy.json", workday("uniform", WD, "").replace("\"workday\",", "\"nope\","));
        write("v-long-label.json", workday("victor", WD.replace("acme.wd1", "a".repeat(64) + ".wd1"), ""));
        write("w-label-63.json", workday("whiskey", WD.replace("acme.wd1", "a".repeat(63) + ".wd1"), ""));
        write("z-ok.json", workday("zulu", WD.replace("}", ",\"maxPages\":200}"), ""));
        assertThat(names()).containsExactly("alpha", "whiskey", "zulu");
    }

    @Test
    void shippedDxcRecipeLoadsDisabled() {
        var reg = new RecipeRegistry(Path.of("../../recipes"), List.of("remotive"));
        var dxc = reg.recipes().stream().filter(r -> r.name().equals("dxc")).findFirst().orElseThrow();
        assertThat(dxc.isEnabled()).isFalse();
        assertThat(dxc.strategy()).isEqualTo("workday");
        assertThat(dxc.workday().host()).isEqualTo("dxctechnology.wd1.myworkdayjobs.com");
        assertThat(dxc.workday().site()).isEqualTo("DXCJobs");
        assertThat(dxc.workday().company()).isEqualTo("DXC Technology");
        assertThat(dxc.limits().maxDetailFetchesPerRun()).isEqualTo(150);
    }
}
