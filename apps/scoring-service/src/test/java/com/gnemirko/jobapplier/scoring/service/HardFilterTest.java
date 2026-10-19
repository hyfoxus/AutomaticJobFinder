package com.gnemirko.jobapplier.scoring.service;

import static com.gnemirko.jobapplier.scoring.service.UnitSupport.posting;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.scoring.dto.Profile;

class HardFilterTest {

    private final HardFilter filter = new HardFilter(UnitSupport.profile());

    @Test
    void passesAnOrdinaryJob() {
        assertThat(filter.reject(posting("Java Developer", "Acme", "Remote", "d"))).isEmpty();
    }

    @Test
    void rejectsExcludedTitleCaseInsensitively() {
        assertThat(filter.reject(posting("Summer INTERN", "Acme", "Remote", "d"))).hasValueSatisfying(
                r -> assertThat(r).contains("title"));
        assertThat(filter.reject(posting("Internal Tools Dev", "Acme", "Remote", "d"))).isEmpty(); // \bintern\b
    }

    @Test
    void rejectsExcludedCompany() {
        assertThat(filter.reject(posting("Dev", " badcorp ", "Remote", "d"))).hasValueSatisfying(
                r -> assertThat(r).contains("company"));
    }

    @Test
    void locationMustContainAnAllowedKeywordUnlessBlank() {
        assertThat(filter.reject(posting("Dev", "Acme", "Berlin, Europe", "d"))).isEmpty();
        assertThat(filter.reject(posting("Dev", "Acme", null, "d"))).isEmpty();
        assertThat(filter.reject(posting("Dev", "Acme", "  ", "d"))).isEmpty();
        assertThat(filter.reject(posting("Dev", "Acme", "Brazil", "d"))).hasValueSatisfying(
                r -> assertThat(r).contains("location"));
    }

    @Test
    void emptyAllowedListMeansNoLocationFilter() {
        Profile p = new Profile("s", java.util.List.of("a"), null, null, null);
        assertThat(new HardFilter(p).reject(posting("Dev", "Acme", "Mars", "d"))).isEmpty();
    }

    @Test
    void catastrophicRegexTimesOutAndRejectsFailClosed() {
        Profile p = new Profile("s", java.util.List.of("a"), null, null,
                new Profile.Filters(java.util.List.of("(a+)+$"), null, null));
        String title = "a".repeat(5000) + "!";
        long start = System.nanoTime();
        assertThat(new HardFilter(p).reject(posting(title, "Acme", "Remote", "d"))).hasValueSatisfying(
                r -> assertThat(r).contains("timed out"));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3000);
    }
}
