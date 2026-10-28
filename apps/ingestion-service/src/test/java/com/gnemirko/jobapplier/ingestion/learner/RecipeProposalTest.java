package com.gnemirko.jobapplier.ingestion.learner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.ingestion.learner.service.RecipeProposal;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeProposal.Accepted;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeProposal.NoStrategy;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeProposal.Rejected;
import com.gnemirko.jobapplier.ingestion.service.RecipeValidator;

class RecipeProposalTest {

    static final URI TARGET = URI.create("https://careers.example.com/jobs");
    final RecipeValidator validator = new RecipeValidator(List.of("remotive", "luxoft"));

    static String listing(String extra) {
        return "{\"name\":\"x\",\"enabled\":true,\"strategy\":\"paginated-listing\",\"listing\":{"
                + "\"urlTemplate\":\"https://careers.example.com/jobs?page={page}\",\"maxPages\":999,"
                + "\"linkSelector\":\"a[href]\",\"linkPattern\":\"/jobs/[0-9]+$\"},"
                + "\"detail\":{\"extractor\":\"json-ld\",\"idFrom\":\"jsonld\"}" + extra + "}";
    }

    private RecipeProposal.Result parse(String reply) {
        return RecipeProposal.parse(reply, TARGET, "careers-example-com", validator);
    }

    @Test
    void acceptsForcesDisabledNameAndClamps() {
        var r = parse(listing(",\"limits\":{\"maxDetailFetchesPerRun\":1000}"));
        assertThat(r).isInstanceOf(Accepted.class);
        var a = (Accepted) r;
        assertThat(a.recipe().enabled()).isFalse();
        assertThat(a.recipe().name()).isEqualTo("careers-example-com");
        assertThat(a.recipe().listing().maxPages()).isEqualTo(RecipeProposal.MAX_LISTING_PAGES);
        assertThat(a.recipe().limits().maxDetailFetchesPerRun()).isEqualTo(RecipeProposal.MAX_DETAIL_FETCHES);
        assertThat(a.json()).contains("\"enabled\" : false");
    }

    @Test
    void sitemapIsAcceptedAndClamped() {
        var r = parse("{\"strategy\":\"sitemap\",\"sitemap\":{\"url\":\"https://careers.example.com/sitemap.xml\","
                + "\"urlPattern\":\"/jobs/[0-9]+$\",\"maxUrls\":5000},"
                + "\"detail\":{\"extractor\":\"json-ld\",\"idFrom\":\"jsonld\"}}");
        assertThat(r).isInstanceOf(Accepted.class);
        assertThat(((Accepted) r).recipe().sitemap().maxUrls()).isEqualTo(RecipeProposal.MAX_SITEMAP_URLS);
    }

    @Test
    void sitemapKeepQueryIsAcceptedAndKeptInTheSavedJson() {
        String base = "{\"strategy\":\"sitemap\",\"sitemap\":{\"url\":\"https://careers.example.com/sitemap.xml\","
                + "\"urlPattern\":\"/jobdetails[?]id=\",\"keepQuery\":true},"
                + "\"detail\":{\"extractor\":\"json-ld\",\"idFrom\":\"urlRegex:id=([A-Za-z0-9_]+)\"}}";
        var r = parse(base);
        assertThat(r).isInstanceOf(Accepted.class);
        assertThat(((Accepted) r).recipe().sitemap().keepQuery()).isTrue();
        assertThat(((Accepted) r).json()).contains("\"keepQuery\" : true");
        assertThat(parse(base.replace("true", "\"yes\""))).isInstanceOf(Rejected.class);
    }

    @Test
    void noneIsNoStrategy() {
        assertThat(parse("{\"strategy\":\"none\"}")).isInstanceOf(NoStrategy.class);
        assertThat(parse("  {\"strategy\": \"none\"}\n")).isInstanceOf(NoStrategy.class);
    }

