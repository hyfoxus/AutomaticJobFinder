package com.gnemirko.jobapplier.ui;

import static com.gnemirko.jobapplier.ui.UiTestSupport.getPage;
import static com.gnemirko.jobapplier.ui.UiTestSupport.job;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class PagesTest extends WebUiBase {

    @Autowired MockMvc mvc;

    private String body(String uri) throws Exception {
        return mvc.perform(getPage(uri)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void dashboardShowsUsageCountsAndTheRunButton() throws Exception {
        STUB.assessments.body = "[" + job("1", "Java Dev", "https://example.com/1", "ok") + ","
                + job("2", "Other", "https://example.com/2", "ok") + "]";
        mvc.perform(getPage("/")).andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Path=/"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Max-Age")),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Domain")),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Secure")))));
        String html = body("/");
        assertThat(html).contains("<strong>$0.120000</strong>").contains("<strong>$1.000000</strong>").contains("within budget")
                .contains("<span>7</span> model calls").contains("<span>2</span> read")
                .containsPattern("Scored, tier strong</td>\\s*<td>2</td>")
                .containsPattern("Scored, tier partial</td>\\s*<td>0</td>")
                .containsPattern("Scored</td>\\s*<td>2</td>").contains("action=\"/run\"").contains("name=\"_csrf\"")
                .doesNotContain("class=\"banner\"");
        assertThat(STUB.queries).anyMatch(q -> q.contains("limit=200") && q.contains("page=0"));
    }

    @Test
    void dashboardShows200PlusWhenTheRecentWindowIsFull() throws Exception {
        StringBuilder rows = new StringBuilder("[");
        for (int i = 1; i <= 200; i++) {
            rows.append(i > 1 ? "," : "").append(job(Integer.toString(i), "J" + i, "https://example.com/" + i, "ok"));
        }
        STUB.assessments.body = rows.append("]").toString();
        STUB.assessments.hasMore = "true";
        String html = body("/");
        assertThat(html).contains("<span>200+</span> read").containsPattern("Scored, tier strong</td>\\s*<td>200</td>");
    }

    @Test
    void chunkedOversizeAnswerGivesTheBanner() throws Exception {
        STUB.assessments.chunked = true;
        STUB.assessments.raw = ("[" + "{\"id\":1},".repeat(30_000) + "{\"id\":1}]").getBytes();
        assertThat(body("/assessments")).contains("not answering");
    }

    @Test
    void tokenIsKeptWhenTheCookieIsValid() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        var res = mvc.perform(getPage("/").cookie(new jakarta.servlet.http.Cookie("ui_csrf", t.cookie()))).andReturn();
        assertThat(res.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(res.getResponse().getContentAsString()).contains(t.field());
    }

    @Test
    void messagesComeFromAFixedList() throws Exception {
        assertThat(body("/?msg=started")).contains("A scoring run was started.");
        assertThat(body("/?msg=busy")).contains("already active");
        assertThat(body("/?msg=failed")).contains("could not be started");
        String evil = body("/?msg=%3Cscript%3Ealert(1)%3C/script%3E");
        assertThat(evil).doesNotContain("alert(1)").doesNotContain("<script").doesNotContain("A scoring run");
    }

    @Test
    void listDefaultsToScoredFiftyPerPageAndShowsEveryField() throws Exception {
        STUB.assessments.body = "[" + job("1", "Java Dev", "https://example.com/jobs/1?a=1&b=2", "Good fit.") + "]";
        String html = body("/assessments");
        assertThat(STUB.queries.getFirst()).contains("status=SCORED").contains("limit=50").contains("page=0")
                .doesNotContain("tier");
        assertThat(html).contains("Java Dev").contains("Acme").contains("Remote").contains("tier: strong")
                .contains("interview chance (heuristic): possible").contains("Good fit.")
                .contains("must-haves met: 3").contains("scored 2026-10-25 09:30 UTC")
                .contains("Hard gaps").contains("Security clearance").contains("Learnable gaps").contains("Kubernetes")
                .contains("href=\"https://example.com/jobs/1?a=1&amp;b=2\"")
                .contains("rel=\"noopener noreferrer\"").contains("referrerpolicy=\"no-referrer\"");
        assertThat(html.indexOf("Hard gaps")).isLessThan(html.indexOf("Learnable gaps"));
    }

    @Test
    void filtersAreCaseInsensitiveAndForwarded() throws Exception {
        body("/assessments?status=scored&tier=strong&page=1");
        assertThat(STUB.queries.getFirst()).contains("status=SCORED").contains("tier=STRONG").contains("page=1");
        STUB.queries.clear();
        body("/assessments?status=&tier=&page=");
        assertThat(STUB.queries.getFirst()).contains("status=SCORED").contains("page=0").doesNotContain("tier");
        body("/assessments?status=failed");
        assertThat(STUB.queries.getLast()).contains("status=FAILED");
    }

    @Test
    void previousAndNextLinksFollowTheMoreHeader() throws Exception {
        STUB.assessments.hasMore = "true";
        String html = body("/assessments?status=SCORED&tier=STRONG&page=1");
        assertThat(html).contains("href=\"/assessments?status=SCORED&amp;tier=STRONG&amp;page=0\"")
                .contains("href=\"/assessments?status=SCORED&amp;tier=STRONG&amp;page=2\"");
        STUB.assessments.hasMore = "false";
        assertThat(body("/assessments")).doesNotContain("Next").doesNotContain("Previous");
        STUB.assessments.hasMore = "true";
        assertThat(body("/assessments?page=199")).doesNotContain("Next"); // scoring's window ends there
    }

    @Test
    void hostileTextIsShownAsPlainTextAndGetsNoLink() throws Exception {
        STUB.assessments.body = "[" + job("1", "<script>alert(1)</script>", "javascript:alert(1)",
                "<img src=x onerror=alert(2)> \u202eevil") + ","
                + job("2", "Second", "JaVaScRiPt:alert(3)", "r") + ","
                + job("3", "Third", "https://user:pw@example.com/x", "r") + ","
                + job("4", "Fourth", "https://example.com/a\" onmouseover=\"x", "r") + ","
                + job("5", "Fifth", "data:text/html,<script>1</script>", "r") + "]";
        var res = mvc.perform(getPage("/assessments")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andReturn();
        String html = res.getResponse().getContentAsString();
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("&lt;img src=x onerror=alert(2)&gt; evil")
                .doesNotContain("<script").doesNotContain("<img").doesNotContain("href=\"javascript:")
                .doesNotContain("JaVaScRiPt").doesNotContain("user:pw").doesNotContain("onmouseover=\"x")
                .doesNotContain("data:text").doesNotContain("\u202e").doesNotContain("href=\"https://example.com/a");
        assertThat(html).doesNotContain("open the posting");
    }

    @Test
    void longAndMissingFieldsAreCappedOrFilled() throws Exception {
        String longReason = "r".repeat(2000);
        STUB.assessments.body = "[{\"id\":1,\"status\":\"SCORED\",\"tier\":\"HUGE\",\"interviewChance\":\"MAYBE\","
                + "\"title\":\"" + "t".repeat(1000) + "\",\"reason\":\"" + longReason + "\",\"gaps\":[{\"text\":null,\"kind\":\"X\"}],"
                + "\"unexpected\":1}]";
        String html = body("/assessments");
        assertThat(html).doesNotContain("t".repeat(301)).doesNotContain("r".repeat(501));
        assertThat(html).contains("tier: none").contains("interview chance (heuristic): unknown").contains("must-haves met: 0");
    }

    @Test
    void badParametersAre400WithAFixedPage() throws Exception {
        for (String q : new String[] {"status=nope", "tier=great", "page=200", "page=201", "page=-1", "page=abc", "page=99999999999",
                "status=FAILED&tier=STRONG", "status=SCORED&status=FAILED"}) {
            var res = mvc.perform(getPage("/assessments?" + q)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.notNullValue())).andReturn();
            assertThat(res.getResponse().getContentAsString()).contains("does not accept").doesNotContain(q.split("=")[1]);
        }
        assertThat(STUB.assessmentCalls.get()).isZero();
        String last = body("/assessments?page=199");
        assertThat(last).doesNotContain("class=\"banner\"");
        mvc.perform(getPage("/assessments?page=0")).andExpect(status().isOk());
    }

    @Test
    void scoringProblemsGiveAFixedBannerNeverTheErrorText() throws Exception {
        STUB.assessments.status = 500;
        STUB.assessments.body = "SECRET stack trace";
        STUB.usage.status = 500;
        STUB.usage.body = "SECRET usage";
        for (String uri : new String[] {"/", "/assessments"}) {
            String html = body(uri);
            assertThat(html).contains("class=\"banner\"").contains("not answering").doesNotContain("SECRET");
        }
        STUB.reset();
        STUB.assessments.body = "not json SECRET";
        assertThat(body("/assessments")).contains("not answering").doesNotContain("SECRET");
        STUB.reset();
        STUB.assessments.status = 302;
        STUB.assessments.location = "http://127.0.0.1:1/elsewhere";
        assertThat(body("/assessments")).contains("not answering");
        STUB.reset();
        STUB.assessments.raw = ("[" + "x".repeat(300_000) + "]").getBytes();
        assertThat(body("/assessments")).contains("not answering");
        STUB.reset();
        STUB.assessments.delayMs = 2500; // read timeout is 1 s
        assertThat(body("/assessments")).contains("not answering");
    }

    @Test
    void unknownPathAndWrongMethodAreFixedPagesWithHeaders() throws Exception {
        for (var r : new Object[][] {{getPage("/nothing"), 404}, {getPage("/run"), 405}}) {
            mvc.perform((org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) r[0])
                    .andExpect(status().is((Integer) r[1]))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
        }
    }

    @Test
    void healthAndStylesheetCarryTheHeaders() throws Exception {
        for (String uri : new String[] {"/actuator/health", "/ui.css", "/"}) {
            mvc.perform(getPage(uri)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("style-src 'self'")))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void foreignHostIsRefusedEverywhere() throws Exception {
        for (String uri : new String[] {"/", "/assessments", "/actuator/health", "/ui.css", "/%61ssessments", "/nothing"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri).header("Host", "evil.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.notNullValue()));
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").header("Host", "127.0.0.1:8084"))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/").header("Host", "LOCALHOST"))
                .andExpect(status().isOk());
        assertThat(STUB.assessmentCalls.get()).isEqualTo(2); // only the two allowed dashboards
    }
}
