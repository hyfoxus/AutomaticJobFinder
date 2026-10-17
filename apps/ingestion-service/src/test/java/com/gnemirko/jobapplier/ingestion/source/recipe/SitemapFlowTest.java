package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.TestcontainersConfiguration;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity.Status;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;
import com.gnemirko.jobapplier.ingestion.repository.IngestionRunRepository;
import com.gnemirko.jobapplier.ingestion.repository.JobRepository;
import com.gnemirko.jobapplier.ingestion.service.IngestionService;
import com.gnemirko.jobapplier.ingestion.service.JobQueryService;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real Postgres, real polite fetcher, local stub site with a sitemap and embedded-JSON pages. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "ingestion.fetch.allow-insecure-hosts=127.0.0.1,localhost",
        "ingestion.fetch.min-delay=0ms",
        "ingestion.recipes.dir=./does-not-exist"})
class SitemapFlowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Map<String, Reply> ROUTES = new ConcurrentHashMap<>();
    private static final List<String> HITS = new CopyOnWriteArrayList<>();
    private static volatile Runnable onHit = () -> { };
    private static final HttpServer SERVER = start();

    record Reply(int status, byte[] body, String contentType) {
        Reply(int status, String body) {
            this(status, body.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
        }
    }

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                HITS.add(path);
                if (path.startsWith("/en/")) {
                    onHit.run();
                }
                Reply r = ROUTES.getOrDefault(path, new Reply(404, "nope"));
                ex.getResponseHeaders().add("Content-Type", r.contentType());
                ex.sendResponseHeaders(r.status(), r.body().length == 0 ? -1 : r.body().length);
                if (r.body().length > 0) {
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(r.body());
                    }
                }
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired IngestionService runner;
    @Autowired PoliteFetcher fetcher;
    @Autowired JobRepository jobs;
    @Autowired IngestionRunRepository runs;
    @Autowired JobQueryService queries;

    private static String base() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    private static String sitemap(String... entries) {
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\"?><urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (String e : entries) {
            sb.append(e);
        }
        return sb.append("</urlset>").toString();
    }

    private static String entry(String path, String lastmod) {
        return "<url><loc>" + base() + path + "</loc>" + (lastmod == null ? "" : "<lastmod>" + lastmod + "</lastmod>")
                + "</url>";
    }

    private static String page(String id, String title, boolean expired) {
        return "<html><head><script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"job\":"
                + "{\"unique_id\":\"" + id + "\",\"name\":\"" + title + "\",\"city\":\"Lviv\",\"country\":[\"UA\"],"
                + "\"vacancy_type\":\"Hybrid\",\"job_specialization\":[\"Engineering\"],\"description\":\"<p>d</p>\","
                + "\"created_at\":\"2026-09-01T10:00:00Z\",\"is_expired\":" + expired + "}}}}</script></head></html>";
    }

    private void ok(String path, String body) {
        ROUTES.put(path, new Reply(200, body));
    }

    private void sitemapOk(String... entries) {
        ROUTES.put("/sitemap.xml", new Reply(200, sitemap(entries).getBytes(StandardCharsets.UTF_8), "application/xml"));
    }

    @BeforeEach
    void reset() {
        jobs.deleteAll();
        runs.deleteAll();
        ROUTES.clear();
        HITS.clear();
        onHit = () -> { };
        ok("/robots.txt", "User-agent: *\nDisallow: /en/vacancy/java-secret\nDisallow: /private\n");
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-01T10:00:00Z"),
                entry("/en/vacancy/java-b?utm=x", "2026-10-02T10:00:00Z"),
                entry("/en/vacancy/python-c", "2026-10-03T10:00:00Z"),
                entry("/careers/java-d", "2026-10-04T10:00:00Z"));
        ok("/en/vacancy/java-a", page("A1", "Job A", false));
        ok("/en/vacancy/java-b", page("B2", "Job B", false));
        ok("/en/vacancy/python-c", page("C3", "Job C", false));
    }

    private Recipe recipe(int cap) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        fields.put("id", MAPPER.valueToTree("props.pageProps.job.unique_id"));
        fields.put("title", MAPPER.valueToTree("props.pageProps.job.name"));
        fields.put("location", MAPPER.readTree(
                "{\"join\":[\"props.pageProps.job.city\",\"props.pageProps.job.country[*]\"],\"sep\":\", \"}"));
        fields.put("jobType", MAPPER.valueToTree("props.pageProps.job.vacancy_type"));
        fields.put("category", MAPPER.valueToTree("props.pageProps.job.job_specialization[*]"));
        fields.put("description", MAPPER.valueToTree("props.pageProps.job.description"));
        fields.put("postedAt", MAPPER.valueToTree("props.pageProps.job.created_at"));
        var embedded = new Recipe.EmbeddedJson("script#__NEXT_DATA__", fields, Map.of("company", "Stub Co"),
                "props.pageProps.job.is_expired");
        return new Recipe("sitemap-stub", true, Recipe.STRATEGY_SITEMAP, null,
                new Recipe.Sitemap(base() + "/sitemap.xml", "/en/vacancy/[a-z-]*java", 100),
                new Recipe.Detail(Recipe.EXTRACTOR_EMBEDDED_JSON, null, embedded), new Recipe.Limits(cap));
    }

    private SitemapRecipeJobSource source(int cap, Clock clock) {
        return new SitemapRecipeJobSource(recipe(cap), fetcher, new SitemapReader(fetcher), clock, Duration.ofMinutes(20));
    }

    private SitemapRecipeJobSource source(int cap) {
        return source(cap, Clock.systemUTC());
    }

    private long detailHits() {
        return HITS.stream().filter(h -> h.startsWith("/en/")).count();
    }

    private Instant marker(String id) {
        return jobs.findBySourceAndExternalId("sitemap-stub", id).orElseThrow().getSourceUpdatedAt();
    }

    @Test
    void happyPathStoresMatchingPagesWithMappedFieldsAndLastmod() {
        IngestionRunEntity r = runner.runSource(source(100));

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(2);
        assertThat(jobs.count()).isEqualTo(2);
        JobPosting b = queries.since(Instant.EPOCH).stream().map(x -> x.posting())
                .filter(p -> p.externalId().equals("B2")).findFirst().orElseThrow();
        assertThat(b.url()).isEqualTo(base() + "/en/vacancy/java-b");
        assertThat(b.title()).isEqualTo("Job B");
        assertThat(b.company()).isEqualTo("Stub Co");
        assertThat(b.location()).isEqualTo("Lviv, UA");
        assertThat(b.jobType()).isEqualTo("Hybrid");
        assertThat(b.category()).isEqualTo("Engineering");
        assertThat(b.description()).isEqualTo("<p>d</p>");
        assertThat(b.postedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(b.updatedAt()).isEqualTo(Instant.parse("2026-10-02T10:00:00Z"));
        assertThat(HITS).doesNotContain("/en/vacancy/python-c", "/careers/java-d");
    }

    @Test
    void gzippedSitemapWorksToo() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(sitemap(entry("/en/vacancy/java-a", null)).getBytes(StandardCharsets.UTF_8));
        }
        ROUTES.put("/sitemap.xml", new Reply(200, out.toByteArray(), "application/octet-stream"));
        runner.runSource(source(100));
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void unchangedSecondRunFetchesNoPagesAndTouchesAllRows() {
        SitemapRecipeJobSource src = source(100);
        runner.runSource(src);
        JobEntity before = jobs.findBySourceAndExternalId("sitemap-stub", "B2").orElseThrow();
        long first = detailHits();

        IngestionRunEntity second = runner.runSource(src);

        assertThat(detailHits()).isEqualTo(first);
        assertThat(second.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(second.getInserted()).isZero();
        assertThat(second.getUpdated()).isEqualTo(2);
        JobEntity after = jobs.findBySourceAndExternalId("sitemap-stub", "B2").orElseThrow();
        assertThat(after.getLastSeenAt()).isAfter(before.getLastSeenAt());
        assertThat(after.getSourceUpdatedAt()).isEqualTo(before.getSourceUpdatedAt());
    }

    @Test
    void changedLastmodRefetchesOnlyThatPageAndUpdatesTheMarker() {
        SitemapRecipeJobSource src = source(100);
        runner.runSource(src);
        HITS.clear();
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-01T10:00:00Z"),
                entry("/en/vacancy/java-b", "2026-10-09T10:00:00Z"));
        ok("/en/vacancy/java-b", page("B2", "Job B renamed", false));

        IngestionRunEntity r = runner.runSource(src);

        assertThat(HITS.stream().filter(h -> h.startsWith("/en/")).toList()).containsExactly("/en/vacancy/java-b");
        assertThat(r.getInserted()).isZero();
        assertThat(r.getUpdated()).isEqualTo(2);
        assertThat(marker("B2")).isEqualTo(Instant.parse("2026-10-09T10:00:00Z"));
        assertThat(marker("A1")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(jobs.findBySourceAndExternalId("sitemap-stub", "B2").orElseThrow().toRecord().posting().title())
                .isEqualTo("Job B renamed");
    }

    @Test
    void lastmodGoingBackwardsAlsoTriggersARefetch() {
        SitemapRecipeJobSource src = source(100);
        runner.runSource(src);
        HITS.clear();
        sitemapOk(entry("/en/vacancy/java-a", "2026-09-01T10:00:00Z"), entry("/en/vacancy/java-b", "2026-10-02T10:00:00Z"));
        runner.runSource(src);
        assertThat(HITS.stream().filter(h -> h.startsWith("/en/")).toList()).containsExactly("/en/vacancy/java-a");
        assertThat(marker("A1")).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
    }

    @Test
    void missingLastmodFetchesOnlyWhenNotStoredAndKeepsTheMarker() {
        SitemapRecipeJobSource src = source(100);
        runner.runSource(src);
        HITS.clear();
        sitemapOk(entry("/en/vacancy/java-a", null), entry("/en/vacancy/java-b", "not a date"),
                entry("/en/vacancy/java-new", null));
        ok("/en/vacancy/java-new", page("N9", "Job N", false));
        runner.runSource(src);
        assertThat(HITS.stream().filter(h -> h.startsWith("/en/")).toList()).containsExactly("/en/vacancy/java-new");
        assertThat(marker("A1")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(marker("N9")).isNull();
        assertThat(jobs.count()).isEqualTo(3);
    }

    @Test
    void newUrlsAreReadBeforeChangedOnesAndChangedByLastmodDescending() {
        SitemapRecipeJobSource src = source(100);
        runner.runSource(src);
        HITS.clear();
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-05T10:00:00Z"), entry("/en/vacancy/java-b", "2026-10-07T10:00:00Z"),
                entry("/en/vacancy/java-new", "2026-10-01T10:00:00Z"));
        ok("/en/vacancy/java-new", page("N9", "Job N", false));
        runner.runSource(src);
        assertThat(HITS.stream().filter(h -> h.startsWith("/en/")).toList())
                .containsExactly("/en/vacancy/java-new", "/en/vacancy/java-b", "/en/vacancy/java-a");
    }

    @Test
    void capLeavesRemainingKnownRowsTouchedAndRetriesNextRun() {
        SitemapRecipeJobSource src = source(1);
        IngestionRunEntity first = runner.runSource(src);
        assertThat(first.getInserted()).isEqualTo(1);
        assertThat(first.getError()).contains("retried next run");

        IngestionRunEntity second = runner.runSource(src);
        assertThat(second.getInserted()).isEqualTo(1);
        assertThat(second.getUpdated()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    void failedChangedPageKeepsOldMarkerAndStaysAlive() {
        SitemapRecipeJobSource src = source(100);
        runner.runSource(src);
        JobEntity before = jobs.findBySourceAndExternalId("sitemap-stub", "A1").orElseThrow();
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-20T10:00:00Z"), entry("/en/vacancy/java-b", "2026-10-02T10:00:00Z"));
        ROUTES.put("/en/vacancy/java-a", new Reply(503, "down"));

        IngestionRunEntity r = runner.runSource(src);

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getError()).contains("failed");
        JobEntity after = jobs.findBySourceAndExternalId("sitemap-stub", "A1").orElseThrow();
        assertThat(after.getSourceUpdatedAt()).isEqualTo(before.getSourceUpdatedAt());
        assertThat(after.getLastSeenAt()).isAfter(before.getLastSeenAt());
    }

    @Test
    void expiredAndBadPagesAreSkippedNotStoredAndRetried() {
        ok("/en/vacancy/java-a", page("A1", "Job A", true));
        ok("/en/vacancy/java-b", "<html>no data here</html>");
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-01T10:00:00Z"), entry("/en/vacancy/java-b", "2026-10-02T10:00:00Z"),
                entry("/en/vacancy/java-c", "2026-10-02T10:00:00Z"), entry("/en/vacancy/java-d", "2026-10-02T10:00:00Z"));
        ok("/en/vacancy/java-c", "<script id=\"__NEXT_DATA__\">{broken</script>");
        ok("/en/vacancy/java-d", page("D4", "", false));
        SitemapRecipeJobSource src = source(100);

        IngestionRunEntity r = runner.runSource(src);

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(jobs.count()).isZero();
        assertThat(r.getSkipped()).isEqualTo(4);
        assertThat(r.getError()).contains("1 expired");
        runner.runSource(src);
        assertThat(HITS.stream().filter(h -> h.equals("/en/vacancy/java-a")).count()).isEqualTo(2);
    }

    @Test
    void robotsDisallowedPageIsNotRequestedAndKnownRowStaysAlive() {
        SitemapRecipeJobSource src = source(100);
        runner.runSource(src);
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-01T10:00:00Z"), entry("/en/vacancy/java-b", "2026-10-02T10:00:00Z"),
                entry("/en/vacancy/java-secret", null));
        ok("/en/vacancy/java-secret", page("S", "Secret", false));
        IngestionRunEntity r = runner.runSource(src);
        assertThat(HITS).doesNotContain("/en/vacancy/java-secret");
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getError()).contains("robots");
    }

    @Test
    void doctypeInTheSitemapFailsTheRunAndLeavesDataUntouched() {
        SitemapRecipeJobSource src = source(100);
        runner.runSource(src);
        JobEntity before = jobs.findBySourceAndExternalId("sitemap-stub", "A1").orElseThrow();
        ROUTES.put("/sitemap.xml", new Reply(200,
                ("<!DOCTYPE urlset [<!ENTITY x \"y\">]>" + sitemap(entry("/en/vacancy/java-a", "2027-01-01T00:00:00Z")))
                        .getBytes(StandardCharsets.UTF_8), "application/xml"));
        HITS.clear();

        IngestionRunEntity r = runner.runSource(src);

        assertThat(r.getStatus()).isEqualTo(Status.FAILED);
        assertThat(HITS).doesNotContain("/en/vacancy/java-a");
        JobEntity after = jobs.findBySourceAndExternalId("sitemap-stub", "A1").orElseThrow();
        assertThat(after.getLastSeenAt()).isEqualTo(before.getLastSeenAt());
        assertThat(after.getSourceUpdatedAt()).isEqualTo(before.getSourceUpdatedAt());
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    void missingSitemapFailsTheRun() {
        ROUTES.remove("/sitemap.xml");
        assertThat(runner.runSource(source(100)).getStatus()).isEqualTo(Status.FAILED);
    }

    @Test
    void timeBudgetKeepsCollectedJobsTouchesKnownRowsAndNotes() {
        runner.runSource(source(100));
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-11T10:00:00Z"), entry("/en/vacancy/java-b", "2026-10-12T10:00:00Z"),
                entry("/en/vacancy/java-x", "2026-10-01T10:00:00Z"), entry("/en/vacancy/java-y", "2026-10-01T10:00:00Z"));
        ok("/en/vacancy/java-x", page("X", "Job X", false));
        ok("/en/vacancy/java-y", page("Y", "Job Y", false));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-14T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        onHit = () -> now.updateAndGet(i -> i.plus(Duration.ofHours(1)));

        IngestionRunEntity r = runner.runSource(source(100, clock));

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getError()).contains("time budget");
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(3);
        assertThat(marker("A1")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    void urlCapIsNotedInTheRun() {
        Recipe base = recipe(100);
        Recipe capped = new Recipe(base.name(), true, base.strategy(), null,
                new Recipe.Sitemap(base.sitemap().url(), base.sitemap().urlPattern(), 1), base.detail(), base.limits());
        IngestionRunEntity r = runner.runSource(new SitemapRecipeJobSource(capped, fetcher, new SitemapReader(fetcher),
                Clock.systemUTC(), Duration.ofMinutes(20)));
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(r.getError()).contains("URL cap");
    }

    private void seed(String id, String path, String marker) {
        JobPosting p = new JobPosting(id, "sitemap-stub", "Old " + id, "Stub Co", null, base() + path, null, null, null,
                null, null, Instant.parse(marker));
        jobs.save(new JobEntity(p, Instant.parse("2026-09-01T00:00:00Z")));
    }

    private void assertKeptAlive(String id, String marker, long rows) {
        JobEntity after = jobs.findBySourceAndExternalId("sitemap-stub", id).orElseThrow();
        assertThat(after.getLastSeenAt()).isAfter(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(after.getSourceUpdatedAt()).isEqualTo(Instant.parse(marker));
        assertThat(after.toRecord().posting().title()).isEqualTo("Old " + id);
        assertThat(jobs.count()).isEqualTo(rows);
    }

    @Test
    void knownRowWhosePageIsRobotsDisallowedIsTouchedWithOldMarker() {
        seed("S", "/en/vacancy/java-secret", "2026-09-01T00:00:00Z");
        sitemapOk(entry("/en/vacancy/java-secret", "2026-10-10T00:00:00Z"));
        ok("/en/vacancy/java-secret", page("S", "New", false));
        runner.runSource(source(100));
        assertThat(HITS).doesNotContain("/en/vacancy/java-secret");
        assertKeptAlive("S", "2026-09-01T00:00:00Z", 1);
    }

    @Test
    void knownRowWhosePageIsExpiredIsTouchedWithOldMarker() {
        seed("A1", "/en/vacancy/java-a", "2026-09-01T00:00:00Z");
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-10T00:00:00Z"));
        ok("/en/vacancy/java-a", page("A1", "New", true));
        runner.runSource(source(100));
        assertThat(HITS).contains("/en/vacancy/java-a");
        assertKeptAlive("A1", "2026-09-01T00:00:00Z", 1);
    }

    @Test
    void knownRowWhosePageIsBadIsTouchedWithOldMarkerAndNoted() {
        seed("A1", "/en/vacancy/java-a", "2026-09-01T00:00:00Z");
        sitemapOk(entry("/en/vacancy/java-a", "2026-10-10T00:00:00Z"));
        ok("/en/vacancy/java-a", "<html>nothing embedded</html>");
        IngestionRunEntity r = runner.runSource(source(100));
        assertThat(HITS).contains("/en/vacancy/java-a");
        assertKeptAlive("A1", "2026-09-01T00:00:00Z", 1);
        assertThat(r.getError()).isEqualTo("1 bad page(s) skipped");
    }

    @Test
    void knownRowBeyondTheUrlCapIsStillTouched() {
        seed("B2", "/en/vacancy/java-b", "2026-10-02T10:00:00Z");
        Recipe base = recipe(100);
        Recipe capped = new Recipe(base.name(), true, base.strategy(), null,
                new Recipe.Sitemap(base.sitemap().url(), base.sitemap().urlPattern(), 1), base.detail(), base.limits());
        IngestionRunEntity r = runner.runSource(new SitemapRecipeJobSource(capped, fetcher, new SitemapReader(fetcher),
                Clock.systemUTC(), Duration.ofMinutes(20)));
        assertThat(HITS).doesNotContain("/en/vacancy/java-b");
        assertKeptAlive("B2", "2026-10-02T10:00:00Z", 2);
        assertThat(r.getError()).contains("URL cap");
    }
}
