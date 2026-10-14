package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.ingestion.source.recipe.JsonLdJobPostingParser.Parsed;

class JsonLdJobPostingParserTest {

    private static String page(String... blocks) {
        StringBuilder sb = new StringBuilder("<html><head>");
        for (String b : blocks) {
            sb.append("<script type=\"application/ld+json\">").append(b).append("</script>");
        }
        return sb.append("</head><body>x</body></html>").toString();
    }

    private static Parsed parse(String... blocks) {
        return JsonLdJobPostingParser.parse(page(blocks), "https://x.example/").orElseThrow();
    }

    @Test
    void readsAllCommonFields() {
        Parsed p = parse("""
                {"@context":"https://schema.org","@type":"JobPosting","title":"Java Dev",
                 "datePosted":"2026-10-01","employmentType":["FULL_TIME","CONTRACTOR"],
                 "jobLocation":[{"@type":"Place","address":{"addressLocality":"Warsaw","addressCountry":"PL"}},
                                {"address":{"addressLocality":"Kyiv","addressCountry":{"name":"Ukraine"}}}],
                 "hiringOrganization":{"@type":"Organization","name":"DXC Luxoft"},
                 "identifier":{"@type":"PropertyValue","name":"Luxoft","value":"VR-125324"},
                 "description":"&lt;p&gt;Build &amp;amp; run&lt;/p&gt;"}""");
        assertThat(p.title()).isEqualTo("Java Dev");
        assertThat(p.identifier()).isEqualTo("VR-125324");
        assertThat(p.company()).isEqualTo("DXC Luxoft");
        assertThat(p.location()).isEqualTo("Warsaw, PL; Kyiv, Ukraine");
        assertThat(p.jobType()).isEqualTo("FULL_TIME,CONTRACTOR");
        assertThat(p.postedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(p.description()).isEqualTo("<p>Build &amp; run</p>");
    }

    @Test
    void picksJobPostingFromGraphAndArrays() {
        Parsed p = parse("{\"@type\":\"Organization\",\"name\":\"X\"}",
                "[{\"@type\":\"WebSite\"},{\"@graph\":[{\"@type\":[\"Thing\",\"JobPosting\"],\"title\":\"In graph\",\"identifier\":42}]}]");
        assertThat(p.title()).isEqualTo("In graph");
        assertThat(p.identifier()).isEqualTo("42");
    }

    @Test
    void ignoresUnparseableBlocks() {
        Parsed p = parse("{not json", "{\"@type\":\"JobPosting\",\"title\":\"OK\"}");
        assertThat(p.title()).isEqualTo("OK");
        assertThat(p.identifier()).isNull();
    }

    @Test
    void noJobPostingGivesEmpty() {
        assertThat(JsonLdJobPostingParser.parse(page("{\"@type\":\"Organization\"}"), "https://x/")).isEmpty();
        assertThat(JsonLdJobPostingParser.parse("<html></html>", "https://x/")).isEmpty();
    }

    @Test
    void toleratesStringAndArrayShapes() {
        Parsed p = parse("""
                {"@type":"JobPosting","title":"T","identifier":["A-1"],"hiringOrganization":"Plain Co",
                 "jobLocation":{"address":{"addressLocality":"Lviv","addressCountry":["UA"]}},
                 "employmentType":"PART_TIME"}""");
        assertThat(p.identifier()).isEqualTo("A-1");
        assertThat(p.company()).isEqualTo("Plain Co");
        assertThat(p.location()).isEqualTo("Lviv, UA");
        assertThat(p.jobType()).isEqualTo("PART_TIME");
        Parsed q = parse("{\"@type\":\"JobPosting\",\"title\":\"T\",\"hiringOrganization\":[{\"name\":\"Arr Co\"}],"
                + "\"jobLocation\":{\"address\":\"Berlin\"},\"identifier\":\"plain\"}");
        assertThat(q.company()).isEqualTo("Arr Co");
        assertThat(q.location()).isEqualTo("Berlin");
        assertThat(q.identifier()).isEqualTo("plain");
    }

    @Test
    void locationIsTruncated() {
        Parsed p = parse("{\"@type\":\"JobPosting\",\"title\":\"T\",\"jobLocation\":{\"address\":\""
                + "L".repeat(900) + "\"}}");
        assertThat(p.location()).hasSize(512);
    }

    @Test
    void dateShapes() {
        assertThat(JsonLdJobPostingParser.parseDate("2026-10-01")).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(JsonLdJobPostingParser.parseDate("2026-10-01T10:00:00+02:00"))
                .isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        assertThat(JsonLdJobPostingParser.parseDate("2026-10-01T10:00:00Z")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(JsonLdJobPostingParser.parseDate("2026-10-01T10:00:00")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(JsonLdJobPostingParser.parseDate("2026-10-01 10:00:00")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(JsonLdJobPostingParser.parseDate("yesterday")).isNull();
        assertThat(JsonLdJobPostingParser.parseDate(null)).isNull();
    }
}
