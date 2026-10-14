package com.gnemirko.jobapplier.ingestion.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

class UrlCanonicalizerTest {

    private static final URI BASE = URI.create("https://Career.Example.com/jobs?page=2");

    @Test
    void resolvesRelativeLinksAndDropsFragment() {
        assertThat(UrlCanonicalizer.canonicalize("/jobs/a-1#apply", BASE, false))
                .isEqualTo(URI.create("https://career.example.com/jobs/a-1"));
    }

    @Test
    void lowercasesHostAndDropsDefaultPortAndTrailingDot() {
        assertThat(UrlCanonicalizer.canonicalize("https://CAREER.example.com.:443/jobs/a-1", BASE, false))
                .isEqualTo(URI.create("https://career.example.com/jobs/a-1"));
    }

    @Test
    void keepsNonDefaultPort() {
        assertThat(UrlCanonicalizer.canonicalize("https://career.example.com:8443/x", BASE, false))
                .isEqualTo(URI.create("https://career.example.com:8443/x"));
    }

    @Test
    void queryIsDroppedUnlessRequested() {
        assertThat(UrlCanonicalizer.canonicalize("/jobs/a?id=7&utm=1", BASE, false))
                .isEqualTo(URI.create("https://career.example.com/jobs/a"));
        assertThat(UrlCanonicalizer.canonicalize("/jobs/a?id=7", BASE, true))
                .isEqualTo(URI.create("https://career.example.com/jobs/a?id=7"));
    }

    @Test
    void rejectsJunkAndNonHttpLinks() {
        assertThat(UrlCanonicalizer.canonicalize("mailto:a@b.c", BASE, false)).isNull();
        assertThat(UrlCanonicalizer.canonicalize("javascript:void(0)", BASE, false)).isNull();
        assertThat(UrlCanonicalizer.canonicalize("http://bad host/x", BASE, false)).isNull();
        assertThat(UrlCanonicalizer.canonicalize("  ", BASE, false)).isNull();
        assertThat(UrlCanonicalizer.canonicalize(null, BASE, false)).isNull();
    }

    @Test
    void overLongHrefIsTruncatedNotRejected() {
        String href = "/jobs/" + "a".repeat(3000);
        URI result = UrlCanonicalizer.canonicalize(href, BASE, false);
        assertThat(result.toString().length()).isLessThanOrEqualTo(2048 + "https://career.example.com".length());
    }
}
