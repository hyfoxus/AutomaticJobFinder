package com.gnemirko.jobapplier.digest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.gnemirko.jobapplier.digest.dto.AssessmentItem;
import com.gnemirko.jobapplier.digest.dto.GapItem;
import com.gnemirko.jobapplier.digest.dto.UsageInfo;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** A fake scoring API on a local port, with the real keyset semantics of scoredSince/afterId plus failure modes. */
public class StubScoring {

    public enum Mode { OK, ERROR, BAD_JSON, HUGE, HUGE_CHUNKED, REDIRECT, SLOW }

    private final HttpServer server;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public final List<String> queries = new CopyOnWriteArrayList<>();
    public volatile List<AssessmentItem> items = List.of();
    public volatile Mode mode = Mode.OK;
    public volatile boolean usageFails;
    /** When set, an assessments call announces itself on {@code arrived} and waits for {@code gate}. */
    public volatile CountDownLatch gate;
    public final CountDownLatch[] arrived = {new CountDownLatch(1)};

    public StubScoring() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/assessments", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            queries.add(query);
            CountDownLatch g = gate;
            if (g != null) {
                arrived[0].countDown();
                try {
                    g.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = 200;
            byte[] body;
            switch (mode) {
                case ERROR -> {
                    status = 500;
                    body = "boom".getBytes(StandardCharsets.UTF_8);
                }
                case BAD_JSON -> body = "<html>not json</html>".getBytes(StandardCharsets.UTF_8);
                case HUGE, HUGE_CHUNKED -> {
                    AssessmentItem big = new AssessmentItem(1L, "x".repeat(1_000_000), "c", "l", "https://e.com/1",
                            "SCORED", "STRONG", List.of(), "LIKELY", "r", Instant.now());
                    body = mapper.writeValueAsBytes(List.of(big, big, big));
                }
                case REDIRECT -> {
                    ex.getResponseHeaders().add("Location", "/other");
                    status = 302;
                    body = new byte[0];
                }
                case SLOW -> {
                    sleep(3000);
                    body = mapper.writeValueAsBytes(List.of());
                }
                default -> body = mapper.writeValueAsBytes(page(query));
            }
            send(ex, status, body, mode == Mode.HUGE_CHUNKED);
        });
        server.createContext("/other", ex -> send(ex, 200, "[]".getBytes(StandardCharsets.UTF_8), false));
        server.createContext("/api/usage/today", ex -> {
            if (usageFails) {
                send(ex, 500, "boom".getBytes(StandardCharsets.UTF_8), false);
            } else {
                send(ex, 200, mapper.writeValueAsBytes(new UsageInfo("0.123000", "1.000000", "OK", 7L)), false);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-scoring");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    private List<AssessmentItem> page(String query) {
        Map<String, String> q = new HashMap<>();
        if (query != null) {
            Arrays.stream(query.split("&")).forEach(kv -> {
                String[] p = kv.split("=", 2);
                q.put(p[0], URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8));
            });
        }
        Instant since = Instant.parse(q.get("scoredSince"));
        long afterId = Long.parseLong(q.getOrDefault("afterId", "0"));
        int limit = Integer.parseInt(q.getOrDefault("limit", "50"));
        return items.stream()
                .filter(i -> "SCORED".equals(i.status()))
                .filter(i -> i.scoredAt().isAfter(since) || (i.scoredAt().equals(since) && i.id() > afterId))
                .sorted(Comparator.comparing(AssessmentItem::scoredAt).thenComparing(AssessmentItem::id))
                .limit(Math.min(limit, 200))
                .toList();
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, byte[] body, boolean chunked)
            throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, chunked ? 0 : body.length == 0 ? -1 : body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException ignored) {
            // the client gave up (timeout test)
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        queries.clear();
        items = List.of();
        mode = Mode.OK;
        usageFails = false;
        gate = null;
        arrived[0] = new CountDownLatch(1);
    }

    /** A SCORED row. */
    public static AssessmentItem item(long id, String tier, String chance, Instant scoredAt) {
        return new AssessmentItem(id, "Java Developer " + id, "Acme", "Remote", "https://example.com/jobs/" + id,
                "SCORED", tier, List.of(new GapItem("Kubernetes", "LEARNABLE"), new GapItem("Security clearance", "HARD")),
                chance, "Good fit " + id, scoredAt);
    }
}