    @Test
    void rejectsBadShapes() {
        assertRejected("");
        assertRejected("[]");
        assertRejected("not json");
        assertRejected("```json\n" + listing("") + "\n```");
        assertRejected(listing("") + " trailing");
        assertRejected(listing(",\"surprise\":1"));
        assertRejected(listing("").replace("\"maxPages\":999", "\"maxPages\":\"5\""));
        assertRejected(listing("").replace("\"extractor\":\"json-ld\"", "\"extractor\":\"json-ld\",\"extractor\":\"x\""));
        assertRejected("{\"strategy\":\"none\",\"extra\":1}");
        assertRejected(listing("").replace("paginated-listing", "magic"));
        assertRejected(listing("").replace("\"maxPages\":999,", ""));
    }

    @Test
    void rejectsForeignHostsSchemeAndOtherSubdomains() {
        assertRejected(listing("").replace("careers.example.com/jobs?", "evil.com/jobs?"));
        assertRejected(listing("").replace("careers.example.com/jobs?", "example.com/jobs?"));
        assertRejected(listing("").replace("careers.example.com/jobs?", "sub.careers.example.com/jobs?"));
        assertRejected(listing("").replace("https://careers", "http://careers"));
        assertRejected(listing("").replace("careers.example.com/jobs?", "careers.example.com:8443/jobs?"));
    }

    @Test
    void rejectsWorkdayAndReservedNames() {
        assertRejected("{\"strategy\":\"workday\",\"workday\":{\"host\":\"a.myworkdayjobs.com\",\"tenant\":\"t\","
                + "\"site\":\"s\",\"searchText\":\"j\",\"company\":\"A\"}}");
        var other = RecipeProposal.parse(listing(""), TARGET, "luxoft", validator);
        assertThat(other).isInstanceOf(Rejected.class);
    }

    @Test
    void rejectsDangerousPatterns() {
        assertRejected(listing("").replace("/jobs/[0-9]+$", "(a+)+$"));
        assertRejected(listing("").replace("/jobs/[0-9]+$", "x".repeat(121)));
        assertRejected(listing("").replace("/jobs/[0-9]+$", ".*a.*a.*a.*a.*a.*a.*a.*a.*a.*a.*b"));
        assertRejected(listing("").replace("/jobs/[0-9]+$", "(["));
    }

    @Test
    void rejectsRegexAndOverlongSelectorsWithFixedFeedback() {
        for (String sel : new String[] {"a:matches(x)", "a:matchesOwn(x)", "div:containsData(x)", "a[href~=x]", "a".repeat(201)}) {
            var r = parse(listing("").replace("a[href]", sel));
            assertThat(r).as(sel).isInstanceOf(Rejected.class);
            assertThat(((Rejected) r).feedback()).isEqualTo("a selector is not allowed");
        }
        assertThat(parse(listing("").replace("a[href]", "ul.jobs > li a[href^=/jobs]"))).isInstanceOf(Accepted.class);
    }

    @Test
    void feedbackNeverEchoesModelText() {
        var r = (Rejected) parse(listing("").replace("paginated-listing", "SECRETWORD"));
        assertThat(r.feedback()).doesNotContain("SECRETWORD");
        var u = (Rejected) parse(listing(",\"SECRETFIELD\":1"));
        assertThat(u.feedback()).doesNotContain("SECRETFIELD");
    }

    @Test
    void rejectsOversizedEmbeddedJson() {
        StringBuilder fields = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            fields.append(i == 0 ? "" : ",").append("\"id").append(i).append("\":\"a.b\"");
        }
        assertRejected("{\"strategy\":\"sitemap\",\"sitemap\":{\"url\":\"https://careers.example.com/s.xml\","
                + "\"urlPattern\":\"/j/\"},\"detail\":{\"extractor\":\"embedded-json\",\"embeddedJson\":{"
                + "\"selector\":\"script#d\",\"fields\":{" + fields + "}}}}");
    }

    private void assertRejected(String reply) {
        assertThat(parse(reply)).as(reply).isInstanceOf(Rejected.class);
    }
}
