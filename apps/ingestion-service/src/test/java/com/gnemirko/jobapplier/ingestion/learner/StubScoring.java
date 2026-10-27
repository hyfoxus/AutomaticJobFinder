package com.gnemirko.jobapplier.ingestion.learner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stands in for scoring's /api/llm/complete: scripted answers, recorded requests. */
public final class StubScoring implements AutoCloseable {

    public record Seen(String token, String run, String body) {
        public JsonNode json() {
            return new JsonMapper().readTree(body);
        }
    }

    private record Answer(int status, String body, long delayMillis) {
    }

    private final HttpServer server;
    public final int port;
    public final List<Seen> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
    public volatile CountDownLatch entered = new CountDownLatch(1);
    public volatile CountDownLatch release;

    public StubScoring() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        port = server.getAddress().getPort();
        server.createContext("/api/llm/complete", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Seen(ex.getRequestHeaders().getFirst("X-Learner-Token"),
                    ex.getRequestHeaders().getFirst("X-JobApplier-Run"), body));
            entered.countDown();
            CountDownLatch gate = release;
            if (gate != null) {
                try {
                    gate.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            Answer a = answers.poll();
            if (a == null) {
                a = new Answer(500, "{\"error\":\"no answer scripted\"}", 0);
            }
            if (a.delayMillis() > 0) {
                try {
                    Thread.sleep(a.delayMillis());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = a.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(a.status(), bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            }
            ex.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** The model answers {@code text} and stopped normally. */
    public StubScoring reply(String text) {
        return replyRaw(200, reply200(text, "STOP"));
    }

    public StubScoring replyCut(String text) {
        return replyRaw(200, reply200(text, "LENGTH"));
    }

    public StubScoring replyRaw(int status, String body) {
        answers.add(new Answer(status, body, 0));
        return this;
    }

    public StubScoring replySlow(String text, long millis) {
        answers.add(new Answer(200, reply200(text, "STOP"), millis));
        return this;
    }

    private static String reply200(String text, String finish) {
        return new JsonMapper().writeValueAsString(
                java.util.Map.of("text", text, "finishReason", finish, "inputTokens", 100, "outputTokens", 50));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
