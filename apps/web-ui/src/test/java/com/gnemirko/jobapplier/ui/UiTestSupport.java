package com.gnemirko.jobapplier.ui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;

/** Helpers shared by the page tests. */
final class UiTestSupport {

    static final String HOST = "localhost:8084";

    /** A cookie token and the same token as it sits in the form. */
    record Token(String cookie, String field) {
    }

    private UiTestSupport() {
    }

    static MockHttpServletRequestBuilder getPage(String uri) {
        return get(uri).header("Host", HOST);
    }

    static Token token(MockMvc mvc) throws Exception {
        MvcResult res = mvc.perform(getPage("/")).andReturn();
        String setCookie = res.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher c = Pattern.compile("ui_csrf=([^;]+)").matcher(setCookie);
        Matcher f = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(res.getResponse().getContentAsString());
        if (!c.find() || !f.find()) {
            throw new IllegalStateException("no token on the dashboard");
        }
        return new Token(c.group(1), f.group(1));
    }

    /** A run request that passes every check; tests then break one part. */
    static MockHttpServletRequestBuilder goodRun(Token t) {
        return post("/run").header("Host", HOST).header("Origin", "http://" + HOST)
                .cookie(new Cookie("ui_csrf", t.cookie())).param("_csrf", t.field());
    }

    static String job(String id, String title, String url, String reason) {
        return "{\"id\":" + id + ",\"title\":" + quote(title) + ",\"company\":\"Acme\",\"location\":\"Remote\","
                + "\"url\":" + quote(url) + ",\"status\":\"SCORED\",\"tier\":\"STRONG\",\"metMustHaves\":[1,2,3],"
                + "\"gaps\":[{\"text\":\"Kubernetes\",\"kind\":\"LEARNABLE\"},{\"text\":\"Security clearance\",\"kind\":\"HARD\"}],"
                + "\"interviewChance\":\"POSSIBLE\",\"reason\":" + quote(reason)
                + ",\"firstSeenAt\":\"2026-10-24T08:00:00Z\",\"scoredAt\":\"2026-10-25T09:30:00Z\"}";
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
