package com.gnemirko.jobapplier.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gnemirko.jobapplier.digest.dto.RunResult.Outcome;
import com.gnemirko.jobapplier.digest.service.DigestMailer;
import com.gnemirko.jobapplier.digest.service.DigestRunner;
import com.gnemirko.jobapplier.digest.service.DryRunMailer;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

/** Default mode: files are written, nothing is recorded and no SMTP server is contacted. */
@SpringBootTest(properties = {
        "digest.enabled=true",
        "digest.cron=-",
        "digest.smtp.host=127.0.0.1",
        "digest.smtp.from=jobs@example.com",
        "digest.smtp.to=me@example.com"
})
@Import(TestcontainersConfiguration.class)
class DigestDryRunTest {

    static final StubScoring STUB = new StubScoring();
    static final Path OUT = outDir();
    static final int PORT = DigestFlowTestBase.freePort();
    static final GreenMail MAIL = new GreenMail(new ServerSetup(PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    static {
        MAIL.start();
    }

    @Autowired DigestRunner runner;
    @Autowired DigestMailer mailer;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("digest.scoring.base-url", STUB::baseUrl);
        registry.add("digest.out-dir", OUT::toString);
        registry.add("digest.smtp.port", () -> PORT);
    }

    @BeforeEach
    void clean() throws Exception {
        STUB.reset();
        MAIL.purgeEmailFromAllMailboxes();
        try (Stream<Path> files = Files.list(OUT)) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    void writesFilesAndRecordsNothing() throws Exception {
        STUB.items = List.of(StubScoring.item(1, "STRONG", "LIKELY", java.time.Instant.now().minusSeconds(60)));

        var first = runner.runNow();
        var second = runner.runNow();

        assertThat(mailer).isInstanceOf(DryRunMailer.class);
        assertThat(first.outcome()).isEqualTo(Outcome.DRY_RUN);
        assertThat(second.outcome()).isEqualTo(Outcome.DRY_RUN);
        try (Stream<Path> files = Files.list(OUT)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertThat(names).hasSize(4).allMatch(n -> n.matches("digest-\\d{8}T\\d{9}Z(-\\d+)?\\.(html|txt)"));
        }
        assertThat(jdbc.queryForObject("select count(*) from digest.sent_assessment", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from digest.scan_cursor", Integer.class)).isZero();
        assertThat(MAIL.getReceivedMessages()).isEmpty();
    }

    @Test
    void nothingQualifyingWritesNoFile() throws Exception {
        assertThat(runner.runNow().outcome()).isEqualTo(Outcome.NOTHING_NEW);
        try (Stream<Path> files = Files.list(OUT)) {
            assertThat(files.count()).isZero();
        }
    }

    private static Path outDir() {
        try {
            return Files.createTempDirectory("digest-out-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
