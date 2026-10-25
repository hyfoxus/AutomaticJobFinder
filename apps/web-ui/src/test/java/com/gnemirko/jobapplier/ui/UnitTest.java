package com.gnemirko.jobapplier.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.ui.config.UiProperties;
import com.gnemirko.jobapplier.ui.exception.UiConfigurationException;
import com.gnemirko.jobapplier.ui.util.Escape;
import com.gnemirko.jobapplier.ui.util.SafeUrl;

class UnitTest {

    private static UiProperties hosts(String... h) {
        return new UiProperties(true, List.of(h), Duration.ZERO, new UiProperties.Scoring("http://x", Duration.ofSeconds(1), Duration.ofSeconds(1), 1));
    }

    @Test
    void hostListIsValidatedLikeScorings() {
        assertThat(hosts(" LocalHost ", "127.0.0.1").normalizedHosts()).containsExactly("localhost", "127.0.0.1");
        for (String bad : new String[] {"", " ", "*", "*.example", "localhost:8084", "http://localhost", "a/b", "a b"}) {
            assertThatThrownBy(() -> hosts(bad).normalizedHosts()).as(bad).isInstanceOf(UiConfigurationException.class);
        }
        assertThatThrownBy(() -> hosts().normalizedHosts()).isInstanceOf(UiConfigurationException.class);
    }

    @Test
    void escapeDropsControlsAndBidiAndCaps() {
        assertThat(Escape.text("a\r\nb\tc\u0000d\u2028e\u2029h\u202ef\u0085g\u009bi")).isEqualTo("a  b cd e hfgi");
        assertThat(Escape.text(null)).isEmpty();
        assertThat(Escape.text("x".repeat(400))).hasSize(300);
        assertThat(Escape.text("x".repeat(299) + "\ud83d\ude00")).hasSize(299);
        assertThat(Escape.text("<b>&\"'", 50)).isEqualTo("<b>&\"'"); // escaping is the template's job
    }

    @Test
    void safeUrlAllowsOnlyPlainHttpAndHttps() {
        assertThat(SafeUrl.parse(" https://example.com/a?b=1 ")).isPresent();
        assertThat(SafeUrl.parse("HTTP://example.com")).isPresent();
        for (String bad : new String[] {null, "", "javascript:alert(1)", "JaVaScRiPt:alert(1)", "data:text/html,x", "//example.com",
                "/relative", "ftp://example.com", "https:///nohost", "https://u:p@example.com", "https://exa mple.com",
                "https://exa\u0001mple.com/", "https://example.com/\u202e", "http://", "https://example.com/" + "a".repeat(2100)}) {
            assertThat(SafeUrl.parse(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void templatesUseOnlyTextOutputAndNoScriptStyleOrRemoteResources() throws IOException {
        Pattern remote = Pattern.compile("(href|src|action)=\"(https?:)?//");
        Pattern event = Pattern.compile("[\\s:]on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/templates"))) {
            List<Path> all = files.toList();
            assertThat(all).hasSizeGreaterThanOrEqualTo(3);
            for (Path f : all) {
                String t = Files.readString(f);
                for (String banned : new String[] {"th:utext", "[(", "th:attr", "th:inline", "th:style", "<script", "<style",
                        " style=", "javascript:", "<iframe", "<object", "<embed", "th:onclick", "<base", "@{${"}) {
                    assertThat(t).as(f + " contains " + banned).doesNotContain(banned);
                }
                assertThat(remote.matcher(t).find()).as(f + " remote resource").isFalse();
                assertThat(event.matcher(t).find()).as(f + " event attribute").isFalse();
            }
        }
    }
}
