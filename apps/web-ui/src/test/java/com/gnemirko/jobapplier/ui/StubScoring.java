package com.gnemirko.jobapplier.ui;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/** A scoring service on a loopback port, answering what the test sets. Nothing leaves the machine. */
public class StubScoring implements AutoCloseable {

    /** One canned answer. */
    public static final class Answer {
        public int status = 200;
        public String body = "[]";
        public String hasMore;
        public String location;
        public long delayMs;
        public byte[] raw;
        /** Answer without a Content-Length (chunked). */
        public boolean chunked;
        /** Send the headers and this many bytes of the body, then sleep for stallMs. 0 = off. */
        public int stallAfterBytes;
        public long stallMs;
    }

    public final Answer assessments = new Answer();
    public final Answer usage = new Answer();
    public final Answer run = new Answer();
    public final AtomicInteger runCalls = new AtomicInteger();
    public final AtomicInteger assessmentCalls = new AtomicInteger();
    public final List<String> queries = new CopyOnWriteArrayList<>();
    public volatile String lastRunMarker;

    private final HttpServer server;

    public StubScoring() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/assessments", ex -> {
            assessmentCalls.incrementAndGet();
            queries.add(ex.getRequestURI().getRawQuery());
            reply(ex, assessments);
        });
        server.createContext("/api/usage/today", ex -> reply(ex, usage));
        server.createContext("/api/scoring/run", ex -> {
            runCalls.incrementAndGet();
            lastRunMarker = ex.getRequestHeaders().getFirst("X-JobApplier-Run");
            reply(ex, run);
        });
        // One thread per request: a slow answer must not hold up the next test.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.start();
        reset();
    }

    public void reset() {
        assessments.status = 200;
        assessments.body = "[]";
        assessments.hasMore = null;
        assessments.location = null;
        assessments.delayMs = 0;
        assessments.raw = null;
        assessments.chunked = false;
        assessments.stallAfterBytes = 0;
        assessments.stallMs = 0;
        usage.status = 200;
        usage.body = "{\"day\":\"2026-10-25\",\"spentUsd\":\"0.120000\",\"capUsd\":\"1.000000\",\"budgetState\":\"OK\",\"calls\":7}";
        usage.location = null;
        usage.delayMs = 0;
        usage.raw = null;
        run.status = 202;
        run.body = "{\"status\":\"started\"}";
        run.location = null;
        run.delayMs = 0;
        run.raw = null;
        runCalls.set(0);
        assessmentCalls.set(0);
        queries.clear();
        lastRunMarker = null;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, Answer a) throws IOException {
        try (ex) {
            if (a.delayMs > 0) {
                try {
                    Thread.sleep(a.delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = a.raw != null ? a.raw : a.body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (a.hasMore != null) {
                ex.getResponseHeaders().add("X-Has-More", a.hasMore);
            }
            if (a.location != null) {
                ex.getResponseHeaders().add("Location", a.location);
            }
            if (a.stallAfterBytes > 0) {
                ex.sendResponseHeaders(a.status, 0);
                ex.getResponseBody().write(bytes, 0, Math.min(a.stallAfterBytes, bytes.length));
                ex.getResponseBody().flush();
                try {
                    Thread.sleep(a.stallMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            if (a.chunked) {
                ex.sendResponseHeaders(a.status, 0);
            } else {
                ex.sendResponseHeaders(a.status, bytes.length == 0 ? -1 : bytes.length);
            }
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
        } catch (IOException ignored) {
            // the client gave up (timeout test)
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
