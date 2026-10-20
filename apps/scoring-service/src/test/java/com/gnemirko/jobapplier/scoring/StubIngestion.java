package com.gnemirko.jobapplier.scoring;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.gnemirko.jobapplier.contracts.JobRecord;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** A fake ingestion service on a local port: strict "firstSeenAt > since" like the real one, plus failure modes. */
public class StubIngestion {

    public enum Mode { OK, ERROR, BAD_JSON, SLOW, HUGE, HUGE_CHUNKED }

    private final HttpServer server;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public final List<String> sinces = new CopyOnWriteArrayList<>();
    public volatile List<JobRecord> jobs = List.of();
    public volatile Mode mode = Mode.OK;

    public StubIngestion() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/jobs", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            String since = query == null ? "1970-01-01T00:00:00Z"
                    : java.net.URLDecoder.decode(query.replace("since=", ""), StandardCharsets.UTF_8);
            sinces.add(since);
            Instant cut = Instant.parse(since);
            int status = 200;
            byte[] body;
            switch (mode) {
                case ERROR -> {
                    status = 500;
                    body = "boom".getBytes(StandardCharsets.UTF_8);
                }
                case BAD_JSON -> body = "<html>not json</html>".getBytes(StandardCharsets.UTF_8);
                case HUGE, HUGE_CHUNKED -> body = mapper.writeValueAsBytes(huge());
                case SLOW -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    body = mapper.writeValueAsBytes(jobs);
                }
                default -> body = mapper.writeValueAsBytes(
                        jobs.stream().filter(j -> j.firstSeenAt().isAfter(cut)).toList());
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(status, mode == Mode.HUGE_CHUNKED ? 0 : body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-ingestion");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** A valid job array far larger than any max-bytes the tests configure. */
    private static List<JobRecord> huge() {
        String text = "x".repeat(2000);
        List<JobRecord> out = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            out.add(new JobRecord(new com.gnemirko.jobapplier.contracts.JobPosting("h" + i, "test", "Java Developer",
                    "Acme", "Remote", "https://example.com/h" + i, text, null, null, null, null, null),
                    Instant.parse("2030-01-01T00:00:00Z").plusSeconds(i)));
        }
        return out;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        sinces.clear();
        jobs = List.of();
        mode = Mode.OK;
    }
}
