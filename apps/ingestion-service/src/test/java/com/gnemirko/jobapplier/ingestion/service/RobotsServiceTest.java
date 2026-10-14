package com.gnemirko.jobapplier.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.FakeTime;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.Rig;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.Stub;

class RobotsServiceTest {

    private StubSupport stub;
    private FakeTime time;
    private Rig rig;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubSupport();
        time = new FakeTime();
        rig = new Rig(StubSupport.props("127.0.0.1", "localhost"), time);
    }

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        stub.close();
    }

    @Test
    void namedGroupWinsOverWildcard() {
        stub.route("/robots.txt", Stub.of(200,
                "User-agent: *\nDisallow: /\n\nUser-agent: jobapplierbot\nDisallow: /nope\n"));
        RobotsService.Rules rules = rig.robots.rulesFor(stub.uri("/x"));
        assertThat(rules.isAllowed(stub.uri("/ok"))).isTrue();
        assertThat(rules.isAllowed(stub.uri("/nope/1"))).isFalse();
    }

    @Test
    void wildcardAppliesWhenNoNamedGroup() {
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nDisallow: /private\n"));
        RobotsService.Rules rules = rig.robots.rulesFor(stub.uri("/x"));
        assertThat(rules.isAllowed(stub.uri("/private/a"))).isFalse();
        assertThat(rules.isAllowed(stub.uri("/public"))).isTrue();
    }

    @Test
    void successIsCachedFor24Hours() {
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nAllow: /\n"));
        rig.robots.rulesFor(stub.uri("/x"));
        time.advance(Duration.ofHours(23));
        rig.robots.rulesFor(stub.uri("/x"));
        assertThat(stub.hits("/robots.txt")).isEqualTo(1);
        time.advance(Duration.ofHours(2));
        rig.robots.rulesFor(stub.uri("/x"));
        assertThat(stub.hits("/robots.txt")).isEqualTo(2);
    }

    @Test
    void missingRobotsAllowsAll() {
        stub.route("/robots.txt", Stub.of(410, "gone"));
        assertThat(rig.robots.rulesFor(stub.uri("/x")).isAllowed(stub.uri("/anything"))).isTrue();
    }

    @Test
    void failureIsRethrownAsFreshInstanceAndCachedAnHour() {
        stub.route("/robots.txt", Stub.of(403, "no"));
        Throwable first = catchIt();
        Throwable second = catchIt();
        assertThat(first).isInstanceOf(FetchBlockedException.class);
        assertThat(second).isNotSameAs(first);
        assertThat(stub.hits("/robots.txt")).isEqualTo(1);
        time.advance(Duration.ofMinutes(61));
        catchIt();
        assertThat(stub.hits("/robots.txt")).isEqualTo(2);
    }

    @Test
    void foreignHostRobotsRedirectIsRefusedForTheForeignHostReason() {
        // both hosts are on the override list, so only the same-host rule can stop this
        stub.route("/robots.txt", Stub.of(302, "", "Location", "http://localhost:" + stub.port + "/elsewhere"));
        stub.route("/elsewhere", Stub.of(200, "User-agent: *\nAllow: /\n"));
        assertThatThrownBy(() -> rig.robots.rulesFor(stub.uri("/x"))).isInstanceOf(FetchException.class)
                .hasMessageContaining("another host");
        assertThat(stub.hits("/elsewhere")).isZero();
    }

    @Test
    void sameHostRobotsRedirectIsFollowed() {
        stub.route("/robots.txt", Stub.of(301, "", "Location", "/real-robots"));
        stub.route("/real-robots", Stub.of(200, "User-agent: *\nDisallow: /a\n"));
        assertThat(rig.robots.rulesFor(stub.uri("/x")).isAllowed(stub.uri("/a"))).isFalse();
    }

    @Test
    void badRobotsLocationBecomesFetchException() {
        stub.route("/robots.txt", Stub.of(302, "", "Location", "http://bad host/ x"));
        assertThatThrownBy(() -> rig.robots.rulesFor(stub.uri("/x"))).isInstanceOf(FetchException.class);
    }

    @Test
    void robotsBodyIsCappedAt500Kb() {
        String filler = "# pad\n".repeat(100_000); // ~600 KB, disallow rule sits beyond the cap
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\n" + filler + "Disallow: /late\n"));
        assertThat(rig.robots.rulesFor(stub.uri("/x")).isAllowed(stub.uri("/late"))).isTrue();
    }

    @Test
    void parallelFirstCallsFetchRobotsOnce() throws Exception {
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nAllow: /\n"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> rig.robots.rulesFor(stub.uri("/x")));
            }
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(stub.hits("/robots.txt")).isEqualTo(1);
    }

    @Test
    void interruptionIsNotCachedAsAFailure() {
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nAllow: /\n"));
        Deadline d = new Deadline(time::nanos, Duration.ofSeconds(30));
        rig.pacer.pace(com.gnemirko.jobapplier.ingestion.util.UrlPolicy.hostKey(stub.uri("/")), Duration.ofSeconds(2), d);
        time.interruptNext = true;
        assertThatThrownBy(() -> rig.robots.rulesFor(stub.uri("/x"))).isInstanceOf(FetchException.class);
        Thread.interrupted(); // clear the flag restored by the pacer
        assertThat(rig.robots.rulesFor(stub.uri("/x")).isAllowed(stub.uri("/p"))).isTrue();
        assertThat(stub.hits("/robots.txt")).isEqualTo(1);
    }

    @Test
    void interruptDuringHttpCallIsNotCached() {
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nAllow: /\n"));
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> rig.robots.rulesFor(stub.uri("/x"))).isInstanceOf(FetchException.class);
        assertThat(Thread.interrupted()).isTrue(); // flag was restored
        assertThat(rig.robots.rulesFor(stub.uri("/x")).isAllowed(stub.uri("/p"))).isTrue();
    }

    @Test
    void slowHostDoesNotBlockAnotherHost() throws Exception {
        try (StubSupport other = new StubSupport()) {
            other.route("/robots.txt", Stub.of(200, "User-agent: *\nAllow: /\n"));
            stub.route("/robots.txt", Stub.of(200, "User-agent: *\nAllow: /\n"));
            stub.gate = new java.util.concurrent.CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> slow = pool.submit(() -> rig.robots.rulesFor(stub.uri("/x")));
                Thread.sleep(300);
                assertThat(java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> rig.robots.rulesFor(other.uri("/x")))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
                assertThat(slow.isDone()).isFalse();
                stub.gate.countDown();
                slow.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                stub.gate.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void hostlessUriIsAFetchExceptionNotAnNpe() {
        assertThatThrownBy(() -> rig.robots.rulesFor(java.net.URI.create("file:///etc/passwd")))
                .isInstanceOf(FetchException.class);
        assertThatThrownBy(() -> rig.robots.rulesFor(java.net.URI.create("/relative")))
                .isInstanceOf(FetchException.class);
    }

    private Throwable catchIt() {
        try {
            rig.robots.rulesFor(stub.uri("/x"));
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected failure");
    }
}
