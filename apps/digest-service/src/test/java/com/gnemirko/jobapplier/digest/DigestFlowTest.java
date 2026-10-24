package com.gnemirko.jobapplier.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.digest.dto.AssessmentItem;
import com.gnemirko.jobapplier.digest.dto.RunResult;
import com.gnemirko.jobapplier.digest.dto.RunResult.Outcome;

import jakarta.mail.internet.MimeMessage;

class DigestFlowTest extends DigestFlowTestBase {

    @Test
    void threeMatchesGoOutOnceAndAreRecorded() throws Exception {
        STUB.items = List.of(
                StubScoring.item(1, "PARTIAL", "LIKELY", ago(30)),
                StubScoring.item(2, "STRONG", "UNLIKELY", ago(20)),
                StubScoring.item(3, "STRONG", "LIKELY", ago(10)));

        RunResult first = runner.runNow();
        RunResult second = runner.runNow();

        assertThat(first.outcome()).isEqualTo(Outcome.SENT);
        assertThat(first.included()).isEqualTo(3);
        assertThat(second.outcome()).isEqualTo(Outcome.NOTHING_NEW);
        MimeMessage[] mails = MAIL.getReceivedMessages();
        assertThat(mails).hasSize(1);
        assertThat(mails[0].getSubject()).isEqualTo("JobApplier digest: 3 new jobs (2 strong, 1 partial, 0 weak)");
        String html = part(mails[0], "text/html");
        // STRONG first, then by interview chance (LIKELY before UNLIKELY), then the PARTIAL one.
        assertThat(html.indexOf("Java Developer 3")).isLessThan(html.indexOf("Java Developer 2"));
        assertThat(html.indexOf("Java Developer 2")).isLessThan(html.indexOf("Java Developer 1"));
        assertThat(html).contains("heuristic").contains("Hard gaps").contains("Learnable gaps")
                .contains("Scoring spend today");
        assertThat(sentRows()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(distinct digest_id) from digest.sent_assessment", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void nothingQualifyingSendsNothing() throws Exception {
        STUB.items = List.of(StubScoring.item(1, "WEAK", "UNLIKELY", ago(5)));

        assertThat(runner.runNow().outcome()).isEqualTo(Outcome.NOTHING_NEW);

        assertThat(MAIL.getReceivedMessages()).isEmpty();
        assertThat(sentRows()).isZero();
    }

    @Test
    void seventyQualifyFiftyGoNowAndTheRestNextTick() throws Exception {
        STUB.items = strong(70);

        RunResult one = runner.runNow();
        RunResult two = runner.runNow();
        RunResult three = runner.runNow();

        assertThat(one.included()).isEqualTo(50);
        assertThat(one.waiting()).isEqualTo(20);
        assertThat(two.outcome()).isEqualTo(Outcome.SENT);
        assertThat(two.included()).isEqualTo(20);
        assertThat(three.outcome()).isEqualTo(Outcome.NOTHING_NEW);
        assertThat(MAIL.getReceivedMessages()).hasSize(2);
        assertThat(sentRows()).isEqualTo(70);
        assertThat(jdbc.queryForObject("select count(distinct assessment_id) from digest.sent_assessment", Integer.class))
                .isEqualTo(70);
    }

    @Test
    void manyRowsOutsideTheTiersDoNotBlockTheScan() throws Exception {
        List<AssessmentItem> all = new ArrayList<>();
        for (int i = 1; i <= 250; i++) {
            all.add(StubScoring.item(i, "WEAK", "UNLIKELY", ago(900).plusSeconds(i)));
        }
        var last = ago(9);
        all.add(StubScoring.item(251, "STRONG", "LIKELY", ago(10)));
        all.add(StubScoring.item(252, "PARTIAL", "POSSIBLE", last));
        STUB.items = all;

        RunResult r = runner.runNow();

        assertThat(r.included()).isEqualTo(2);
        assertThat(sentRows()).isEqualTo(2);
        assertCursor(last.minus(5, java.time.temporal.ChronoUnit.MINUTES), 0);
    }

    @Test
    void equalScoredAtAcrossAPageEdgeLosesNothing() throws Exception {
        var same = ago(60);
        List<AssessmentItem> all = new ArrayList<>();
        for (int i = 1; i <= 230; i++) {
            all.add(StubScoring.item(i, "WEAK", "UNLIKELY", same));
        }
        for (int i = 231; i <= 233; i++) {
            all.add(StubScoring.item(i, "STRONG", "LIKELY", same));
        }
        STUB.items = all;

        assertThat(runner.runNow().included()).isEqualTo(3);
        assertCursor(same.minus(5, java.time.temporal.ChronoUnit.MINUTES), 0);
    }

    @Test
    void oldestLeftoverSharingItsInstantWithSentRowsOfLowerIdsLosesNothing() throws Exception {
        var t = ago(600);
        List<AssessmentItem> all = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            all.add(StubScoring.item(i, "STRONG", "LIKELY", t));
        }
        for (int i = 11; i <= 70; i++) {
            all.add(StubScoring.item(i, "STRONG", "POSSIBLE", t));
        }
        STUB.items = all;

        RunResult one = runner.runNow();

        assertThat(one.included()).isEqualTo(50);
        assertThat(one.waiting()).isEqualTo(20);
        assertCursor(t.minus(5, java.time.temporal.ChronoUnit.MINUTES), 0);
        RunResult two = runner.runNow();
        assertThat(two.included()).isEqualTo(20);
        assertThat(sentRows()).isEqualTo(70);
    }

