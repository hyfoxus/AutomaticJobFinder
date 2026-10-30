package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.TestcontainersConfiguration;
import com.gnemirko.jobapplier.ingestion.dto.FetchContext;
import com.gnemirko.jobapplier.ingestion.dto.FetchResult;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity.Status;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.repository.IngestionRunRepository;
import com.gnemirko.jobapplier.ingestion.repository.JobRepository;
import com.gnemirko.jobapplier.ingestion.service.IngestionService;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.service.RecipeRegistry;
import com.gnemirko.jobapplier.ingestion.service.SourceRegistry;
import com.gnemirko.jobapplier.ingestion.source.RunNotes;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real Postgres, real polite fetcher, a local stub JSON endpoint (the matrix of plan-json-api-strategy). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "ingestion.fetch.allow-insecure-hosts=127.0.0.1,localhost",
        "ingestion.fetch.min-delay=0ms",
        "ingestion.recipes.dir=./does-not-exist"})
class JsonApiFlowTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PATH = "/api/jobs";

    private record Req(String method, String rawPath, String query, String body) {
    }

    private record Reply(int status, String body, String type, String location) {
        Reply(String body) {
            this(200, body, "application/json", null);
        }

        Reply(int status, String body) {
            this(status, body, "application/json", null);
        }
    }

    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Req, Reply> api = r -> new Reply("{\"items\":[]}");
    private volatile String robots = "User-agent: *\nAllow: /\n";
    private HttpServer server;

    @Autowired IngestionService runner;
    @Autowired PoliteFetcher fetcher;
    @Autowired JobRepository jobs;
    @Autowired IngestionRunRepository runs;

    @BeforeEach
    void start() throws IOException {
        jobs.deleteAll();
        runs.deleteAll();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            Reply r;
            if (path.equals("/robots.txt")) {
                r = new Reply(200, robots, "text/plain", null);
            } else {
                Req req = new Req(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(),
                        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                requests.add(req);
                r = path.equals(PATH) || path.startsWith("/job/") ? api.apply(req) : new Reply(404, "nope");
            }
            ex.getResponseHeaders().add("Content-Type", r.type());
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
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static Map<String, JsonNode> fields(String json) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        MAPPER.readTree(json).properties().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static final String FIELDS = "{\"id\":\"id\",\"title\":\"title\",\"url\":\"link\",\"company\":{\"const\":\"Acme\"},"
            + "\"location\":{\"join\":[\"city\",\"country\"],\"sep\":\", \"},\"postedAt\":\"posted\"}";

    private static Recipe.JsonApi get(int size, int maxPages, String fieldJson, String jobUrlTemplate, Integer maxItems,
            String totalPath) {
        return new Recipe.JsonApi("https://api.example.com" + PATH, "GET",
                Map.of("page", "${page}", "size", "${size}", "q", "java dev"), null,
                new Recipe.Paging("page", 1, size, maxPages, totalPath), "items", fields(fieldJson), jobUrlTemplate,
                maxItems);
    }

    private static Recipe recipe(Recipe.JsonApi a, Recipe.Detail detail, Integer cap) {
        return new Recipe("api-stub", true, Recipe.STRATEGY_JSON_API, null, null, null, detail, new Recipe.Limits(cap), a);
    }

    private JsonApiRecipeJobSource source(Recipe r) {
        return new JsonApiRecipeJobSource(r, fetcher, Clock.systemUTC(), Duration.ofMinutes(5), base());
    }

    private JsonApiRecipeJobSource source(Recipe.JsonApi a) {
        return source(recipe(a, null, null));
    }

    private static String item(int n) {
        return "{\"id\":\"J" + n + "\",\"title\":\"Job " + n + "\",\"link\":\"/job/J" + n
                + "\",\"city\":\"Lviv\",\"country\":\"UA\",\"posted\":1700000000}";
    }

    private static String items(int from, int to) {
        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int i = from; i <= to; i++) {
            sb.append(i > from ? "," : "").append(item(i));
        }
        return sb.append("]}").toString();
    }

    private static int pageOf(Req r) {
        return Integer.parseInt(r.query().replaceAll(".*page=(\\d+).*", "$1"));
    }

    @Test
    void getApiTwoPagesStopsAfterTheShortPage() {
        api = r -> new Reply(pageOf(r) == 1 ? items(1, 2) : items(3, 3));

        FetchResult result = source(get(2, 10, FIELDS, null, null, null)).fetch();

        assertThat(result.postings()).extracting(JobPosting::externalId).containsExactly("J1", "J2", "J3");
        assertThat(requests).extracting(Req::query).containsExactly("page=1&q=java%20dev&size=2",
                "page=2&q=java%20dev&size=2");
        JobPosting p = result.postings().get(0);
        assertThat(p.url()).isEqualTo(base() + "/job/J1");
        assertThat(p.company()).isEqualTo("Acme");
        assertThat(p.location()).isEqualTo("Lviv, UA");
        assertThat(p.source()).isEqualTo("api-stub");
    }

    @Test
    void postApiRendersTheBodyPerPageAndStoresPostings() {
        api = r -> new Reply(r.body().contains("\"page\":1") ? items(1, 2) : items(3, 3));
        JsonNode body = MAPPER.readTree("{\"filter\":{\"q\":\"java\",\"tags\":[\"a\"]},\"page\":\"${page}\",\"size\":\"${size}\"}");
        Recipe.JsonApi a = new Recipe.JsonApi("https://api.example.com" + PATH, "POST", null, body,
                new Recipe.Paging("page", 1, 2, 10, null), "items", fields(FIELDS), null, null);

        IngestionRunEntity run = runner.runSource(source(a));

        assertThat(run.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(run.getInserted()).isEqualTo(3);
        assertThat(requests).extracting(Req::method).containsOnly("POST");
        assertThat(requests).extracting(Req::body).containsExactly(
                "{\"filter\":{\"q\":\"java\",\"tags\":[\"a\"]},\"page\":1,\"size\":2}",
                "{\"filter\":{\"q\":\"java\",\"tags\":[\"a\"]},\"page\":2,\"size\":2}");
        assertThat(jobs.count()).isEqualTo(3);
        assertThat(jobs.findAll()).extracting(JobEntity::getExternalId).containsExactlyInAnyOrder("J1", "J2", "J3");
    }

    @Test
    void offsetPagingFeedsTheOffsetSlot() {
        api = r -> new Reply(r.body().contains("\"from\":0") ? items(1, 2) : items(3, 3));
        JsonNode body = MAPPER.readTree("{\"from\":\"${offset}\",\"n\":\"${size}\"}");
        Recipe.JsonApi a = new Recipe.JsonApi("https://api.example.com" + PATH, "POST", null, body,
                new Recipe.Paging("offset", null, 2, 10, null), "items", fields(FIELDS), null, null);

        source(a).fetch();

        assertThat(requests).extracting(Req::body).containsExactly("{\"from\":0,\"n\":2}", "{\"from\":2,\"n\":2}");
    }

    @Test
    void topLevelArrayWorksWithAWildcardPath() {
        api = r -> new Reply("[" + item(1) + "," + item(2) + "]");
        Recipe.JsonApi a = new Recipe.JsonApi("https://api.example.com" + PATH, "GET", Map.of("size", "${size}", "page", "${page}"), null,
                new Recipe.Paging("page", 1, 10, 5, null), "[*]", fields(FIELDS), null, null);

        FetchResult result = source(a).fetch();

        assertThat(result.postings()).hasSize(2);
        assertThat(requests).hasSize(1);
    }

    @Test
    void idsOnlyListBuildsTheUrlFromTheTemplateAndSkipsIdsOutsideTheCharset() {
        api = r -> new Reply("{\"items\":[{\"id\":\"A-1.b\",\"title\":\"One\"},{\"id\":\"a/b\",\"title\":\"Slash\"},"
                + "{\"id\":\"has space\",\"title\":\"Space\"}]}");
        String fieldJson = "{\"id\":\"id\",\"title\":\"title\"}";

        FetchResult result = source(get(10, 5, fieldJson, "https://jobs.example.com/careers/{id}", null, null)).fetch();

        assertThat(result.postings()).extracting(JobPosting::url).containsExactly("https://jobs.example.com/careers/A-1.b");
        assertThat(result.skipped()).isEqualTo(2);
    }

    @Test
    void itemsWithoutIdOrTitleAreCountedSkipped() {
        api = r -> new Reply("{\"items\":[" + item(1) + ",{\"title\":\"no id\",\"link\":\"/job/x\"},"
                + "{\"id\":\"J9\",\"link\":\"/job/J9\"}]}");

        FetchResult result = source(get(10, 5, FIELDS, null, null, null)).fetch();

        assertThat(result.postings()).hasSize(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.note()).contains("2 item(s) skipped");
    }

    @Test
    void anApiThatIgnoresPagingStopsOnNoNewIds() {
        api = r -> new Reply(items(1, 2));

        FetchResult result = source(get(2, 10, FIELDS, null, null, null)).fetch();

        assertThat(result.postings()).hasSize(2);
        assertThat(requests).hasSize(2);
        assertThat(result.note()).contains("no new ids");
    }

    @Test
    void epochSecondsAndMillisAreBothRead() {
        api = r -> new Reply("{\"items\":[{\"id\":\"s\",\"title\":\"S\",\"link\":\"/job/s\",\"posted\":1700000000},"
                + "{\"id\":\"m\",\"title\":\"M\",\"link\":\"/job/m\",\"posted\":1700000000000}]}");

        FetchResult result = source(get(10, 1, FIELDS, null, null, null)).fetch();

        assertThat(result.postings()).extracting(JobPosting::postedAt)
                .containsExactly(Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void a401FailsTheRunWithANeedsAuthenticationNote() {
        api = r -> new Reply(401, "{\"error\":\"auth\"}");

        IngestionRunEntity run = runner.runSource(source(get(2, 5, FIELDS, null, null, null)));

        assertThat(run.getStatus()).isEqualTo(Status.FAILED);
        assertThat(run.getError()).contains("needs authentication");
        assertThat(jobs.count()).isZero();
    }

    @Test
    void a403And429AreBlockedExceptions() {
        api = r -> new Reply(403, "{}");
        assertThatThrownBy(() -> source(get(2, 5, FIELDS, null, null, null)).fetch())
                .isInstanceOf(FetchBlockedException.class);
        api = r -> new Reply(429, "{}");
        assertThatThrownBy(() -> source(get(2, 5, FIELDS, null, null, null)).fetch())
                .isInstanceOf(FetchBlockedException.class);
    }

    @Test
    void aLoginPageOrNonJsonBodyFailsTheRun() {
        api = r -> new Reply(200, "<html>log in</html>", "text/html", null);
        assertThatThrownBy(() -> source(get(2, 5, FIELDS, null, null, null)).fetch())
                .isInstanceOf(FetchException.class).hasMessageContaining("not JSON (login page?)");
        api = r -> new Reply(200, "<html>log in</html>", "application/json", null);
        assertThatThrownBy(() -> source(get(2, 5, FIELDS, null, null, null)).fetch())
                .isInstanceOf(FetchException.class).hasMessageContaining("not JSON (login page?)");
    }

    @Test
    void aSecondPageFailureFailsTheWholeRunAndStoresNothing() {
        api = r -> pageOf(r) == 1 ? new Reply(items(1, 2)) : new Reply(500, "boom");

        IngestionRunEntity run = runner.runSource(source(get(2, 5, FIELDS, null, null, null)));

        assertThat(run.getStatus()).isEqualTo(Status.FAILED);
        assertThat(jobs.count()).isZero();
    }

    @Test
    void robotsDisallowingTheApiPathMeansNoRequest() {
        robots = "User-agent: *\nDisallow: /api/\n";

        FetchResult result = source(get(2, 5, FIELDS, null, null, null)).fetch();

        assertThat(result.postings()).isEmpty();
        assertThat(result.note()).contains(RunNotes.ROBOTS_DISALLOWED);
        assertThat(RunNotes.isPartial(result.note())).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test
    void aRedirectOnPostIsAFetchExceptionAndSentOnce() {
        api = r -> new Reply(302, "", "application/json", "/elsewhere");
        Recipe.JsonApi a = new Recipe.JsonApi("https://api.example.com" + PATH, "POST", null,
                MAPPER.readTree("{\"page\":\"${page}\"}"), new Recipe.Paging("page", 1, 2, 5, null), "items",
                fields(FIELDS), null, null);

        assertThatThrownBy(() -> source(a).fetch()).isInstanceOf(FetchException.class).hasMessageContaining("Redirect");
        assertThat(requests).hasSize(1);
    }

    @Test
    void zeroItemsOnTheFirstPageIsAnOkRunWithZero() {
        api = r -> new Reply("{\"items\":[]}");

        IngestionRunEntity run = runner.runSource(source(get(2, 5, FIELDS, null, null, null)));

        assertThat(run.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(run.getInserted()).isZero();
        assertThat(requests).hasSize(1);
    }

    @Test
    void totalPathStopsEvenOnAFullPage() {
        api = r -> new Reply("{\"total\":2,\"items\":" + items(1, 2).substring(9, items(1, 2).length() - 1) + "}");

        FetchResult result = source(get(2, 5, FIELDS, null, null, "total")).fetch();

        assertThat(result.postings()).hasSize(2);
        assertThat(requests).hasSize(1);
    }

    @Test
    void maxPagesAndMaxItemsBoundTheRun() {
        api = r -> new Reply(items((pageOf(r) - 1) * 2 + 1, pageOf(r) * 2));
        assertThat(source(get(2, 2, FIELDS, null, null, null)).fetch().postings()).hasSize(4);
        requests.clear();

        FetchResult capped = source(get(2, 10, FIELDS, null, 3, null)).fetch();

        assertThat(capped.postings()).hasSize(3);
        assertThat(capped.note()).contains("limit of 3 items");
    }

    @Test
    void theRunDeadlineStopsPaging() {
        api = r -> new Reply(items((pageOf(r) - 1) * 2 + 1, pageOf(r) * 2));
        Clock past = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
        JsonApiRecipeJobSource s = new JsonApiRecipeJobSource(recipe(get(2, 10, FIELDS, null, null, null), null, null),
                fetcher, past, Duration.ZERO, base());

        FetchResult result = s.fetch();

        assertThat(result.postings()).isEmpty();
        assertThat(result.note()).contains("Stopped early");
        assertThat(requests).isEmpty();
    }

    @Test
    void aDetailBlockReadsJobPagesOnTheEndpointHostSkipsKnownOnesAndHonoursTheCap() {
        api = r -> {
            if (r.rawPath().startsWith("/job/")) {
                String id = r.rawPath().substring("/job/".length());
                return new Reply(200, "<html><head><script type=\"application/ld+json\">{\"@type\":\"JobPosting\","
                        + "\"title\":\"Detail " + id + "\",\"datePosted\":\"2026-10-01\",\"description\":\"d\","
                        + "\"hiringOrganization\":{\"name\":\"Acme\"},\"identifier\":{\"value\":\"" + id
                        + "\"}}</script></head></html>", "text/html", null);
            }
            return new Reply(items(1, 3));
        };
        Recipe r = recipe(get(10, 5, FIELDS, null, null, null), new Recipe.Detail(Recipe.EXTRACTOR_JSON_LD, Recipe.ID_JSONLD), 1);
        String known = base() + "/job/J1";

        FetchResult result = source(r).fetch(new FetchContext(Map.of(known, Instant.parse("2026-09-01T00:00:00Z"))));

        assertThat(result.unchangedUrls()).containsExactly(known);
        assertThat(result.postings()).extracting(JobPosting::title).containsExactly("Detail J2");
        assertThat(result.note()).contains("1 new (retried next run)");
        assertThat(requests.stream().filter(x -> x.rawPath().startsWith("/job/"))).hasSize(1);
    }

    @Test
    void aForeignJobHostIsAllowedOnlyWithoutADetailBlock() {
        api = r -> new Reply("{\"items\":[{\"id\":\"1\",\"title\":\"T\",\"link\":\"https://other.example.org/j/1\"}]}");

        assertThat(source(get(10, 1, FIELDS, null, null, null)).fetch().postings())
                .extracting(JobPosting::url).containsExactly("https://other.example.org/j/1");
        Recipe withDetail = recipe(get(10, 1, FIELDS, null, null, null),
                new Recipe.Detail(Recipe.EXTRACTOR_JSON_LD, Recipe.ID_JSONLD), null);
        FetchResult result = source(withDetail).fetch();

        assertThat(result.postings()).isEmpty();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void idsAndUrlsThatCouldTraverseAreSkipped() {
        api = r -> new Reply("{\"items\":[{\"id\":\"..\",\"title\":\"a\"},{\"id\":\".\",\"title\":\"b\"},"
                + "{\"id\":\"%2e\",\"title\":\"c\"},{\"id\":\"ok\",\"title\":\"d\"}]}");
        FetchResult result = source(get(10, 1, "{\"id\":\"id\",\"title\":\"title\"}", "https://jobs.example.com/careers/{id}", null, null)).fetch();
        assertThat(result.postings()).extracting(JobPosting::externalId).containsExactly("ok");
        assertThat(result.skipped()).isEqualTo(3);

        api = r -> new Reply("{\"items\":[{\"id\":\"1\",\"title\":\"a\",\"link\":\"/job/../x\"},"
                + "{\"id\":\"2\",\"title\":\"b\",\"link\":\"/job/%2e%2e/x\"},{\"id\":\"3\",\"title\":\"c\",\"link\":\"/a//b\"},"
                + "{\"id\":\"4\",\"title\":\"d\",\"link\":\"/a%2Fb\"},{\"id\":\"5\",\"title\":\"e\",\"link\":\"/job/J5\"}]}");
        FetchResult urls = source(get(10, 1, FIELDS, null, null, null)).fetch();
        assertThat(urls.postings()).extracting(JobPosting::externalId).containsExactly("5");
        assertThat(urls.skipped()).isEqualTo(4);
    }

    @Test
    void anOverLongUrlSkipsTheItemInsteadOfTruncating() {
        String longUrl = "/job/" + "x".repeat(2100);
        api = r -> new Reply("{\"items\":[{\"id\":\"1\",\"title\":\"a\",\"link\":\"" + longUrl
                + "\"},{\"id\":\"2\",\"title\":\"b\",\"link\":\"/job/J2\"}]}");

        FetchResult result = source(get(10, 1, FIELDS, null, null, null)).fetch();

        assertThat(result.postings()).extracting(JobPosting::externalId).containsExactly("2");
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void detailHostComparisonIgnoresDefaultPortAndTrailingDot() {
        api = r -> new Reply("{\"items\":[{\"id\":\"1\",\"title\":\"T\",\"link\":\"https://API.example.com.:443/job/1\"}]}");
        Recipe r = new Recipe("api-stub", true, Recipe.STRATEGY_JSON_API, null, null, null,
                new Recipe.Detail(Recipe.EXTRACTOR_JSON_LD, Recipe.ID_JSONLD), new Recipe.Limits(5),
                get(10, 1, FIELDS, null, null, null));
        // the stub endpoint is http://127.0.0.1:port, so a different host is skipped; the key comparison itself:
        assertThat(source(r).fetch().skipped()).isEqualTo(1);
        assertThat(JsonApiValidator.sameHost(URI.create("https://API.example.com.:443/a"), URI.create("https://api.example.com/b")))
                .isTrue();
        assertThat(JsonApiValidator.sameHost(URI.create("https://api.example.com:8443/a"), URI.create("https://api.example.com/b")))
                .isFalse();
    }

    @Test
    void exactlyReachingTheItemCapRequestsNoFurtherPage() {
        api = r -> new Reply(items((pageOf(r) - 1) * 2 + 1, pageOf(r) * 2));

        FetchResult result = source(get(2, 10, FIELDS, null, 4, null)).fetch();

        assertThat(result.postings()).hasSize(4);
        assertThat(requests).hasSize(2);
    }

    @Test
    void aPageWhereNothingParsesReportsNoUsableItems() {
        api = r -> new Reply("{\"items\":[{\"x\":1},{\"y\":2}]}");

        FetchResult result = source(get(2, 5, FIELDS, null, null, null)).fetch();

        assertThat(result.note()).contains("page 1: no usable items").doesNotContain("ignore paging");
        assertThat(requests).hasSize(1);
    }

    @Test
    void aJsonApiFileLoadsThroughTheRegistriesAndTheRunStoresPostings(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        api = r -> new Reply(pageOf(r) == 1 ? items(1, 2) : items(3, 3));
        java.nio.file.Files.writeString(dir.resolve("api.json"), "{\"name\":\"api-stub\",\"enabled\":true,"
                + "\"strategy\":\"json-api\",\"jsonApi\":{\"url\":\"https://api.example.com" + PATH + "\","
                + "\"query\":{\"page\":\"${page}\"},\"paging\":{\"kind\":\"page\",\"size\":2},\"itemsPath\":\"items\","
                + "\"fields\":" + FIELDS + "}}");

        RecipeRegistry registry = new RecipeRegistry(dir, List.of());
        SourceRegistry sources = new SourceRegistry(List.of(), registry, fetcher, Clock.systemUTC(), Duration.ofMinutes(5));

        assertThat(sources.all()).singleElement().isInstanceOf(JsonApiRecipeJobSource.class);
        // the registry source targets https://api.example.com; run the same loaded recipe against the http stub
        IngestionRunEntity run = runner.runSource(source(registry.recipes().get(0)));
        assertThat(run.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(jobs.count()).isEqualTo(3);
    }
}
