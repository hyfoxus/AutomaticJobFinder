package com.gnemirko.jobapplier.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.gnemirko.jobapplier.ingestion.service.IngestionService;
import com.gnemirko.jobapplier.ingestion.source.RemotiveJobSource;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity;
import com.gnemirko.jobapplier.ingestion.entity.IngestionRunEntity.Status;
import com.gnemirko.jobapplier.ingestion.repository.IngestionRunRepository;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;
import com.gnemirko.jobapplier.ingestion.repository.JobRepository;
import com.sun.net.httpserver.HttpServer;

/** Real Postgres (Testcontainers) and a local HTTP server replaying recorded Remotive fixtures. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IngestionFlowTest {

    private static volatile int httpStatus = 200;
    private static volatile String body = "{}";
    private static volatile String lastQuery;
    private static final HttpServer SERVER = startServer();

    private static HttpServer startServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                lastQuery = ex.getRequestURI().getRawQuery();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(httpStatus, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("ingestion.remotive.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/api");
        r.add("ingestion.remotive.timeout", () -> "3s");
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired IngestionService runner;
    @Autowired RemotiveJobSource source;
    @Autowired JobRepository jobs;
    @Autowired IngestionRunRepository runs;
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        jobs.deleteAll();
        runs.deleteAll();
        httpStatus = 200;
        lastQuery = null;
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures", name));
    }

    private IngestionRunEntity run() {
        return runner.runSource(source);
    }

    @Test
    void firstFetchStoresAllValidJobsAndCountsMalformed() throws Exception {
        body = fixture("remotive-3.json");
        IngestionRunEntity r = run();

        assertThat(jobs.count()).isEqualTo(3);
        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(r.getInserted()).isEqualTo(3);
        assertThat(r.getSkipped()).isEqualTo(1);
    }

    @Test
    void repeatFetchAddsNothingAndUpdatesLastSeen() throws Exception {
        body = fixture("remotive-3.json");
        run();
        JobEntity before = jobs.findBySourceAndExternalId("remotive", "2").orElseThrow();
        run();
        JobEntity after = jobs.findBySourceAndExternalId("remotive", "2").orElseThrow();

        assertThat(jobs.count()).isEqualTo(3);
        assertThat(after.getFirstSeenAt()).isEqualTo(before.getFirstSeenAt());
        assertThat(after.getLastSeenAt()).isAfter(before.getLastSeenAt());
        assertThat(runs.findBySourceOrderByIdAsc("remotive").get(1).getInserted()).isZero();
    }

    @Test
    void partialNewStoresOnlyTheNewOnes() throws Exception {
        body = fixture("remotive-3.json");
        run();
        body = fixture("remotive-5.json");
        IngestionRunEntity r = run();

        assertThat(jobs.count()).isEqualTo(5);
        assertThat(r.getInserted()).isEqualTo(2);
        assertThat(r.getUpdated()).isEqualTo(3);
    }

    @Test
    void sourceDownRecordsFailureAndChangesNothing() throws Exception {
        body = fixture("remotive-3.json");
        run();
        List<Snap> snapshot = jobs.findAll().stream()
                .map(j -> new Snap(j.getExternalId(), j.getLastSeenAt().toString())).toList();

        httpStatus = 503;
        IngestionRunEntity r = run();

        assertThat(r.getStatus()).isEqualTo(Status.FAILED);
        assertThat(r.getError()).isNotBlank();
        assertThat(jobs.findAll().stream()
                .map(j -> new Snap(j.getExternalId(), j.getLastSeenAt().toString())).toList())
                .containsExactlyInAnyOrderElementsOf(snapshot);

        httpStatus = 200;
        assertThat(run().getStatus()).isEqualTo(Status.SUCCESS);
    }

    @Test
    void overLongFieldIsSkippedWithoutFailingTheBatch() throws Exception {
        String longTitle = "x".repeat(600);
        body = fixture("remotive-3.json").replace("Java Developer 1\"", longTitle + "\"");
        IngestionRunEntity r = run();

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(jobs.count()).isEqualTo(2);
        assertThat(jobs.findBySourceAndExternalId("remotive", "1")).isEmpty();
        assertThat(r.getSkipped()).isEqualTo(2); // malformed + over-long
    }

    @Test
    void apiReturnsEachJobOnceWithFirstSeenAt() throws Exception {
        body = fixture("remotive-3.json");
        run();
        run();

        mvc.perform(get("/api/jobs").param("since", "1970-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].posting.externalId").value("1"))
                .andExpect(jsonPath("$[0].posting.source").value("remotive"))
                .andExpect(jsonPath("$[0].firstSeenAt").isNotEmpty());
    }

    @Test
    void sinceCursorIsStrict() throws Exception {
        body = fixture("remotive-3.json");
        run();
        var max = jobs.findAll().stream().map(JobEntity::getFirstSeenAt).max(java.util.Comparator.naturalOrder()).orElseThrow();

        mvc.perform(get("/api/jobs").param("since", max.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private static String stubUrl() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/api";
    }

    @Test
    void defaultRequestCarriesCategoryOnly() throws Exception {
        body = fixture("remotive-3.json");
        run();

        assertThat(lastQuery).isEqualTo("category=software-dev");
    }

    @Test
    void searchIsSentAlongsideCategory() throws Exception {
        body = fixture("remotive-3.json");
        new RemotiveJobSource(RestClient.builder(), stubUrl(), java.time.Duration.ofSeconds(3),
                "software-dev", "java").fetch();

        assertThat(lastQuery).contains("category=software-dev").contains("search=java");
    }

    @Test
    void specialCharactersInSearchAreEncodedNotParsedAsTemplates() throws Exception {
        body = fixture("remotive-3.json");
        new RemotiveJobSource(RestClient.builder(), stubUrl(), java.time.Duration.ofSeconds(3),
                "software-dev", "c++ {x}").fetch();

        assertThat(lastQuery).contains("search=c%2B%2B%20%7Bx%7D");
    }

    @Test
    void blankSettingsSendNoQuery() throws Exception {
        body = fixture("remotive-3.json");
        new RemotiveJobSource(RestClient.builder(), stubUrl(), java.time.Duration.ofSeconds(3), "", " ").fetch();

        assertThat(lastQuery).isNull();
    }

    @Test
    void extraFieldsAreStoredAndReturnedOrNull() throws Exception {
        body = fixture("remotive-3.json");
        run();

        mvc.perform(get("/api/jobs").param("since", "1970-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].posting.category").value("Software Development"))
                .andExpect(jsonPath("$[0].posting.jobType").value("full_time"))
                .andExpect(jsonPath("$[0].posting.salary").value("$100k - $120k"))
                .andExpect(jsonPath("$[2].posting.category").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[2].posting.jobType").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[2].posting.salary").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void overLongSalaryIsSkippedWithoutFailingTheBatch() throws Exception {
        body = fixture("remotive-3.json").replace("$100k - $120k", "9".repeat(300));
        IngestionRunEntity r = run();

        assertThat(r.getStatus()).isEqualTo(Status.SUCCESS);
        assertThat(jobs.count()).isEqualTo(2);
        assertThat(jobs.findBySourceAndExternalId("remotive", "1")).isEmpty();
        assertThat(r.getSkipped()).isEqualTo(2);
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private record Snap(String id, String lastSeen) {
    }
}
