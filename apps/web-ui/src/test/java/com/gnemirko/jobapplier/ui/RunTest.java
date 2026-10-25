package com.gnemirko.jobapplier.ui;

import static com.gnemirko.jobapplier.ui.UiTestSupport.HOST;
import static com.gnemirko.jobapplier.ui.UiTestSupport.goodRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

class RunTest extends WebUiBase {

    @Autowired MockMvc mvc;

    private void expectRedirect(UiTestSupport.Token t, String msg) throws Exception {
        mvc.perform(goodRun(t)).andExpect(status().isSeeOther()).andExpect(header().string("Location", "/?msg=" + msg))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.notNullValue()));
    }

    @Test
    void validRunCallsScoringOnceWithTheMarkerAndRedirects() throws Exception {
        expectRedirect(UiTestSupport.token(mvc), "started");
        assertThat(STUB.runCalls.get()).isEqualTo(1);
        assertThat(STUB.lastRunMarker).isEqualTo("1");
    }

    @Test
    void scoringBusyGivesBusy() throws Exception {
        STUB.run.status = 409;
        expectRedirect(UiTestSupport.token(mvc), "busy");
    }

    @Test
    void anyOtherAnswerGivesFailedAndIsNeverRetried() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        for (int code : new int[] {400, 404, 500, 503}) {
            STUB.reset();
            STUB.run.status = code;
            expectRedirect(t, "failed");
            assertThat(STUB.runCalls.get()).as("calls for " + code).isEqualTo(1);
        }
        STUB.reset();
        STUB.run.status = 302;
        STUB.run.location = "http://127.0.0.1:1/x";
        expectRedirect(t, "failed");
        STUB.reset();
        STUB.run.delayMs = 2500;
        expectRedirect(t, "failed");
        assertThat(STUB.runCalls.get()).isEqualTo(1);
    }

    @Test
    void requestWithoutAValidTokenIsRefusedAndScoringIsNotCalled() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        String origin = "http://" + HOST;
        // no cookie, no field, wrong field, field of another length, foreign signed-looking cookie
        mvc.perform(post("/run").header("Host", HOST).header("Origin", origin)).andExpect(status().isForbidden());
        mvc.perform(post("/run").header("Host", HOST).header("Origin", origin).cookie(new Cookie("ui_csrf", t.cookie())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/run").header("Host", HOST).header("Origin", origin).param("_csrf", t.field()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/run").header("Host", HOST).header("Origin", origin).cookie(new Cookie("ui_csrf", t.cookie()))
                .param("_csrf", "other")).andExpect(status().isForbidden());
        mvc.perform(post("/run").header("Host", HOST).header("Origin", origin)
                .cookie(new Cookie("ui_csrf", t.cookie())).param("_csrf", "x".repeat(t.field().length())))
                .andExpect(status().isForbidden());
        // an injected cookie that the UI did not sign, with a matching field
        mvc.perform(post("/run").header("Host", HOST).header("Origin", origin)
                .cookie(new Cookie("ui_csrf", "AAAA.BBBB")).param("_csrf", "AAAA.BBBB")).andExpect(status().isForbidden());
        mvc.perform(post("/run").header("Host", HOST).header("Origin", origin)
                .cookie(new Cookie("ui_csrf", "attacker")).param("_csrf", "attacker")).andExpect(status().isForbidden());
        assertThat(STUB.runCalls.get()).isZero();
    }

    @Test
    void injectedCookieIsReplacedOnTheNextVisit() throws Exception {
        var res = mvc.perform(UiTestSupport.getPage("/").cookie(new Cookie("ui_csrf", "attacker"))).andReturn();
        assertThat(res.getResponse().getHeader("Set-Cookie")).startsWith("ui_csrf=").doesNotContain("attacker");
    }

    @Test
    void originMustBePresentAndEqualTheHost() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        var base = post("/run").header("Host", HOST).cookie(new Cookie("ui_csrf", t.cookie())).param("_csrf", t.field());
        mvc.perform(base).andExpect(status().isForbidden()); // no Origin, no Sec-Fetch-Site
        mvc.perform(post("/run").header("Host", HOST).header("Origin", "null").cookie(new Cookie("ui_csrf", t.cookie()))
                .param("_csrf", t.field())).andExpect(status().isForbidden());
        for (String origin : new String[] {"http://evil.example", "http://localhost:9999", "http://localhost",
                "https://" + HOST, "http://127.0.0.1:8084", "http://" + HOST + ".evil.example"}) {
            mvc.perform(post("/run").header("Host", HOST).header("Origin", origin).cookie(new Cookie("ui_csrf", t.cookie()))
                    .param("_csrf", t.field())).andExpect(status().isForbidden());
        }
        mvc.perform(post("/run").header("Host", HOST).header("Sec-Fetch-Site", "cross-site")
                .cookie(new Cookie("ui_csrf", t.cookie())).param("_csrf", t.field())).andExpect(status().isForbidden());
        assertThat(STUB.runCalls.get()).isZero();
        // Without an Origin, same-origin fetch metadata is accepted.
        mvc.perform(post("/run").header("Host", HOST).header("Sec-Fetch-Site", "same-origin")
                .cookie(new Cookie("ui_csrf", t.cookie())).param("_csrf", t.field())).andExpect(status().isSeeOther());
        assertThat(STUB.runCalls.get()).isEqualTo(1);
    }

    @Test
    void badHostIsRefusedOnThePost() throws Exception {
        UiTestSupport.Token t = UiTestSupport.token(mvc);
        mvc.perform(post("/run").header("Host", "evil.example").header("Origin", "http://evil.example")
                .cookie(new Cookie("ui_csrf", t.cookie())).param("_csrf", t.field())).andExpect(status().isForbidden());
        assertThat(STUB.runCalls.get()).isZero();
    }
}
