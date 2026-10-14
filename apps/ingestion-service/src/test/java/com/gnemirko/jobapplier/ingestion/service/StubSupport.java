package com.gnemirko.jobapplier.ingestion.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.gnemirko.jobapplier.ingestion.config.FetchProperties;
import com.gnemirko.jobapplier.ingestion.util.Sleeper;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;
import com.sun.net.httpserver.HttpServer;

/** Local stub server, fake clock and fake sleeper; no live network. */
final class StubSupport implements AutoCloseable {

    record Stub(int status, Map<String, String> headers, byte[] body) {
        static Stub of(int status, String body, String... headerPairs) {
            Map<String, String> h = new ConcurrentHashMap<>();
            for (int i = 0; i < headerPairs.length; i += 2) {
                h.put(headerPairs[i], headerPairs[i + 1]);
            }
            return new Stub(status, h, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    final HttpServer server;
    final int port;
    final Map<String, Stub> routes = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    volatile java.util.concurrent.CountDownLatch gate;
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    final List<String> acceptEncodings = new CopyOnWriteArrayList<>();

    StubSupport() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        port = server.getAddress().getPort();
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            String ua = ex.getRequestHeaders().getFirst("User-Agent");
            userAgents.add(ua);
            String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
            acceptEncodings.add(ae == null ? "" : ae);
            java.util.concurrent.CountDownLatch g = gate;
            if (g != null && path.equals("/robots.txt")) {
                try {
                    g.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            Stub stub = routes.get(path);
            if (stub == null) {
                stub = Stub.of(404, "nope");
            }
            stub.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(stub.status(), stub.body().length == 0 ? -1 : stub.body().length);
            if (stub.body().length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(stub.body());
                }
            }
            ex.close();
        });
        server.start();
    }

    void route(String path, Stub stub) {
        routes.put(path, stub);
    }

    int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Fake monotonic clock; the fake sleeper advances it and records every wait. */
    static class FakeTime implements Sleeper {
        final AtomicLong now = new AtomicLong(1_000_000_000L);
        final List<Duration> sleeps = new CopyOnWriteArrayList<>();
        volatile boolean interruptNext;

        long nanos() {
            return now.get();
        }

        void advance(Duration d) {
            now.addAndGet(d.toNanos());
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            if (interruptNext) {
                interruptNext = false;
                throw new InterruptedException("test");
            }
            sleeps.add(duration);
            advance(duration);
        }

        Duration total() {
            return sleeps.stream().reduce(Duration.ZERO, Duration::plus);
        }
    }

    static FetchProperties props(String... overrideHosts) {
        return new FetchProperties("JobApplierBot/0.1 (personal job-search tool)", Duration.ofSeconds(2),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 5 * 1024 * 1024, List.of(overrideHosts));
    }

    /** Wired fetcher parts. */
    static final class Rig {
        final FetchProperties props;
        final UrlPolicy policy;
        final HttpExchange exchange;
        final HostPacer pacer;
        final RobotsService robots;
        final PoliteFetcher fetcher;

        Rig(FetchProperties props, FakeTime time) {
            this.props = props;
            policy = new UrlPolicy(props);
            exchange = new HttpExchange(props, policy);
            pacer = new HostPacer(time, time::nanos);
            robots = new RobotsService(props, policy, exchange, pacer, time::nanos);
            fetcher = new PoliteFetcher(props, policy, robots, exchange, pacer, time::nanos);
        }
    }
}
