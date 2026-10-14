package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gnemirko.jobapplier.contracts.JobRecord;
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
import com.gnemirko.jobapplier.ingestion.service.RecipeRegistry;
import com.gnemirko.jobapplier.ingestion.source.RemotiveJobSource;
import com.gnemirko.jobapplier.ingestion.service.SourceRegistry;
import com.sun.net.httpserver.HttpServer;

/** Real Postgres, real polite fetcher, local stub site. Never touches the live network. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "ingestion.fetch.allow-insecure-hosts=127.0.0.1,localhost",
        "ingestion.fetch.min-delay=0ms",
        "ingestion.recipes.dir=./does-not-exist"})
class RecipeEngineFlowTest {

    private static final Map<String, Reply> ROUTES = new ConcurrentHashMap<>();
    private static final List<String> HITS = new CopyOnWriteArrayList<>();
    private static volatile Runnable onHit = () -> { };
    private static final HttpServer SERVER = start();

    record Reply(int status, String body, java.nio.charset.Charset charset, String contentType) {
        Reply(int status, String body) {
            this(status, body, StandardCharsets.UTF_8, "text/html; charset=utf-8");
        }
    }

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String q = ex.getRequestURI().getRawQuery();
                String key = ex.getRequestURI().getPath() + (q == null ? "" : "?" + q);
                HITS.add(key);
                if (key.startsWith("/jobs/")) {
                    onHit.run();
                }
                Reply r = ROUTES.getOrDefault(key, new Reply(404, "nope"));
                byte[] bytes = r.body().getBytes(r.charset());
                ex.getResponseHeaders().add("Content-Type", r.contentType());
                ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(bytes);
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
    @Autowired RemotiveJobSource remotive;
    @Autowired Clock springClock;
    @TempDir Path tmp;

    private static String base() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    private static String listing(String... hrefs) {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (String h : hrefs) {
            sb.append("<a href=\"").append(h).append("\">job</a>");
        }
        return sb.append("</body></html>").toString();
    }

    private static String detail(String id, String title) {
        String idPart = id == null ? "" : ",\"identifier\":{\"value\":\"" + id + "\"}";
        return "<html><head><script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"" + title
                + "\",\"datePosted\":\"2026-10-01\",\"employmentType\":\"FULL_TIME\",\"description\":\"&lt;p&gt;d&lt;/p&gt;\","
                + "\"hiringOrganization\":{\"name\":\"Acme\"},"
                + "\"jobLocation\":{\"address\":{\"addressLocality\":\"Lviv\",\"addressCountry\":\"UA\"}}"
                + idPart + "}</script></head><body></body></html>";
    }

    private void ok(String key, String body) {
        ROUTES.put(key, new Reply(200, body));
    }

    @BeforeEach
    void reset() {
        jobs.deleteAll();
        runs.deleteAll();
        ROUTES.clear();
        HITS.clear();
        onHit = () -> { };
        ok("/robots.txt", "User-agent: *\nDisallow: /jobs/secret-9\nDisallow: /jobs/secret-8\nDisallow: /closed\n");
        ok("/jobs?page=1", listing("/jobs/a-1", "/jobs/b-2#frag", "http://other.example/jobs/x-7", "/about"));
        ok("/jobs?page=2", listing("/jobs/b-2", "/jobs/c-3"));
        ok("/jobs?page=3", listing("/jobs/a-1", "/jobs/c-3"));
        ok("/jobs?page=4", listing("/jobs/z-99"));
        ok("/jobs/a-1", detail("A1", "Job A"));
        ok("/jobs/b-2", detail("B2", "Job B"));
        ok("/jobs/c-3", detail("C3", "Job C"));
    }

    private RecipeJobSource source(int maxPages, int cap, String idFrom, Clock clock) {
        Recipe recipe = new Recipe("stub", true, Recipe.STRATEGY_PAGINATED_LISTING,
                new Recipe.Listing(base() + "/jobs?page={page}", 1, maxPages, "a[href]", "/jobs/[a-z]+-[0-9]+$"),
                new Recipe.Detail("json-ld", idFrom), new Recipe.Limits(cap));
        return new RecipeJobSource(recipe, fetcher, clock, Duration.ofMinutes(20));
    }

    private RecipeJobSource source(int maxPages, int cap) {
        return source(maxPages, cap, "jsonld", Clock.systemUTC());
    }

    private long detailHits() {
        return HITS.stream().filter(h -> h.startsWith("/jobs/")).count();
    }

    @Test
    void happyPathStoresThreeJobsWithCanonicalUrls() {
        IngestionRunEntity r = runner.runSource(source(10, 200));

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(3);
        assertThat(jobs.count()).isEqualTo(3);
        JobRecord rec = queries.since(Instant.EPOCH).stream()
                .filter(j -> j.posting().externalId().equals("B2")).findFirst().orElseThrow();
        assertThat(rec.posting().url()).isEqualTo(base() + "/jobs/b-2");
        assertThat(rec.posting().title()).isEqualTo("Job B");
        assertThat(rec.posting().company()).isEqualTo("Acme");
        assertThat(rec.posting().location()).isEqualTo("Lviv, UA");
        assertThat(rec.posting().jobType()).isEqualTo("FULL_TIME");
        assertThat(rec.posting().description()).isEqualTo("<p>d</p>");
        assertThat(rec.posting().postedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(HITS).doesNotContain("/jobs/x-7", "/about");
        // page 3 holds only links already seen, so page 4 is never requested.
        assertThat(HITS).contains("/jobs?page=3").doesNotContain("/jobs?page=4");
    }

    @Test
    void secondRunFetchesNoDetailsButRefreshesLastSeen() {
        RecipeJobSource src = source(10, 200);
        runner.runSource(src);
        JobEntity before = jobs.findBySourceAndExternalId("stub", "B2").orElseThrow();
        long detailsAfterFirst = detailHits();

        IngestionRunEntity second = runner.runSource(src);

        assertThat(detailHits()).isEqualTo(detailsAfterFirst);
        assertThat(second.getInserted()).isZero();
        assertThat(second.getUpdated()).isEqualTo(3);
        assertThat(jobs.count()).isEqualTo(3);
        JobEntity after = jobs.findBySourceAndExternalId("stub", "B2").orElseThrow();
        assertThat(after.getLastSeenAt()).isAfter(before.getLastSeenAt());
        assertThat(after.getFirstSeenAt()).isEqualTo(before.getFirstSeenAt());
    }

    @Test
    void maxPagesStopsTheCrawl() {
        runner.runSource(source(1, 200));
        assertThat(HITS).contains("/jobs?page=1").doesNotContain("/jobs?page=2");
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    void emptyPageEndsPagination() {
        ok("/jobs?page=2", listing("/about"));
        runner.runSource(source(10, 200));
        assertThat(HITS).doesNotContain("/jobs?page=3");
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    void detailCapStillRefreshesKnownLinks() {
        RecipeJobSource src = source(10, 1);
        IngestionRunEntity first = runner.runSource(src);
        assertThat(first.getInserted()).isEqualTo(1);
        assertThat(detailHits()).isEqualTo(1);

        IngestionRunEntity second = runner.runSource(src);
        assertThat(second.getInserted()).isEqualTo(1);
        assertThat(second.getUpdated()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    void robotsDisallowedDetailIsNeverRequestedAndCountedSkipped() {
        ok("/jobs?page=1", listing("/jobs/a-1", "/jobs/secret-9"));
        ok("/jobs/secret-9", detail("S9", "Secret"));
        IngestionRunEntity r = runner.runSource(source(1, 200));

        assertThat(HITS).doesNotContain("/jobs/secret-9");
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(1);
    }

    @Test
    void botChallengeFailsRunAndLeavesDataUnchanged() {
        RecipeJobSource src = source(10, 200);
        runner.runSource(src);
        Instant seen = jobs.findBySourceAndExternalId("stub", "A1").orElseThrow().getLastSeenAt();
        ok("/jobs?page=1", listing("/jobs/a-1", "/jobs/d-4"));
        ROUTES.put("/jobs/d-4", new Reply(403, "Just a moment"));

        IngestionRunEntity r = runner.runSource(src);

        assertThat(r.getStatus()).isEqualTo(Status.FAILED);
        assertThat(r.getError()).isEqualTo("blocked");
        assertThat(jobs.count()).isEqualTo(3);
        assertThat(jobs.findBySourceAndExternalId("stub", "A1").orElseThrow().getLastSeenAt()).isEqualTo(seen);
    }

    @Test
    void goneNoJsonLdAndMissingIdAreSkipped() {
        ok("/jobs?page=1", listing("/jobs/a-1", "/jobs/g-5", "/jobs/n-6", "/jobs/m-7"));
        ROUTES.put("/jobs/g-5", new Reply(410, "gone"));
        ok("/jobs/n-6", "<html><body>no data</body></html>");
        ok("/jobs/m-7", detail(null, "No id"));
        IngestionRunEntity r = runner.runSource(source(1, 200));

        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(3);
        assertThat(jobs.findBySourceAndExternalId("stub", "A1")).isPresent();
    }

    @Test
    void notFoundDetailIsSkipped() {
        ok("/jobs?page=1", listing("/jobs/a-1", "/jobs/q-8"));
        IngestionRunEntity r = runner.runSource(source(1, 200));
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(1);
    }

    @Test
    void idCanComeFromTheUrl() {
        ok("/jobs/a-1", detail(null, "Job A"));
        ok("/jobs?page=1", listing("/jobs/a-1"));
        runner.runSource(source(1, 200, "urlRegex:-([0-9]+)$", Clock.systemUTC()));
        assertThat(jobs.findBySourceAndExternalId("stub", "1")).isPresent();
    }

    @Test
    void timeBudgetStopsEarlyKeepsCollectedJobsAndAddsNote() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-14T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        onHit = () -> now.updateAndGet(i -> i.plus(Duration.ofHours(1)));

        IngestionRunEntity r = runner.runSource(source(10, 200, "jsonld", clock));

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getError()).contains("time budget");
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void detailServerErrorIsSkippedAndOtherJobsStored() {
        ROUTES.put("/jobs/a-1", new Reply(503, "down"));
        IngestionRunEntity r = runner.runSource(source(10, 200));

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(2);
        assertThat(r.getSkipped()).isEqualTo(1);
        assertThat(jobs.findBySourceAndExternalId("stub", "B2")).isPresent();
    }

    @Test
    void fiveConsecutiveDetailFailuresFailTheRun() {
        ok("/jobs?page=1", listing("/jobs/d-1", "/jobs/d-2", "/jobs/d-3", "/jobs/d-4", "/jobs/d-5", "/jobs/d-6"));
        for (int i = 1; i <= 6; i++) {
            ROUTES.put("/jobs/d-" + i, new Reply(503, "down"));
        }
        IngestionRunEntity r = runner.runSource(source(1, 200));

        assertThat(r.getStatus()).isEqualTo(Status.FAILED);
        assertThat(jobs.count()).isZero();
        assertThat(HITS.stream().filter(h -> h.startsWith("/jobs/d-")).count()).isEqualTo(5);
    }

    @Test
    void successResetsTheFailureStreak() {
        ok("/jobs?page=1", listing("/jobs/d-1", "/jobs/d-2", "/jobs/d-3", "/jobs/d-4", "/jobs/a-1", "/jobs/d-5",
                "/jobs/d-6", "/jobs/d-7", "/jobs/d-8"));
        for (int i = 1; i <= 8; i++) {
            ROUTES.put("/jobs/d-" + i, new Reply(503, "down"));
        }
        IngestionRunEntity r = runner.runSource(source(1, 200));
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(8);
    }

    @Test
    void linkWithAnotherSchemeIsIgnored() {
        ok("/jobs?page=1", listing("/jobs/a-1", "https://127.0.0.1:" + SERVER.getAddress().getPort() + "/jobs/h-5"));
        IngestionRunEntity r = runner.runSource(source(1, 200));
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getSkipped()).isZero();
    }

    @Test
    void robotsDisallowedLinksDoNotConsumeTheDetailCap() {
        ok("/jobs?page=1", listing("/jobs/secret-9", "/jobs/secret-8", "/jobs/a-1"));
        IngestionRunEntity r = runner.runSource(source(1, 1));
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(2);
        assertThat(jobs.findBySourceAndExternalId("stub", "A1")).isPresent();
    }

    @Test
    void detailCharsetComesFromHeader() {
        ROUTES.put("/jobs/a-1", new Reply(200, detail("A1", "Caf\u00e9 Entwickler"),
                StandardCharsets.ISO_8859_1, "text/html; charset=ISO-8859-1"));
        ok("/jobs?page=1", listing("/jobs/a-1"));
        runner.runSource(source(1, 200));
        assertThat(queries.since(Instant.EPOCH).get(0).posting().title()).isEqualTo("Caf\u00e9 Entwickler");
    }

    @Test
    void detailCharsetComesFromMetaWhenHeaderHasNone() {
        String html = detail("A1", "\u0420\u0430\u0437\u0440\u0430\u0431\u043e\u0442\u0447\u0438\u043a")
                .replace("<head>", "<head><meta charset=\"windows-1251\">");
        ROUTES.put("/jobs/a-1", new Reply(200, html, java.nio.charset.Charset.forName("windows-1251"), "text/html"));
        ok("/jobs?page=1", listing("/jobs/a-1"));
        runner.runSource(source(1, 200));
        assertThat(queries.since(Instant.EPOCH).get(0).posting().title())
                .isEqualTo("\u0420\u0430\u0437\u0440\u0430\u0431\u043e\u0442\u0447\u0438\u043a");
    }

    @Test
    void catastrophicLinkPatternTimesOutAsNoMatch() {
        Recipe recipe = new Recipe("stub", true, Recipe.STRATEGY_PAGINATED_LISTING,
                new Recipe.Listing(base() + "/jobs?page={page}", 1, 1, "a[href]", "(a+)+$"),
                new Recipe.Detail("json-ld", "jsonld"), new Recipe.Limits(5));
        ok("/jobs?page=1", listing("/jobs/" + "a".repeat(60) + "!"));
        long start = System.nanoTime();
        IngestionRunEntity r = runner.runSource(new RecipeJobSource(recipe, fetcher, Clock.systemUTC(),
                Duration.ofMinutes(20)));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(jobs.count()).isZero();
    }

    @Test
    void robotsDisallowedListingIsReportedInTheNote() {
        Recipe recipe = new Recipe("stub", true, Recipe.STRATEGY_PAGINATED_LISTING,
                new Recipe.Listing(base() + "/closed/list?page={page}", 1, 3, "a[href]", "/jobs/[a-z]+-[0-9]+$"),
                new Recipe.Detail("json-ld", "jsonld"), new Recipe.Limits(5));
        IngestionRunEntity r = runner.runSource(new RecipeJobSource(recipe, fetcher, Clock.systemUTC(),
                Duration.ofMinutes(20)));
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getError()).contains("disallowed by robots.txt").contains("/closed/list");
        assertThat(HITS).doesNotContain("/closed/list?page=1");
    }

    @Test
    void recipesAreRegisteredAfterBuiltInsAndDisabledOnesAreLeftOut() throws Exception {
        String tpl = """
                {"name":"%s","enabled":%s,"strategy":"paginated-listing",
                 "listing":{"urlTemplate":"https://a.example/jobs?page={page}","maxPages":2,"linkSelector":"a","linkPattern":"x"},
                 "detail":{"extractor":"json-ld","idFrom":"jsonld"}}""";
        Files.writeString(tmp.resolve("on.json"), tpl.formatted("on-recipe", "true"));
        Files.writeString(tmp.resolve("off.json"), tpl.formatted("off-recipe", "false"));
        SourceRegistry registry = new SourceRegistry(List.of(remotive),
                new RecipeRegistry(tmp, List.of("remotive")), fetcher, springClock, Duration.ofMinutes(20));

        assertThat(registry.all().stream().map(src -> src.name()).toList()).containsExactly("remotive", "on-recipe");
    }

    @Test
    void sharedJsonLdIdentifierStoresOneJobAndCountsTheOtherSkipped() {
        ok("/jobs?page=1", listing("/jobs/a-1", "/jobs/b-2"));
        ok("/jobs/a-1", detail("SAME", "Job A"));
        ok("/jobs/b-2", detail("SAME", "Job B"));
        IngestionRunEntity r = runner.runSource(source(1, 200));

        assertThat(jobs.count()).isEqualTo(1);
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(1);
    }

    @Test
    void urlRegexIdKeepsPagesThatShareAJsonLdIdentifier() {
        ok("/jobs?page=1", listing("/jobs/a-1", "/jobs/b-2"));
        ok("/jobs/a-1", detail("SAME", "Job A"));
        ok("/jobs/b-2", detail("SAME", "Job B"));
        IngestionRunEntity r = runner.runSource(source(1, 200, "urlRegex:-([0-9]+)$", Clock.systemUTC()));

        assertThat(jobs.count()).isEqualTo(2);
        assertThat(r.getSkipped()).isZero();
    }
}
