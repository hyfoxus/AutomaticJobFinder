package com.gnemirko.jobapplier.digest.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EscapeTest {

    @Test
    void htmlEscapesMarkupAndQuotes() {
        assertThat(Escape.html("<a href=\"x\" onclick='y'>&</a>"))
                .isEqualTo("&lt;a href=&quot;x&quot; onclick=&#39;y&#39;&gt;&amp;&lt;/a&gt;");
    }

    @Test
    void controlsAndBidiOverridesAreDroppedAndLineBreaksBecomeSpaces() {
        assertThat(Escape.html("a\r\nb\tc\u0000d‮e⁦f")).isEqualTo("a  b cdef");
        assertThat(Escape.text("a\r\nBcc: x‮")).isEqualTo("a  Bcc: x");
        assertThat(Escape.text("a\nb")).doesNotContain("\n");
    }

    @Test
    void lineSeparatorsBecomeSpacesAndInvisibleDirectionMarksAreDropped() {
        assertThat(Escape.text("a\u2028b\u2029c")).isEqualTo("a b c");
        assertThat(Escape.html("a\u200Eb\u200Fc\u061Cd")).isEqualTo("abcd");
    }

    @Test
    void textIsNotEntityEscapedAndNullIsEmpty() {
        assertThat(Escape.text("<b>&")).isEqualTo("<b>&");
        assertThat(Escape.html(null)).isEmpty();
        assertThat(Escape.text(null)).isEmpty();
    }

    @Test
    void textIsCapped() {
        assertThat(Escape.html("x".repeat(1000))).hasSize(Escape.MAX_TEXT);
        assertThat(Escape.text("x".repeat(1000), 10)).hasSize(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/jobs/1", "http://example.com", "HTTPS://Example.com/x?a=b&c=d",
            " https://example.com/a "})
    void httpAndHttpsUrlsAreLinks(String url) {
        assertThat(Escape.safeUrl(url)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,x", "file:///etc/passwd",
            "mailto:a@b.c", "//example.com", "/relative", "https:///nohost", "https://exa mple.com", "https://a.com/x\ny",
            "https://a.com/‮", "https://user:pw@example.com/", "https://a.com/\"onmouseover=\"x", "", "  "})
    void everythingElseIsNotALink(String url) {
        assertThat(Escape.safeUrl(url)).isEmpty();
    }

    @Test
    void overlongOrNullUrlIsNotALink() {
        assertThat(Escape.safeUrl("https://example.com/" + "a".repeat(2100))).isEmpty();
        assertThat(Escape.safeUrl(null)).isEmpty();
    }
}
