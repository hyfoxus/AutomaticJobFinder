package com.gnemirko.jobapplier.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gnemirko.jobapplier.digest.dto.AssessmentItem;
import com.gnemirko.jobapplier.digest.service.DigestRunner;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

/** Digest enabled in smtp mode against a stub scoring API, GreenMail and a real Postgres. */
@SpringBootTest(properties = {
        "digest.enabled=true",
        "digest.mode=smtp",
        "digest.cron=-",
        "digest.smtp.host=127.0.0.1",
        "digest.smtp.from=jobs@example.com",
        "digest.smtp.to=me@example.com",
        "digest.scoring.read-timeout=1s",
        "digest.scoring.max-bytes=2000000"
})
@Import(TestcontainersConfiguration.class)
abstract class DigestFlowTestBase {

    static final StubScoring STUB = new StubScoring();
    static final int SMTP_PORT = freePort();
    static final GreenMail MAIL = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    static {
        MAIL.start();
    }

    @Autowired protected DigestRunner runner;
    @Autowired protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("digest.scoring.base-url", STUB::baseUrl);
        registry.add("digest.smtp.port", () -> SMTP_PORT);
    }

    @BeforeEach
    void cleanState() throws Exception {
        jdbc.execute("drop trigger if exists fail_insert on digest.sent_assessment");
        jdbc.execute("delete from digest.sent_assessment");
        jdbc.execute("delete from digest.scan_cursor");
        STUB.reset();
        MAIL.purgeEmailFromAllMailboxes();
        if (!MAIL.getSmtp().isRunning()) {
            MAIL.start();
        }
    }

    protected int sentRows() {
        return jdbc.queryForObject("select count(*) from digest.sent_assessment", Integer.class);
    }

    protected void assertCursor(Instant at, long afterId) {
        var rows = jdbc.queryForList("select scored_at, after_id from digest.scan_cursor");
        assertThat(rows).hasSize(1);
        assertThat(((java.sql.Timestamp) rows.get(0).get("scored_at")).toInstant()).isEqualTo(at);
        assertThat(((Number) rows.get(0).get("after_id")).longValue()).isEqualTo(afterId);
    }

    protected static Instant ago(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    }

    protected static List<AssessmentItem> strong(int count) {
        List<AssessmentItem> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            out.add(StubScoring.item(i, "STRONG", "POSSIBLE", ago(600).plusSeconds(i)));
        }
        return out;
    }

    /** All text of a mail (subject, text part, html part). */
    protected static String everything(MimeMessage m) throws Exception {
        StringBuilder sb = new StringBuilder(m.getSubject()).append('\n');
        collect(m, sb);
        return sb.toString();
    }

    protected static String part(MimeMessage m, String mime) throws Exception {
        StringBuilder sb = new StringBuilder();
        collect(m, sb, mime);
        return sb.toString();
    }

    private static void collect(Part p, StringBuilder sb) throws Exception {
        collect(p, sb, null);
    }

    private static void collect(Part p, StringBuilder sb, String only) throws Exception {
        Object content = p.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                collect(bp, sb, only);
            }
        } else if (content instanceof String s && (only == null || p.isMimeType(only))) {
            sb.append(s).append('\n');
        }
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
