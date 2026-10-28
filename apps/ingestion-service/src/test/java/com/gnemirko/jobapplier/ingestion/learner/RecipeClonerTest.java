package com.gnemirko.jobapplier.ingestion.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.learner.config.CloneProperties;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.dto.CloneStarted;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnBusyException;
import com.gnemirko.jobapplier.ingestion.learner.exception.LearnRejectedException;
import com.gnemirko.jobapplier.ingestion.learner.service.ProposalWriter;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeCloner;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeLearner;
import com.gnemirko.jobapplier.ingestion.learner.source.LlmClient;
import com.gnemirko.jobapplier.ingestion.learner.source.SampleRunner;
import com.gnemirko.jobapplier.ingestion.service.LearnerTestRig;
import com.gnemirko.jobapplier.ingestion.service.RecipeRegistry;
import com.gnemirko.jobapplier.ingestion.service.RecipeValidator;

/** The clone flow against a local HTTPS stub site with a sitemap index and locale sitemaps. No model, no network. */
class RecipeClonerTest {

    @TempDir Path tmp;
    StubSite site;
    StubScoring scoring;
    LearnerTestRig rig;
    Path out;
    Path recipesDir;
    RecipeLearner learner;

    @BeforeEach
    void setUp() throws Exception {
        site = new StubSite();
        scoring = new StubScoring();
        rig = new LearnerTestRig("127.0.0.1");
        out = tmp.resolve("proposed");
        recipesDir = tmp.resolve("recipes");
        Files.createDirectories(recipesDir);
        Files.writeString(recipesDir.resolve("acme-ie-en.json"), template());
        site.ok("/robots.txt", "User-agent: *\nAllow: /\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        site.close();
        scoring.close();
    }

    private String template() {
        return "{\"name\":\"acme-ie-en\",\"enabled\":true,\"strategy\":\"sitemap\",\"sitemap\":{\"url\":\""
                + site.base() + "/ie-en/jobpostings-sitemap.xml\",\"urlPattern\":\"^https://127\\\\.0\\\\.0\\\\.1:"
                + site.port + "/ie-en/careers/jobdetails\\\\?id=.*\",\"maxUrls\":300,\"keepQuery\":true},"
                + "\"detail\":{\"extractor\":\"json-ld\",\"idFrom\":\"urlRegex:[?&]id=([^&]+)\"},"
                + "\"limits\":{\"maxDetailFetchesPerRun\":40}}";
    }

    private static String urlset(List<String> locs) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        locs.forEach(l -> sb.append("<url><loc>").append(l.replace("&", "&amp;")).append("</loc></url>"));
        return sb.append("</urlset>").toString();
    }

