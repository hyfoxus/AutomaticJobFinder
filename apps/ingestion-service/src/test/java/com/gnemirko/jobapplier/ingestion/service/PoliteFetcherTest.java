package com.gnemirko.jobapplier.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.ingestion.config.FetchProperties;
import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.FakeTime;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.Rig;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.Stub;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

class PoliteFetcherTest {

    private StubSupport stub;
    private FakeTime time;
    private Rig rig;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubSupport();
        time = new FakeTime();
        rig = new Rig(StubSupport.props("127.0.0.1", "localhost"), time);
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nDisallow: /private\n"));
        stub.route("/page", Stub.of(200, "hello", "Content-Type", "text/html"));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void allowedPageIsReturned() {
        FetchedPage page = rig.fetcher.fetch(stub.uri("/page"));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.finalUri()).isEqualTo(stub.uri("/page"));
        assertThat(page.contentType()).isEqualTo("text/html");
        assertThat(new String(page.body())).isEqualTo("hello");
        assertThat(stub.userAgents).allMatch(ua -> ua.equals("JobApplierBot/0.1 (personal job-search tool)"));
        assertThat(stub.acceptEncodings).allMatch(ae -> ae.equals("identity"));
    }

    @Test
    void disallowedPathIsNeverRequested() {
        stub.route("/private/x", Stub.of(200, "secret"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/private/x"))).isInstanceOf(RobotsDisallowedException.class);
        assertThat(stub.hits("/private/x")).isZero();
    }

    @Test
    void missingRobotsAllowsEverything() {
        stub.routes.remove("/robots.txt");
        stub.route("/private/x", Stub.of(200, "open"));
        assertThat(rig.fetcher.fetch(stub.uri("/private/x")).status()).isEqualTo(200);
    }

    @Test
    void robotsServerErrorDeniesAndIsRetriedOnlyAfterAnHour() {
        stub.route("/robots.txt", Stub.of(503, "down"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/page"))).isInstanceOf(FetchException.class)
                .isNotInstanceOf(FetchBlockedException.class);
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/page"))).isInstanceOf(FetchException.class);
        assertThat(stub.hits("/robots.txt")).isEqualTo(1);
        assertThat(stub.hits("/page")).isZero();

        time.advance(Duration.ofMinutes(61));
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nAllow: /\n"));
        assertThat(rig.fetcher.fetch(stub.uri("/page")).status()).isEqualTo(200);
        assertThat(stub.hits("/robots.txt")).isEqualTo(2);
    }

    @Test
    void robotsForbiddenMeansBlocked() {
        stub.route("/robots.txt", Stub.of(403, "no"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/page"))).isInstanceOf(FetchBlockedException.class);
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/page"))).isInstanceOf(FetchBlockedException.class);
        assertThat(stub.hits("/robots.txt")).isEqualTo(1);
        assertThat(stub.hits("/page")).isZero();
    }

    @Test
    void robotsChallengeMeansBlocked() {
        stub.route("/robots.txt", Stub.of(200, "x", "cf-mitigated", "challenge"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/page"))).isInstanceOf(FetchBlockedException.class);
    }

    @Test
    void challengeStatusesStopWithoutRetry() {
        stub.route("/a", Stub.of(403, "x"));
        stub.route("/b", Stub.of(429, "x"));
        stub.route("/c", Stub.of(200, "x", "cf-mitigated", "challenge"));
        stub.route("/d", Stub.of(503, "<html><title>Just a moment...</title>"));
        stub.route("/e", Stub.of(503, "<script src=\"/cdn-cgi/challenge-platform/h/b\">"));
        for (String p : List.of("/a", "/b", "/c", "/d", "/e")) {
            assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri(p))).as(p).isInstanceOf(FetchBlockedException.class);
            assertThat(stub.hits(p)).as(p).isEqualTo(1);
        }
    }

    @Test
    void retryAfterZeroOn503IsNotResent() {
        stub.route("/ra", Stub.of(503, "busy", "Retry-After", "0"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/ra"))).isInstanceOf(FetchException.class);
        assertThat(stub.hits("/ra")).isEqualTo(1);
    }

    @Test
    void oversizedBlockedResponseIsStillBlocked() {
        Rig small = new Rig(smallLimit(10), time);
        stub.route("/big429", Stub.of(429, "x".repeat(10_000)));
        assertThatThrownBy(() -> small.fetcher.fetch(stub.uri("/big429"))).isInstanceOf(FetchBlockedException.class);
    }

    @Test
    void plainServerErrorIsTransientNotBlocked() {
        stub.route("/e", Stub.of(503, "maintenance"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/e"))).isInstanceOf(FetchException.class)
                .isNotInstanceOf(FetchBlockedException.class);
    }

    @Test
    void goneStatusIsReturned() {
        assertThat(rig.fetcher.fetch(stub.uri("/missing")).status()).isEqualTo(404);
        stub.route("/gone", Stub.of(410, "gone"));
        assertThat(rig.fetcher.fetch(stub.uri("/gone")).status()).isEqualTo(410);
    }

    @Test
    void sameHostRedirectIsFollowed() {
        stub.route("/old", Stub.of(301, "", "Location", "/page"));
        FetchedPage page = rig.fetcher.fetch(stub.uri("/old"));
        assertThat(page.finalUri()).isEqualTo(stub.uri("/page"));
        assertThat(page.status()).isEqualTo(200);
    }

    @Test
    void foreignHostRedirectIsNotFollowed() {
        stub.route("/old", Stub.of(302, "", "Location", "http://localhost:" + stub.port + "/page"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/old")))
                .isInstanceOf(FetchException.class).hasMessageContaining("another host");
        assertThat(stub.hits("/page")).isZero();
    }

    @Test
    void redirectToDisallowedPathIsNotFollowed() {
        stub.route("/private/x", Stub.of(200, "secret"));
        stub.route("/old", Stub.of(302, "", "Location", "/private/x"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/old"))).isInstanceOf(RobotsDisallowedException.class);
        assertThat(stub.hits("/private/x")).isZero();
    }

    @Test
    void tooManyRedirectsStop() {
        for (int i = 0; i < 10; i++) {
            stub.route("/r" + i, Stub.of(302, "", "Location", "/r" + (i + 1)));
        }
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/r0"))).isInstanceOf(FetchException.class)
                .hasMessageContaining("Too many redirects");
        assertThat(stub.hits("/r6")).isZero();
    }

    @Test
    void fiveRedirectsAreAllowed() {
        for (int i = 0; i < 5; i++) {
            stub.route("/r" + i, Stub.of(302, "", "Location", "/r" + (i + 1)));
        }
        stub.route("/r5", Stub.of(200, "end"));
        assertThat(rig.fetcher.fetch(stub.uri("/r0")).status()).isEqualTo(200);
    }

    @Test
    void malformedLocationBecomesFetchException() {
        stub.route("/bad", Stub.of(302, "", "Location", "http://exa mple.com/ x y"));
        assertThatThrownBy(() -> rig.fetcher.fetch(stub.uri("/bad"))).isInstanceOf(FetchException.class);
    }

    @Test
    void oversizeContentLengthIsRejected() {
        Rig small = new Rig(smallLimit(10), time);
        stub.route("/big", Stub.of(200, "x".repeat(100)));
        assertThatThrownBy(() -> small.fetcher.fetch(stub.uri("/big"))).isInstanceOf(FetchException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void oversizeStreamIsRejected() throws Exception {
        // No Content-Length: a raw chunked response from a second tiny server.
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if (ex.getRequestURI().getPath().equals("/robots.txt")) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, 0);
                try (var os = ex.getResponseBody()) {
                    os.write(new byte[100]);
                }
            }
            ex.close();
        });
        server.start();
        try {
            Rig small = new Rig(smallLimit(10), time);
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/s");
            assertThatThrownBy(() -> small.fetcher.fetch(uri)).isInstanceOf(FetchException.class)
                    .hasMessageContaining("too large");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void privateAddressWithoutOverrideIsNotRequested() {
        Rig strict = new Rig(StubSupport.props(), time);
        assertThatThrownBy(() -> strict.fetcher.fetch(URI.create("https://localhost/page"))).isInstanceOf(FetchException.class);
        assertThatThrownBy(() -> strict.fetcher.fetch(stub.uri("/page"))).isInstanceOf(FetchException.class);
        assertThat(stub.hits("/page")).isZero();
        assertThat(stub.hits("/robots.txt")).isZero();
    }

    @Test
    void dnsLevelRejectionStopsConnectionToLoopback() {
        Rig strict = new Rig(StubSupport.props(), time);
        Deadline deadline = new Deadline(time::nanos, Duration.ofSeconds(30));
        URI uri = URI.create("http://localhost:" + stub.port + "/page");
        assertThatThrownBy(() -> strict.exchange.get(uri, 1000, true, deadline)).isInstanceOf(FetchException.class);
        assertThat(stub.hits("/page")).isZero();
    }

    @Test
    void urlPolicyRejectsUnsafeUrls() {
        UrlPolicy policy = new UrlPolicy(StubSupport.props());
        for (String u : List.of("http://example.com/", "https://user:pw@example.com/", "https://example.com:8443/",
                "https://127.0.0.1/", "https://[::1]/", "https://10.0.0.1/", "ftp://example.com/")) {
            assertThatThrownBy(() -> policy.validate(URI.create(u))).as(u).isInstanceOf(FetchException.class);
        }
        policy.validate(URI.create("https://example.com/"));
        policy.validate(URI.create("https://example.com:443/"));
    }

    @Test
    void pacingMakesSecondRequestWait() {
        rig.fetcher.fetch(stub.uri("/page"));
        // robots.txt took the first slot, so the page already waited one delay
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(2));
        rig.fetcher.fetch(stub.uri("/page"));
        assertThat(time.sleeps).hasSize(2);
        assertThat(time.sleeps.get(1)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void hostAndHostWithDefaultPortShareAKey() {
        assertThat(UrlPolicy.hostKey(URI.create("https://Example.com./a")))
                .isEqualTo(UrlPolicy.hostKey(URI.create("https://example.com:443/b")));
        assertThat(UrlPolicy.hostKey(URI.create("http://example.com:80/")))
                .isEqualTo(UrlPolicy.hostKey(URI.create("http://example.com/")));
        Deadline d = new Deadline(time::nanos, Duration.ofSeconds(30));
        rig.pacer.pace(UrlPolicy.hostKey(URI.create("https://example.com/")), Duration.ofSeconds(2), d);
        rig.pacer.pace(UrlPolicy.hostKey(URI.create("https://example.com:443/")), Duration.ofSeconds(2), d);
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void crawlDelayRaisesTheDelayAndIsCapped() {
        stub.route("/robots.txt", Stub.of(200, "User-agent: jobapplierbot\nCrawl-delay: 5\n"));
        rig.fetcher.fetch(stub.uri("/page"));
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(5));
        rig.fetcher.fetch(stub.uri("/page"));
        assertThat(time.sleeps.get(1)).isEqualTo(Duration.ofSeconds(5));

        StubSupport.FakeTime t2 = new FakeTime();
        Rig capped = new Rig(StubSupport.props("127.0.0.1"), t2);
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nCrawl-delay: 600\n"));
        capped.fetcher.fetch(stub.uri("/page"));
        capped.fetcher.fetch(stub.uri("/page"));
        assertThat(t2.sleeps.get(1)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void longPacingWaitDoesNotFailTheRequest() {
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nCrawl-delay: 60\n"));
        rig.fetcher.fetch(stub.uri("/page"));
        // total timeout is 30 s but the 60 s wait extends the deadline
        assertThat(rig.fetcher.fetch(stub.uri("/page")).status()).isEqualTo(200);
        assertThat(time.sleeps.get(1)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void parallelCallsGetDistinctOrderedSlots() throws Exception {
        FakeTime frozen = new FakeTime() {
            @Override
            public void sleep(Duration duration) {
                sleeps.add(duration);
            }
        };
        HostPacer pacer = new HostPacer(frozen, frozen::nanos);
        Deadline d = new Deadline(frozen::nanos, Duration.ofSeconds(30));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> {
                    pacer.pace("h", Duration.ofSeconds(2), d);
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(frozen.sleeps).containsExactlyInAnyOrder(Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(6), Duration.ofSeconds(8), Duration.ofSeconds(10), Duration.ofSeconds(12),
                Duration.ofSeconds(14));
    }

    private static FetchProperties smallLimit(long max) {
        FetchProperties p = StubSupport.props("127.0.0.1");
        return new FetchProperties(p.userAgent(), p.minDelay(), p.connectTimeout(), p.totalTimeout(), max, p.allowInsecureHosts());
    }

    private static tools.jackson.databind.JsonNode json(String text) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(text);
    }

    @Test
    void postSendsExactBodyAndHeadersOnce() {
        stub.route("/api", Stub.of(200, "{\"ok\":true}", "Content-Type", "application/json"));
        FetchedPage page = rig.fetcher.post(stub.uri("/api"), json("{\"limit\":20,\"searchText\":\"a\\\"b\"}"));
        assertThat(page.status()).isEqualTo(200);
        assertThat(new String(page.body())).isEqualTo("{\"ok\":true}");
        var posts = stub.requestsTo("/api");
        assertThat(posts).hasSize(1);
        var seen = posts.get(0);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.body()).isEqualTo("{\"limit\":20,\"searchText\":\"a\\\"b\"}");
        assertThat(seen.contentType()).isEqualTo("application/json");
        assertThat(seen.accept()).isEqualTo("application/json");
        assertThat(seen.acceptLanguage()).isEqualTo("en-US");
        assertThat(stub.userAgents).allMatch(ua -> ua.equals("JobApplierBot/0.1 (personal job-search tool)"));
        assertThat(stub.acceptEncodings).allMatch(ae -> ae.equals("identity"));
    }

    @Test
    void getJsonAsksForJsonAndPlainGetDoesNot() {
        stub.route("/api", Stub.of(200, "{}", "Content-Type", "application/json"));
        rig.fetcher.fetchJson(stub.uri("/api"));
        rig.fetcher.fetch(stub.uri("/page"));
        var api = stub.requestsTo("/api").get(0);
        assertThat(api.method()).isEqualTo("GET");
        assertThat(api.accept()).isEqualTo("application/json");
        assertThat(api.acceptLanguage()).isEqualTo("en-US");
        var plain = stub.requestsTo("/page").get(0);
        assertThat(plain.accept()).isNotEqualTo("application/json");
        assertThat(plain.acceptLanguage()).isNull();
    }

    @Test
    void postToDisallowedPathIsNeverSent() {
        stub.route("/private/api", Stub.of(200, "{}"));
        assertThatThrownBy(() -> rig.fetcher.post(stub.uri("/private/api"), json("{}")))
                .isInstanceOf(RobotsDisallowedException.class);
        assertThat(stub.hits("/private/api")).isZero();
    }

    @Test
    void postRedirectIsRefusedAfterExactlyOneRequest() {
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            stub.route("/api" + status, Stub.of(status, "", "Location", "/page"));
            assertThatThrownBy(() -> rig.fetcher.post(stub.uri("/api" + status), json("{}")))
                    .isInstanceOf(FetchException.class).hasMessageContaining("refused for POST");
            assertThat(stub.requestsTo("/api" + status)).hasSize(1);
        }
        assertThat(stub.hits("/page")).isZero();
    }

    @Test
    void postBlockedStatusesAreBlocked() {
        stub.route("/a403", Stub.of(403, "no"));
        stub.route("/a429", Stub.of(429, "slow"));
        assertThatThrownBy(() -> rig.fetcher.post(stub.uri("/a403"), json("{}"))).isInstanceOf(FetchBlockedException.class);
        assertThatThrownBy(() -> rig.fetcher.post(stub.uri("/a429"), json("{}"))).isInstanceOf(FetchBlockedException.class);
        assertThat(stub.requestsTo("/a429")).hasSize(1);
    }

    @Test
    void postServerErrorIsTransientAndClientErrorIsReturned() {
        stub.route("/a500", Stub.of(500, "boom"));
        stub.route("/a400", Stub.of(400, "bad"));
        assertThatThrownBy(() -> rig.fetcher.post(stub.uri("/a500"), json("{}"))).isInstanceOf(FetchException.class)
                .isNotInstanceOf(FetchBlockedException.class);
        assertThat(rig.fetcher.post(stub.uri("/a400"), json("{}")).status()).isEqualTo(400);
    }

    @Test
    void postRefusesUnsafeUrlsAndOversizeAnswers() {
        Rig strict = new Rig(StubSupport.props(), time);
        assertThatThrownBy(() -> strict.fetcher.post(stub.uri("/api"), json("{}"))).isInstanceOf(FetchException.class);
        assertThatThrownBy(() -> strict.fetcher.post(URI.create("https://127.0.0.1/api"), json("{}")))
                .isInstanceOf(FetchException.class);
        assertThat(stub.requestsTo("/api")).isEmpty();

        Rig small = new Rig(smallLimit(10), time);
        stub.route("/big", Stub.of(200, "x".repeat(100)));
        assertThatThrownBy(() -> small.fetcher.post(stub.uri("/big"), json("{}"))).isInstanceOf(FetchException.class)
                .hasMessageContaining("too large");
        assertThat(stub.requestsTo("/big")).hasSize(1);
    }

    @Test
    void postChallengeResponsesAreBlocked() {
        stub.route("/cf", Stub.of(200, "x", "cf-mitigated", "challenge"));
        stub.route("/c503", Stub.of(503, "<html><title>Just a moment...</title></html>"));
        assertThatThrownBy(() -> rig.fetcher.post(stub.uri("/cf"), json("{}"))).isInstanceOf(FetchBlockedException.class);
        assertThatThrownBy(() -> rig.fetcher.post(stub.uri("/c503"), json("{}"))).isInstanceOf(FetchBlockedException.class);
        assertThat(stub.requestsTo("/cf")).hasSize(1);
        assertThat(stub.requestsTo("/c503")).hasSize(1);
    }

    @Test
    void postIsPacedLikeGet() {
        stub.route("/api", Stub.of(200, "{}"));
        rig.fetcher.post(stub.uri("/api"), json("{}"));
        time.sleeps.clear();
        rig.fetcher.post(stub.uri("/api"), json("{}"));
        assertThat(time.total()).isPositive();
    }
}
