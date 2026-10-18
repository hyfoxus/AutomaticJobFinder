package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

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

/** Real Postgres, real polite fetcher, local stub Workday tenant (search, detail, robots). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "ingestion.fetch.allow-insecure-hosts=127.0.0.1,localhost",
        "ingestion.fetch.min-delay=0ms",
        "ingestion.recipes.dir=./does-not-exist"})
class WorkdayFlowTest {

    private static final String SEARCH = "/wday/cxs/acme/Careers/jobs";
    private static final String DETAIL = "/wday/cxs/acme/Careers";
    /** Shaped like DXC's real file: the site is allowed, facet refreshes are not. */
    private static final String ROBOTS = "User-agent: *\nAllow: /Careers/\nDisallow: /refreshFacet/\n";

    private record Reply(int status, String body, String location) {
        Reply(int status, String body) {
            this(status, body, null);
        }
    }

    private record Post(String path, String body) {
    }

    private static final Map<String, Reply> ROUTES = new ConcurrentHashMap<>();
    private static final List<String> HITS = new CopyOnWriteArrayList<>();
    private static final List<Post> POSTS = new CopyOnWriteArrayList<>();
    private static final List<String> ALL = new CopyOnWriteArrayList<>();
    private static volatile int total;
    private static volatile IntFunction<Reply> searchOverride;
    private static volatile Runnable onDetail = () -> { };
    private static final HttpServer SERVER = start();

    private static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String path = ex.getRequestURI().getRawPath();
                String requestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                HITS.add(ex.getRequestMethod() + " " + path);
                Reply r;
                if (ex.getRequestMethod().equals("POST")) {
                    POSTS.add(new Post(path, requestBody));
                    r = path.equals(SEARCH) ? search(requestBody) : new Reply(404, "nope");
                } else {
                    if (path.startsWith(DETAIL + "/job/")) {
                        onDetail.run();
                    }
                    r = ROUTES.getOrDefault(path, new Reply(404, "nope"));
                }
                ex.getResponseHeaders().add("Content-Type", "application/json");
                if (r.location() != null) {
                    ex.getResponseHeaders().add("Location", r.location());
                }
                byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
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

    private static Reply search(String requestBody) {
        int offset = Integer.parseInt(requestBody.replaceAll(".*\"offset\":(\\d+).*", "$1"));
        IntFunction<Reply> o = searchOverride;
        if (o != null) {
            Reply r = o.apply(offset);
            if (r != null) {
                return r;
            }
        }
        List<String> slice = ALL.subList(Math.min(offset, ALL.size()), Math.min(offset + 20, ALL.size()));
        StringBuilder sb = new StringBuilder("{\"total\":" + (offset == 0 ? total : 0) + ",\"jobPostings\":[");
        for (int i = 0; i < slice.size(); i++) {
            sb.append(i > 0 ? "," : "").append("{\"title\":\"x\",\"externalPath\":\"").append(slice.get(i)).append("\"}");
        }
        return new Reply(200, sb.append("]}").toString());
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

    private static String path(int n) {
        return "/job/Lviv/Dev-" + n + "_R" + n;
    }

    private static String detailJson(int n) {
        return "{\"jobPostingInfo\":{\"title\":\"Job " + n + "\",\"location\":\"Lviv, Ukraine\","
                + "\"additionalLocations\":[\"Kyiv, Ukraine\"],\"timeType\":\"Full time\",\"startDate\":\"2026-10-01\","
                + "\"jobDescription\":\"<p>d" + n + "</p>\",\"posted\":true}}";
    }

    private static void listing(int count) {
        ALL.clear();
        for (int i = 1; i <= count; i++) {
            ALL.add(path(i));
            ROUTES.put(DETAIL + path(i), new Reply(200, detailJson(i)));
        }
        total = count;
    }

    @BeforeEach
    void reset() {
        jobs.deleteAll();
        runs.deleteAll();
        ROUTES.clear();
        HITS.clear();
        POSTS.clear();
        searchOverride = null;
        onDetail = () -> { };
        ROUTES.put("/robots.txt", new Reply(200, ROBOTS));
        listing(25);
    }

    private Recipe recipe(int cap, int maxPages) {
        return new Recipe("wd-stub", true, Recipe.STRATEGY_WORKDAY, null, null,
                new Recipe.Workday("acme.wd1.myworkdayjobs.com", "acme", "Careers", "java", "Acme Corp", maxPages), null,
                new Recipe.Limits(cap));
    }

    private WorkdayRecipeJobSource source(int cap, int maxPages, Clock clock) {
        return source(cap, maxPages, clock, base());
    }

    private WorkdayRecipeJobSource source(int cap, int maxPages, Clock clock, String origin) {
        return new WorkdayRecipeJobSource(recipe(cap, maxPages), fetcher, clock, Duration.ofMinutes(20),
                URI.create(origin));
    }

    /**
     * Robots rules are cached per host and port for a day, so a test that changes them gets its own
     * stub server (same routes) and therefore a cold cache.
     */
    private IngestionRunEntity runOnFreshServer() {
        HttpServer fresh = start();
        try {
            return runner.runSource(source(100, 50, Clock.systemUTC(), "http://127.0.0.1:" + fresh.getAddress().getPort()));
        } finally {
            fresh.stop(0);
        }
    }

    private WorkdayRecipeJobSource source(int cap, int maxPages) {
        return source(cap, maxPages, Clock.systemUTC());
    }

    private WorkdayRecipeJobSource source() {
        return source(100, 50);
    }

    private long detailHits() {
        return HITS.stream().filter(h -> h.startsWith("GET " + DETAIL + "/job/")).count();
    }

    private void seed(int n) {
        JobPosting p = new JobPosting(path(n), "wd-stub", "Old " + n, "Acme Corp", null,
                base() + "/Careers" + path(n), null, null, null, null, null, null);
        jobs.save(new JobEntity(p, Instant.parse("2026-09-01T00:00:00Z")));
    }

    @Test
    void happyPathStoresAllPostingsWithMappedFields() {
        IngestionRunEntity r = runner.runSource(source());

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(25);
        assertThat(r.getError()).isNull();
        assertThat(jobs.count()).isEqualTo(25);
        assertThat(POSTS).extracting(Post::body).containsExactly(
                "{\"appliedFacets\":{},\"limit\":20,\"offset\":0,\"searchText\":\"java\"}",
                "{\"appliedFacets\":{},\"limit\":20,\"offset\":20,\"searchText\":\"java\"}");
        JobPosting p = jobs.findBySourceAndExternalId("wd-stub", path(7)).orElseThrow().toRecord().posting();
        assertThat(p.url()).isEqualTo(base() + "/Careers" + path(7));
        assertThat(p.title()).isEqualTo("Job 7");
        assertThat(p.company()).isEqualTo("Acme Corp");
        assertThat(p.location()).isEqualTo("Lviv, Ukraine; Kyiv, Ukraine");
        assertThat(p.jobType()).isEqualTo("Full time");
        assertThat(p.postedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(p.description()).isEqualTo("<p>d7</p>");
        assertThat(p.updatedAt()).isNull();
    }

    @Test
    void secondRunFetchesNoDetailsAndRefreshesEveryRow() {
        WorkdayRecipeJobSource src = source();
        runner.runSource(src);
        long first = detailHits();
        assertThat(first).isEqualTo(25);

        IngestionRunEntity second = runner.runSource(src);

        assertThat(detailHits()).isEqualTo(first);
        assertThat(second.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(second.getInserted()).isZero();
        assertThat(second.getUpdated()).isEqualTo(25);
        assertThat(jobs.count()).isEqualTo(25);
    }

    @Test
    void pagingStopsOnShortPage() {
        listing(5);
        total = 0;
        runner.runSource(source());
        assertThat(POSTS).hasSize(1);
        assertThat(jobs.count()).isEqualTo(5);
    }

    @Test
    void pagingStopsOnEmptyPage() {
        listing(0);
        total = 0;
        IngestionRunEntity r = runner.runSource(source());
        assertThat(POSTS).hasSize(1);
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isZero();
    }

    @Test
    void pagingStopsWhenOffsetPassesTotalEvenOnFullPage() {
        listing(40);
        total = 20;
        runner.runSource(source());
        assertThat(POSTS).hasSize(1);
        assertThat(jobs.count()).isEqualTo(20);
    }

    @Test
    void pagingStopsAtMaxPagesAndNotesTheShortfall() {
        listing(60);
        IngestionRunEntity r = runner.runSource(source(100, 2));
        assertThat(POSTS).hasSize(2);
        assertThat(jobs.count()).isEqualTo(40);
        assertThat(r.getError()).contains("reported 60").contains("40 unique");
    }

    @Test
    void samePathOnTwoPagesIsCountedOnce() {
        listing(25);
        ALL.set(20, path(1)); // drift: page 2 repeats a posting from page 1
        IngestionRunEntity r = runner.runSource(source());
        assertThat(jobs.count()).isEqualTo(24);
        assertThat(HITS.stream().filter(h -> h.equals("GET " + DETAIL + path(1)))).hasSize(1);
        assertThat(r.getInserted()).isEqualTo(24);
        assertThat(r.getError()).contains("reported 25").contains("24 unique");
    }

    @Test
    void knownJobIsNotRefetchedButTouched() {
        seed(3);
        IngestionRunEntity r = runner.runSource(source());
        assertThat(HITS).doesNotContain("GET " + DETAIL + path(3));
        assertThat(r.getInserted()).isEqualTo(24);
        assertThat(r.getUpdated()).isEqualTo(1);
        JobEntity after = jobs.findBySourceAndExternalId("wd-stub", path(3)).orElseThrow();
        assertThat(after.getLastSeenAt()).isAfter(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(after.toRecord().posting().title()).isEqualTo("Old 3");
    }

    @Test
    void detailCapKeepsPagingAndNotesTheUnvisited() {
        seed(24);
        IngestionRunEntity r = runner.runSource(source(5, 50));
        assertThat(detailHits()).isEqualTo(5);
        assertThat(POSTS).hasSize(2);
        assertThat(r.getInserted()).isEqualTo(5);
        assertThat(r.getUpdated()).isEqualTo(1);
        assertThat(r.getError()).contains("limit of 5").contains("19 new");
    }

    @Test
    void goneAndClosedAndBadDetailsAreSkippedAndTheRestStored() {
        listing(8);
        ROUTES.put(DETAIL + path(1), new Reply(404, "gone"));
        ROUTES.put(DETAIL + path(2), new Reply(410, "gone"));
        ROUTES.put(DETAIL + path(3), new Reply(200, "{\"jobPostingInfo\":{\"title\":\"T\",\"posted\":false}}"));
        ROUTES.put(DETAIL + path(4), new Reply(200, "<html>not json</html>"));
        ROUTES.put(DETAIL + path(5), new Reply(200, "{\"other\":1}"));
        ROUTES.put(DETAIL + path(6), new Reply(200, "{\"jobPostingInfo\":{\"location\":\"x\"}}"));

        IngestionRunEntity r = runner.runSource(source());

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(2);
        assertThat(r.getSkipped()).isEqualTo(6);
        assertThat(r.getError()).contains("1 closed");
    }

    @Test
    void badPathsAreSkippedAndNeverRequested() {
        listing(2);
        ALL.addAll(List.of("//evil.example/x", "/job/../admin", "/job//x", "/other/path", "/job/a b", "/job/a?x=1",
                "/job/%2e%2e/x", ""));
        total = ALL.size();
        IngestionRunEntity r = runner.runSource(source());

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(2);
        assertThat(r.getSkipped()).isEqualTo(8);
        assertThat(HITS).noneMatch(h -> h.contains("evil") || h.contains("admin") || h.contains("other")
                || h.contains("%2e") || h.contains("/job//"));
        assertThat(HITS.stream().filter(h -> h.startsWith("GET " + DETAIL))).hasSize(2);
    }

    @Test
    void detailServerErrorsAreSkippedUntilFiveInARow() {
        listing(8);
        ROUTES.put(DETAIL + path(2), new Reply(500, "boom"));
        IngestionRunEntity ok = runner.runSource(source());
        assertThat(ok.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(ok.getInserted()).isEqualTo(7);
        assertThat(ok.getError()).contains("1 page fetch(es) failed");

        jobs.deleteAll();
        for (int i = 1; i <= 5; i++) {
            ROUTES.put(DETAIL + path(i), new Reply(503, "down"));
        }
        IngestionRunEntity failed = runner.runSource(source());
        assertThat(failed.getStatus()).isEqualTo(Status.FAILED);
        assertThat(jobs.count()).isZero();
    }

    @Test
    void searchFailuresFailTheRunAndStoreNothing() {
        Reply[] bad = {new Reply(500, "boom"), new Reply(404, "{}"), new Reply(200, "not json"),
                new Reply(200, "{\"total\":3}"), new Reply(200, "{\"total\":3,\"jobPostings\":{}}")};
        for (Reply reply : bad) {
            for (int failingOffset : new int[] {0, 20}) {
                jobs.deleteAll();
                searchOverride = offset -> offset == failingOffset ? reply : null;
                IngestionRunEntity r = runner.runSource(source());
                assertThat(r.getStatus()).as(reply + " at " + failingOffset).isEqualTo(Status.FAILED);
                assertThat(jobs.count()).isZero();
            }
        }
    }

    @Test
    void blockedSearchOrDetailFailsTheRunAsBlocked() {
        searchOverride = offset -> new Reply(429, "slow down");
        IngestionRunEntity search = runner.runSource(source());
        assertThat(search.getStatus()).isEqualTo(Status.FAILED);
        assertThat(search.getError()).isEqualTo("blocked");
        assertThat(POSTS).hasSize(1);

        searchOverride = null;
        ROUTES.put(DETAIL + path(2), new Reply(403, "no"));
        IngestionRunEntity detail = runner.runSource(source());
        assertThat(detail.getStatus()).isEqualTo(Status.FAILED);
        assertThat(detail.getError()).isEqualTo("blocked");
        assertThat(jobs.count()).isZero();
    }

    @Test
    void searchRedirectFailsTheRunWithExactlyOnePost() {
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            POSTS.clear();
            HITS.clear();
            searchOverride = offset -> new Reply(status, "", "/Careers/elsewhere");
            IngestionRunEntity r = runner.runSource(source());
            assertThat(r.getStatus()).as("" + status).isEqualTo(Status.FAILED);
            assertThat(POSTS).hasSize(1);
            assertThat(HITS).doesNotContain("GET /Careers/elsewhere", "POST /Careers/elsewhere");
        }
        assertThat(jobs.count()).isZero();
    }

    @Test
    void robotsDisallowingTheSearchPathFailsTheRunWithoutAnyPost() {
        ROUTES.put("/robots.txt", new Reply(200, ROBOTS + "Disallow: " + SEARCH + "\n"));
        IngestionRunEntity r = runOnFreshServer();
        assertThat(r.getStatus()).isEqualTo(Status.FAILED);
        assertThat(r.getError()).contains("robots.txt disallows");
        assertThat(POSTS).isEmpty();
    }

    @Test
    void robotsDisallowedDetailIsSkippedAndCounted() {
        ROUTES.put("/robots.txt", new Reply(200, ROBOTS + "Disallow: " + DETAIL + path(2) + "\n"));
        IngestionRunEntity r = runOnFreshServer();
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(24);
        assertThat(r.getSkipped()).isEqualTo(1);
        assertThat(r.getError()).contains("1 page(s) disallowed by robots.txt");
        assertThat(HITS).doesNotContain("GET " + DETAIL + path(2));
    }

    @Test
    void timeBudgetStopsKeepsCollectedJobsAndTouchesKnownOnes() {
        seed(1);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-18T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        onDetail = () -> now.updateAndGet(i -> i.plus(Duration.ofHours(1)));

        IngestionRunEntity r = runner.runSource(source(100, 50, clock));

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getError()).contains("time budget");
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getUpdated()).isEqualTo(1);
        assertThat(detailHits()).isEqualTo(1);
        assertThat(POSTS).hasSize(1);
        assertThat(jobs.count()).isEqualTo(2);
    }

    @Test
    void searchIsAPostAndDetailsAreGets() {
        listing(1);
        runner.runSource(source());
        assertThat(POSTS).hasSize(1);
        assertThat(HITS).contains("POST " + SEARCH, "GET " + DETAIL + path(1));
    }

    @Test
    void everyPostingWithoutAPathCountsAsSkipped() {
        searchOverride = offset -> new Reply(200, "{\"total\":3,\"jobPostings\":[{\"title\":\"a\"},{\"title\":\"b\"},"
                + "{\"externalPath\":\"" + path(1) + "\"}]}");
        IngestionRunEntity r = runner.runSource(source());
        assertThat(r.getInserted()).isEqualTo(1);
        assertThat(r.getSkipped()).isEqualTo(2);
        assertThat(r.getError()).contains("2 posting path(s) rejected");
    }

    @Test
    void pathOver255CharsIsRejectedAndNeverRequested() {
        String longPath = "/job/" + "x".repeat(251);
        searchOverride = offset -> new Reply(200, "{\"total\":1,\"jobPostings\":[{\"externalPath\":\"" + longPath + "\"}]}");
        IngestionRunEntity r = runner.runSource(source());
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getSkipped()).isEqualTo(1);
        assertThat(r.getInserted()).isZero();
        assertThat(detailHits()).isZero();
    }
}