    private static String sitemapIndex(List<String> locs) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        locs.forEach(l -> sb.append("<sitemap><loc>").append(l).append("</loc></sitemap>"));
        return sb.append("</sitemapindex>").toString();
    }

    /** A locale sitemap with n jobs (ids {@code <prefix><i>}), each with a JSON-LD page. */
    private void locale(String loc, String idPrefix, int n) {
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String id = idPrefix + i;
            urls.add(site.base() + "/" + loc + "/careers/jobdetails?id=" + id);
            site.ok("/" + loc + "/careers/jobdetails?id=" + id,
                    "<html><head><script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"Engineer "
                            + id + "\",\"datePosted\":\"2026-10-01\",\"description\":\"d\","
                            + "\"hiringOrganization\":{\"name\":\"Acme\"},\"jobLocation\":{\"address\":"
                            + "{\"addressLocality\":\"X\",\"addressCountry\":\"UA\"}}}</script></head></html>");
        }
        site.ok("/" + loc + "/jobpostings-sitemap.xml", urlset(urls));
    }

    private String entry(String loc) {
        return site.base() + "/" + loc + "/jobpostings-sitemap.xml";
    }

    private void index(String... locs) {
        site.ok("/sitemap-index.xml", sitemapIndex(List.of(locs)));
    }

    private RecipeCloner cloner(Clock clock, CloneProperties cp, String... allowed) {
        LearnerProperties props = new LearnerProperties(true, List.of(allowed), 12000, scoring.baseUrl(),
                writeToken(), out.toString(), Duration.ofSeconds(5), Duration.ofSeconds(5), 65536, Duration.ofMinutes(2));
        RecipeRegistry registry = new RecipeRegistry(recipesDir, List.of());
        ProposalWriter writer = new ProposalWriter(out, recipesDir);
        RecipeValidator validator = new RecipeValidator(registry.recipes().stream().map(Recipe::name).toList());
        SampleRunner sampler = new SampleRunner(rig.fetcher, clock, props);
        learner = new RecipeLearner(props, rig.fetcher, rig.policy, new LlmClient(props), validator, sampler, writer,
                Runnable::run);
        return new RecipeCloner(props, cp, registry, validator, rig.fetcher, rig.policy, sampler, writer, learner,
                clock, Runnable::run);
    }

    private String writeToken() {
        try {
            Path f = tmp.resolve("token");
            Files.writeString(f, "0123456789abcdef-test-secret\n");
            return f.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private RecipeCloner cloner() {
        return cloner(Clock.systemUTC(), new CloneProperties(Duration.ofMinutes(30), 80), "127.0.0.1");
    }

    private CloneStarted run(RecipeCloner c) {
        return c.start("acme-ie-en", site.base() + "/sitemap-index.xml");
    }

    private String report(CloneStarted s) throws Exception {
        return Files.readString(out.resolve(s.report() + ".report.txt"));
    }

    private List<String> outFiles() throws Exception {
        if (!Files.exists(out)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(out)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private void assertCountsAddUp(String report, int entries) {
        int covered = count(report, "COVERED: ");
        int failed = count(report, "FAILED: ");
        int skipped = count(report, "SKIPPED: ");
        assertThat(count(report, "index entries: ")).isEqualTo(entries);
        assertThat(covered + failed + skipped).isEqualTo(entries);
        assertThat(report).doesNotContain("PENDING: ");
    }

    private static int count(String report, String label) {
        for (String line : report.split("\n")) {
            if (line.startsWith(label)) {
                return Integer.parseInt(line.substring(label.length()).strip());
            }
        }
        throw new AssertionError("no line " + label);
    }

    @Test
    void goodRunCoversSiblingsAndExplainsEveryOtherEntry() throws Exception {
        locale("ie-en", "ie", 3);
        locale("de-de", "ie", 1);          // id ie1 is shared with the template locale
        site.routes.remove("/de-de/careers/jobdetails?id=ie1");
        locale("de-de", "d", 4);
        locale("fr-fr", "f", 4);
        index(entry("ie-en"), entry("de-de"), entry("fr-fr"), entry("de-de"),
                site.base() + "/jobpostings-sitemap.xml",
                site.base() + "/pl-pl/other-sitemap.xml",
                site.base() + "/pl-pl/jobpostings-sitemap.xml?x=1",
                site.base() + "/ZZ-ZZ/jobpostings-sitemap.xml",
                site.base() + "/sitemap-index.xml",
                "https://other.example.com/xx-yy/jobpostings-sitemap.xml");

        CloneStarted s = run(cloner());

        assertThat(outFiles()).containsExactlyInAnyOrder("acme-ie-en-clone.report.txt",
                "acme-de-de.json", "acme-fr-fr.json").doesNotContain("acme-ie-en.json");
        String report = report(s);
        assertCountsAddUp(report, 10);
        assertThat(report).contains("COVERED: 2", "SKIPPED: 8", "FAILED: 0")
                .contains("COVERED " + entry("de-de") + " : acme-de-de.json", "COVERED " + entry("fr-fr"))
                .contains("duplicate entry", "not a sibling: no locale segment", "different file or query",
                        "unsupported locale", "other host", "template locale", "nested index")
                .contains("matched=4 good=4");
        // Clones load through the real registry, disabled, with the template's limits and keepQuery.
        RecipeRegistry loaded = new RecipeRegistry(out, List.of());
        assertThat(loaded.recipes()).extracting(Recipe::name).containsExactly("acme-de-de", "acme-fr-fr");
        assertThat(loaded.recipes()).allSatisfy(r -> {
            assertThat(r.enabled()).isFalse();
            assertThat(r.limits().maxDetailFetchesPerRun()).isEqualTo(40);
            assertThat(r.sitemap().keepQuery()).isTrue();
            assertThat(r.sitemap().maxUrls()).isEqualTo(300);
        });
        Recipe de = loaded.recipes().get(0);
        assertThat(de.sitemap().url()).isEqualTo(site.base() + "/de-de/jobpostings-sitemap.xml");
        assertThat(de.sitemap().urlPattern()).contains("/de-de/careers").doesNotContain("ie-en");
        // Nothing was written into the loaded recipes folder, and the foreign host got no request.
        try (Stream<Path> files = Files.list(recipesDir)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("acme-ie-en.json");
        }
        assertThat(scoring.requests).isEmpty();
        assertThat(site.hitsOn("/pl-pl/")).isZero();
        assertThat(site.hitsOn("/ZZ-ZZ/")).isZero();
    }

    @Test
    void sharedSampleIdsWithTheTemplateAreCounted() throws Exception {
        locale("ie-en", "x", 3);
        locale("de-de", "x", 3);
        index(entry("ie-en"), entry("de-de"));
        CloneStarted s = run(cloner());
        assertThat(report(s)).contains("COVERED").contains("sample-ids-shared-with-template=3");
    }

    @Test
    void failingLocalesAreReportedAndTheRunContinues() throws Exception {
        locale("ie-en", "ie", 3);
        locale("nl-nl", "n", 3);
        site.ok("/de-de/jobpostings-sitemap.xml", urlset(List.of()));                      // empty
        site.ok("/fr-fr/jobpostings-sitemap.xml",
                urlset(List.of(site.base() + "/fr-fr/careers/jobdetails?id=1",
                        site.base() + "/fr-fr/careers/jobdetails?id=2",
                        site.base() + "/fr-fr/careers/jobdetails?id=3")));                  // pages have no JSON-LD
        for (int i = 1; i <= 3; i++) {
            site.ok("/fr-fr/careers/jobdetails?id=" + i, "<html><body>nothing</body></html>");
        }
        // es-es sitemap does not exist: 404
        index(entry("ie-en"), entry("de-de"), entry("fr-fr"), entry("es-es"), entry("nl-nl"));

        CloneStarted s = run(cloner());

        String report = report(s);
        assertCountsAddUp(report, 5);
        assertThat(report).contains("COVERED: 1", "FAILED: 3", "SKIPPED: 1")
                .contains("FAILED " + entry("de-de") + " : the sitemap lists no job URLs")
                .contains("FAILED " + entry("fr-fr") + " : sample found too few usable postings, need 3")
                .contains("FAILED " + entry("es-es"))
                .contains("COVERED " + entry("nl-nl"));
        assertThat(outFiles()).containsExactlyInAnyOrder("acme-ie-en-clone.report.txt", "acme-nl-nl.json");
    }

    @Test
    void aSmallLocaleWithFewerThanThreeJobsStillPasses() throws Exception {
        locale("ie-en", "ie", 3);
        locale("lu-fr", "l", 2);
        index(entry("ie-en"), entry("lu-fr"));
        CloneStarted s = run(cloner());
        assertThat(report(s)).contains("COVERED " + entry("lu-fr")).contains("matched=2 good=2");
        assertThat(outFiles()).contains("acme-lu-fr.json");
    }

    @Test
    void templateUnusableIsRejectedBeforeAnyFetch() throws Exception {
        Files.writeString(recipesDir.resolve("lst.json"), "{\"name\":\"lst-ie-en\",\"strategy\":\"paginated-listing\","
                + "\"listing\":{\"urlTemplate\":\"" + site.base() + "/c?p={page}\",\"maxPages\":2,"
                + "\"linkSelector\":\"a[href]\",\"linkPattern\":\"/j/[0-9]+\"},"
                + "\"detail\":{\"extractor\":\"json-ld\",\"idFrom\":\"jsonld\"}}");
        Files.writeString(recipesDir.resolve("flat.json"), template().replace("acme-ie-en", "flat")
                .replace("/ie-en/jobpostings-sitemap.xml", "/jobpostings-sitemap.xml"));
        String idx = site.base() + "/sitemap-index.xml";
        // Built after the extra recipe files were written, so the registry sees them.
        RecipeCloner cc = cloner();
        assertThatThrownBy(() -> cc.start("unknown", idx)).isInstanceOf(LearnRejectedException.class);
        assertThatThrownBy(() -> cc.start("lst-ie-en", idx)).isInstanceOf(LearnRejectedException.class)
                .hasMessageContaining("not a sitemap recipe");
        assertThatThrownBy(() -> cc.start("flat", idx)).isInstanceOf(LearnRejectedException.class)
                .hasMessageContaining("not locale-specific");
        assertThatThrownBy(() -> cc.start("Bad Name!", idx)).isInstanceOf(LearnRejectedException.class);
        assertThatThrownBy(() -> cc.start(null, idx)).isInstanceOf(LearnRejectedException.class);
        assertThat(site.hits).isEmpty();
        assertThat(learner.isBusy()).isFalse();
    }

    @Test
    void templateHostMustBeAllowListed() {
        RecipeCloner c = cloner(Clock.systemUTC(), new CloneProperties(Duration.ofMinutes(30), 80), "other.example.com");
        assertThatThrownBy(() -> run(c)).isInstanceOf(LearnRejectedException.class).hasMessageContaining("allowed-hosts");
        assertThat(site.hits).isEmpty();
    }

    @Test
    void foreignOrUnsafeIndexUrlIsRejected() {
        RecipeCloner c = cloner();
        assertThatThrownBy(() -> c.start("acme-ie-en", "https://other.example.com/sitemap-index.xml"))
                .isInstanceOf(LearnRejectedException.class).hasMessageContaining("template's host");
        assertThatThrownBy(() -> c.start("acme-ie-en", "http://127.0.0.1:" + site.port + "/sitemap-index.xml"))
                .isInstanceOf(LearnRejectedException.class).hasMessageContaining("https");
        assertThatThrownBy(() -> c.start("acme-ie-en", null)).isInstanceOf(LearnRejectedException.class);
        assertThatThrownBy(() -> c.start("acme-ie-en", "x".repeat(3000))).isInstanceOf(LearnRejectedException.class);
        assertThat(site.hits).isEmpty();
        assertThat(learner.isBusy()).isFalse();
    }

    @Test
    void robotsBlockedLocaleFailsAndTheRunContinues() throws Exception {
        site.ok("/robots.txt", "User-agent: *\nAllow: /\nDisallow: /nl-nl/\n");
        locale("ie-en", "ie", 3);
        locale("nl-nl", "n", 3);
        locale("de-de", "d", 3);
        index(entry("ie-en"), entry("nl-nl"), entry("de-de"));
        CloneStarted s = run(cloner());
        String report = report(s);
        assertThat(report).contains("FAILED " + entry("nl-nl") + " : blocked").contains("COVERED " + entry("de-de"));
        assertThat(outFiles()).doesNotContain("acme-nl-nl.json").contains("acme-de-de.json");
        assertThat(site.hitsOn("/nl-nl/")).isZero();
    }

    @Test
    void blockedMidRunStopsTheRestButStillWritesTheReport() throws Exception {
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 3);
        site.routes.put("/fr-fr/jobpostings-sitemap.xml", new StubSite.Route(403, "denied"));
        locale("nl-nl", "n", 3);
        index(entry("ie-en"), entry("de-de"), entry("fr-fr"), entry("nl-nl"));
        CloneStarted s = run(cloner());
        String report = report(s);
        assertCountsAddUp(report, 4);
        assertThat(report).contains("COVERED " + entry("de-de"), "FAILED " + entry("fr-fr") + " : blocked",
                "SKIPPED " + entry("nl-nl") + " : run stopped: host blocked", "stopped: host blocked");
        assertThat(site.hitsOn("/nl-nl/")).isZero();
    }

    @Test
    void existingOutputIsSkippedAndNeverOverwritten() throws Exception {
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 3);
        locale("fr-fr", "f", 3);
        index(entry("ie-en"), entry("de-de"), entry("fr-fr"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("acme-de-de.json"), "{\"keep\":\"me\"}");
        CloneStarted s = run(cloner());
        assertThat(Files.readString(out.resolve("acme-de-de.json"))).isEqualTo("{\"keep\":\"me\"}");
        assertThat(report(s)).contains("SKIPPED " + entry("de-de") + " : already proposed")
                .contains("COVERED " + entry("fr-fr"));
        assertThat(site.hitsOn("/de-de/")).isZero();
        // a second run gets its own report name instead of overwriting the first
        RecipeCloner again = cloner();
        CloneStarted s2 = run(again);
        assertThat(s2.report()).isEqualTo("acme-ie-en-clone-2");
        assertThat(report(s2)).contains("SKIPPED " + entry("fr-fr") + " : already proposed");
    }

    @Test
    void aRunningLearnRunMakesCloneBusyAndTheSlotIsFreedAfterwards() throws Exception {
        locale("ie-en", "ie", 3);
        index(entry("ie-en"));
        RecipeCloner c = cloner();
        assertThat(learner.tryAcquireSlot()).isTrue();
        assertThatThrownBy(() -> run(c)).isInstanceOf(LearnBusyException.class);
        assertThat(site.hits).isEmpty();
        learner.releaseSlot();
        run(c);
        assertThat(learner.isBusy()).isFalse();
    }

    @Test
    void unreachableOrWrongIndexIsReported() throws Exception {
        // 404 index
        CloneStarted s = run(cloner());
        assertThat(report(s)).contains("outcome: index unreadable").contains("index entries: 0");
        // a plain urlset is not an index
        site.ok("/sitemap-index.xml", urlset(List.of(entry("de-de"))));
        CloneStarted s2 = run(cloner());
        assertThat(report(s2)).contains("outcome: index unreadable").contains("Not a sitemap index");
        assertThat(outFiles()).doesNotContain("acme-de-de.json");
    }

    @Test
    void templateMissingFromTheIndexIsAWarning() throws Exception {
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 3);
        index(entry("de-de"));
        CloneStarted s = run(cloner());
        assertThat(report(s)).contains("warning: the template's own sitemap is not listed").contains("COVERED");
    }

    @Test
    void capLimitsHowManyLocalesAreTried() throws Exception {
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 3);
        locale("fr-fr", "f", 3);
        index(entry("ie-en"), entry("de-de"), entry("fr-fr"));
        CloneStarted s = run(cloner(Clock.systemUTC(), new CloneProperties(Duration.ofMinutes(30), 1), "127.0.0.1"));
        String report = report(s);
        assertCountsAddUp(report, 3);
        assertThat(report).contains("COVERED " + entry("de-de"), "SKIPPED " + entry("fr-fr") + " : cap");
        assertThat(site.hitsOn("/fr-fr/")).isZero();
        assertThatThrownBy(() -> new CloneProperties(Duration.ofMinutes(1), 121)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeBudgetSkipsTheRest() throws Exception {
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 3);
        index(entry("ie-en"), entry("de-de"));
        CloneStarted s = run(cloner(Clock.systemUTC(), new CloneProperties(Duration.ZERO, 80), "127.0.0.1"));
        assertThat(report(s)).contains("SKIPPED " + entry("de-de") + " : time budget");
        assertThat(outFiles()).containsExactly("acme-ie-en-clone.report.txt");
    }

    @Test
    void aLoadedRecipeWithTheCloneNameIsNotCloned() throws Exception {
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 3);
        Files.writeString(recipesDir.resolve("de.json"), template().replace("acme-ie-en", "acme-de-de"));
        index(entry("ie-en"), entry("de-de"));
        CloneStarted s = run(cloner());
        assertThat(report(s)).contains("SKIPPED " + entry("de-de") + " : a recipe named acme-de-de is already loaded");
        assertThat(site.hitsOn("/de-de/")).isZero();
    }

    @Test
    void disabledTemplateCanBeCloned() throws Exception {
        Files.writeString(recipesDir.resolve("acme-ie-en.json"), template().replace("\"enabled\":true", "\"enabled\":false"));
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 3);
        index(entry("ie-en"), entry("de-de"));
        CloneStarted s = run(cloner());
        assertThat(report(s)).contains("COVERED " + entry("de-de"));
    }

    @Test
    void lowDetailLimitStillPasses() throws Exception {
        Files.writeString(recipesDir.resolve("acme-ie-en.json"), template().replace("\"maxDetailFetchesPerRun\":40", "\"maxDetailFetchesPerRun\":2"));
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 5);
        index(entry("ie-en"), entry("de-de"));
        CloneStarted s = run(cloner());
        assertThat(report(s)).contains("COVERED " + entry("de-de")).contains("good=2");
    }

    @Test
    void interruptIsReportedAndTheReportStillWritten() throws Exception {
        locale("ie-en", "ie", 3);
        locale("de-de", "d", 3);
        index(entry("ie-en"), entry("de-de"));
        RecipeCloner c = cloner();
        Thread.currentThread().interrupt();
        CloneStarted s;
        try {
            s = run(c);
        } finally {
            Thread.interrupted();
        }
        assertThat(report(s)).contains("outcome: interrupted").doesNotContain("PENDING: ");
        assertThat(outFiles()).doesNotContain("acme-de-de.json");
    }

    @Test
    void atMostTwoRunsADay() throws Exception {
        locale("ie-en", "ie", 3);
        index(entry("ie-en"));
        RecipeCloner c = cloner();
        run(c);
        run(c);
        assertThatThrownBy(() -> run(c)).isInstanceOf(LearnRejectedException.class).hasMessageContaining("a day");
        assertThat(learner.isBusy()).isFalse();
    }
}
