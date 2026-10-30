package com.gnemirko.jobapplier.ingestion.learner.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.gnemirko.jobapplier.ingestion.TestcontainersConfiguration;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** SampleRunner.run with a json-api recipe against a stub endpoint: at most 2 pages and 20 items. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "ingestion.fetch.allow-insecure-hosts=127.0.0.1,localhost",
        "ingestion.fetch.min-delay=0ms",
        "ingestion.recipes.dir=./does-not-exist"})
class SampleRunnerJsonApiRunTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @Autowired PoliteFetcher fetcher;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            String body;
            if (path.equals("/robots.txt")) {
                body = "User-agent: *\nAllow: /\n";
            } else {
                String q = ex.getRequestURI().getRawQuery();
                queries.add(q);
                int page = Integer.parseInt(q.replaceAll(".*page=(\\d+).*", "$1"));
                int size = Integer.parseInt(q.replaceAll(".*size=(\\d+).*", "$1"));
                StringBuilder sb = new StringBuilder("{\"items\":[");
                for (int i = 0; i < size; i++) {
                    int id = (page - 1) * size + i;
                    sb.append(i > 0 ? "," : "").append("{\"id\":\"J").append(id).append("\",\"title\":\"Job ").append(id)
                            .append("\",\"link\":\"/job/J").append(id).append("\"}");
                }
                body = sb.append("]}").toString();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", path.equals("/robots.txt") ? "text/plain" : "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SampleRunner.Sample run(int size) {
        Recipe.JsonApi api = new Recipe.JsonApi("https://api.example.com/api/jobs", "GET",
                Map.of("page", "${page}", "size", "${size}"), null, new Recipe.Paging("page", 1, size, 30, null), "items",
                Map.of("id", MAPPER.readTree("\"id\""), "title", MAPPER.readTree("\"title\""), "url", MAPPER.readTree("\"link\"")),
                null, 900);
        Recipe recipe = new Recipe("api-x", true, Recipe.STRATEGY_JSON_API, null, null, null, null, new Recipe.Limits(50), api);
        return new SampleRunner(fetcher, Clock.systemUTC(), Duration.ofMinutes(2),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())).run(recipe);
    }

    @Test
    void theSampleStopsAfterTwoPages() {
        SampleRunner.Sample s = run(5);

        assertThat(queries).hasSize(2);
        assertThat(s.ran()).isTrue();
        assertThat(s.passed()).isTrue();
        assertThat(s.good()).isEqualTo(10);
    }

    @Test
    void theSampleStopsAtTwentyItems() {
        SampleRunner.Sample s = run(15);

        assertThat(queries).hasSize(2);
        assertThat(s.good()).isEqualTo(20);
    }
}