    @Test
    void leftoverOlderThanTheOverlapWindowHoldsTheCursorBack() throws Exception {
        var base = ago(900);
        List<AssessmentItem> all = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            all.add(StubScoring.item(i, "STRONG", "POSSIBLE", base.plus(i, java.time.temporal.ChronoUnit.MINUTES)));
        }
        STUB.items = all;

        RunResult one = runner.runNow();

        assertThat(one.waiting()).isEqualTo(10);
        // The oldest leftover is id 1; the cursor sits just before it.
        assertCursor(base.plus(1, java.time.temporal.ChronoUnit.MINUTES), 0);
        assertThat(runner.runNow().included()).isEqualTo(10);
        assertThat(sentRows()).isEqualTo(60);
    }

    @Test
    void leftoverInsideTheLastOverlapWindowStillKeepsTheOverlap() throws Exception {
        var base = ago(4);
        List<AssessmentItem> all = new ArrayList<>();
        for (int i = 1; i <= 55; i++) {
            all.add(StubScoring.item(i, "STRONG", "POSSIBLE", base.plusSeconds(i)));
        }
        STUB.items = all;

        RunResult one = runner.runNow();

        assertThat(one.waiting()).isEqualTo(5);
        assertCursor(base.plusSeconds(55).minus(5, java.time.temporal.ChronoUnit.MINUTES), 0);
        assertThat(runner.runNow().included()).isEqualTo(5);
        assertThat(sentRows()).isEqualTo(55);
        assertThat(MAIL.getReceivedMessages()).hasSize(2);
    }

    @Test
    void displayOrderPutsUnknownChanceLastAndNewestFirstOnTies() throws Exception {
        STUB.items = List.of(
                StubScoring.item(1, "STRONG", "LIKELY", ago(50)),
                StubScoring.item(2, "STRONG", "POSSIBLE", ago(40)),
                StubScoring.item(3, "STRONG", "POSSIBLE", ago(30)),
                StubScoring.item(4, "STRONG", null, ago(20)),
                StubScoring.item(5, "PARTIAL", "LIKELY", ago(10)));

        runner.runNow();

        String html = part(MAIL.getReceivedMessages()[0], "text/html");
        int[] at = new int[6];
        for (int i = 1; i <= 5; i++) {
            at[i] = html.indexOf("Java Developer " + i + "</a>");
            assertThat(at[i]).isPositive();
        }
        assertThat(at[1]).isLessThan(at[3]);
        assertThat(at[3]).isLessThan(at[2]);
        assertThat(at[2]).isLessThan(at[4]);
        assertThat(at[4]).isLessThan(at[5]);
    }

    @Test
    void smtpDownRecordsNothingAndTheNextTickRetries() throws Exception {
        STUB.items = strong(3);
        MAIL.stop();

        RunResult down = runner.runNow();

        assertThat(down.outcome()).isEqualTo(Outcome.DELIVERY_FAILED);
        assertThat(sentRows()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from digest.scan_cursor", Integer.class)).isZero();
        MAIL.start();
        assertThat(runner.runNow().outcome()).isEqualTo(Outcome.SENT);
        assertThat(MAIL.getReceivedMessages()).hasSize(1);
        assertThat(sentRows()).isEqualTo(3);
    }

    @Test
    void scoringApiFailuresSendNothingAndRecordNothing() throws Exception {
        STUB.items = strong(3);
        for (StubScoring.Mode mode : List.of(StubScoring.Mode.ERROR, StubScoring.Mode.BAD_JSON,
                StubScoring.Mode.HUGE, StubScoring.Mode.HUGE_CHUNKED, StubScoring.Mode.REDIRECT, StubScoring.Mode.SLOW)) {
            STUB.mode = mode;
            assertThat(runner.runNow().outcome()).as(mode.name()).isEqualTo(Outcome.SCORING_UNAVAILABLE);
        }
        assertThat(MAIL.getReceivedMessages()).isEmpty();
        assertThat(sentRows()).isZero();

        STUB.mode = StubScoring.Mode.OK;
        assertThat(runner.runNow().outcome()).isEqualTo(Outcome.SENT);
    }

    @Test
    void hostileTextArrivesOnlyAsEscapedText() throws Exception {
        AssessmentItem evil = new AssessmentItem(1L, "<script>alert(1)</script>\r\nBcc: x@evil.example",
                "<img src=http://evil.example/p.png>", "Loc‮ation", "javascript:alert(1)", "SCORED", "STRONG",
                List.of(new com.gnemirko.jobapplier.digest.dto.GapItem("<b>hard</b>", "HARD")), "LIKELY",
                "Reason </td><script>x()</script>\r\nSubject: hijack", ago(5));
        STUB.items = List.of(evil);

        assertThat(runner.runNow().outcome()).isEqualTo(Outcome.SENT);

        MimeMessage mail = MAIL.getReceivedMessages()[0];
        assertThat(mail.getSubject()).isEqualTo("JobApplier digest: 1 new jobs (1 strong, 0 partial, 0 weak)");
        assertThat(mail.getHeader("Bcc")).isNull();
        String html = part(mail, "text/html");
        assertThat(html).doesNotContain("<script").doesNotContain("<img").doesNotContain("<b>")
                .doesNotContain("javascript:").doesNotContain("<a ")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        String text = part(mail, "text/plain");
        assertThat(text).doesNotContain("javascript:").doesNotContain("‮");
        assertThat(text.lines().filter(l -> l.startsWith("Bcc:") || l.startsWith("Subject:"))).isEmpty();
    }

    @Test
    void usageLineIsOmittedWhenTheCallFails() throws Exception {
        STUB.items = strong(1);
        STUB.usageFails = true;

        assertThat(runner.runNow().outcome()).isEqualTo(Outcome.SENT);

        assertThat(everything(MAIL.getReceivedMessages()[0])).doesNotContain("Scoring spend");
    }

    @Test
    void mailAcceptedButRecordFailingResendsLaterAndLosesNothing() throws Exception {
        STUB.items = strong(3);
        jdbc.execute("create or replace function digest.refuse() returns trigger language plpgsql as "
                + "$$ begin raise exception 'refused'; end $$");
        jdbc.execute("create trigger fail_insert before insert on digest.sent_assessment "
                + "for each row execute function digest.refuse()");

        RunResult broken = runner.runNow();

        assertThat(broken.outcome()).isEqualTo(Outcome.RECORD_FAILED);
        assertThat(MAIL.getReceivedMessages()).hasSize(1);
        assertThat(sentRows()).isZero();
        jdbc.execute("drop trigger fail_insert on digest.sent_assessment");

        assertThat(runner.runNow().outcome()).isEqualTo(Outcome.SENT);
        assertThat(MAIL.getReceivedMessages()).hasSize(2);
        assertThat(sentRows()).isEqualTo(3);
    }

    @Test
    void onlyOneRunAtATime() throws Exception {
        STUB.items = strong(1);
        STUB.gate = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<RunResult> first = pool.submit(runner::runNow);
            assertThat(STUB.arrived[0].await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(runner.runNow().outcome()).isEqualTo(Outcome.BUSY);

            STUB.gate.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.SENT);
        } finally {
            STUB.gate.countDown();
            pool.shutdownNow();
        }
        assertThat(MAIL.getReceivedMessages()).hasSize(1);
    }
}
