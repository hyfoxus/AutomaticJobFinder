package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;

class WorkdayParserTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static DetailExtractor.Result detail(String json) {
        return WorkdayParser.parseDetail(b(json), "dxc", "DXC Technology", "/job/X/Dev_R1",
                "https://h/Site/job/X/Dev_R1", null);
    }

    @Test
    void searchPageGivesPathsAndTotal() {
        var page = WorkdayParser.parseSearch(b(
                "{\"total\":25,\"jobPostings\":[{\"externalPath\":\"/job/a\"},{\"title\":\"no path\"},\"junk\"]}")).orElseThrow();
        assertThat(page.paths()).containsExactly("/job/a", "", "");
        assertThat(page.total()).isEqualTo(25);
    }

    @Test
    void searchPageWithoutTotalOrWithZeroTotalReadsZero() {
        assertThat(WorkdayParser.parseSearch(b("{\"jobPostings\":[]}")).orElseThrow().total()).isZero();
        assertThat(WorkdayParser.parseSearch(b("{\"total\":0,\"jobPostings\":[]}")).orElseThrow().total()).isZero();
        assertThat(WorkdayParser.parseSearch(b("{\"total\":\"x\",\"jobPostings\":[]}")).orElseThrow().total()).isZero();
    }

    @Test
    void unusableSearchAnswers() {
        assertThat(WorkdayParser.parseSearch(b("not json"))).isEmpty();
        assertThat(WorkdayParser.parseSearch(b(""))).isEmpty();
        assertThat(WorkdayParser.parseSearch(b("[]"))).isEmpty();
        assertThat(WorkdayParser.parseSearch(b("{\"total\":3}"))).isEmpty();
        assertThat(WorkdayParser.parseSearch(b("{\"jobPostings\":{}}"))).isEmpty();
        assertThat(WorkdayParser.parseSearch(b("{\"jobPostings\":null}"))).isEmpty();
    }

    @Test
    void detailMapsEveryField() {
        JobPosting p = detail("""
                {"jobPostingInfo":{"title":" Java Dev ","location":"Lviv, Ukraine",
                 "additionalLocations":["Kyiv, Ukraine","Lviv, Ukraine"," "],"timeType":"Full time",
                 "startDate":"2026-10-01","jobDescription":"  <p>Hi</p> ","posted":true,"country":{"x":1}}}""").posting();
        assertThat(p.externalId()).isEqualTo("/job/X/Dev_R1");
        assertThat(p.source()).isEqualTo("dxc");
        assertThat(p.title()).isEqualTo("Java Dev");
        assertThat(p.company()).isEqualTo("DXC Technology");
        assertThat(p.location()).isEqualTo("Lviv, Ukraine; Kyiv, Ukraine");
        assertThat(p.url()).isEqualTo("https://h/Site/job/X/Dev_R1");
        assertThat(p.jobType()).isEqualTo("Full time");
        assertThat(p.postedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(p.description()).isEqualTo("  <p>Hi</p> ");
        assertThat(p.category()).isNull();
        assertThat(p.updatedAt()).isNull();
    }

    @Test
    void optionalFieldsMayBeMissingAndBadDatesAreNull() {
        JobPosting p = detail("{\"jobPostingInfo\":{\"title\":\"T\",\"startDate\":\"yesterday\"}}").posting();
        assertThat(p.location()).isNull();
        assertThat(p.postedAt()).isNull();
        assertThat(p.jobType()).isNull();
        assertThat(p.description()).isNull();
        assertThat(detail("{\"jobPostingInfo\":{\"title\":\"T\",\"startDate\":\"2026-10-01T10:00:00Z\"}}").posting()
                .postedAt()).isNull();
        assertThat(detail("{\"jobPostingInfo\":{\"title\":\"T\",\"location\":\"A\",\"additionalLocations\":\"B\"}}")
                .posting().location()).isEqualTo("A");
    }

    @Test
    void closedPostingIsExpiredNotBad() {
        var r = detail("{\"jobPostingInfo\":{\"title\":\"T\",\"posted\":false}}");
        assertThat(r.expired()).isTrue();
        assertThat(r.posting()).isNull();
    }

    @Test
    void badDetailBodies() {
        for (String body : new String[] {"nope", "", "[]", "{}", "{\"jobPostingInfo\":[]}",
                "{\"jobPostingInfo\":{}}", "{\"jobPostingInfo\":{\"title\":\"  \"}}", "{\"jobPostingInfo\":{\"title\":5}}"}) {
            var r = detail(body);
            assertThat(r.posting()).as(body).isNull();
            assertThat(r.expired()).as(body).isFalse();
        }
    }

    @Test
    void valuesAreTruncatedToColumnLimits() {
        String json = "{\"jobPostingInfo\":{\"title\":\"" + "t".repeat(2000) + "\",\"location\":\"" + "l".repeat(2000)
                + "\",\"timeType\":\"" + "j".repeat(500) + "\"}}";
        JobPosting p = detail(json).posting();
        assertThat(p.title()).hasSize(JobEntity.MAX_TITLE);
        assertThat(p.location()).hasSize(JobEntity.MAX_TEXT);
        assertThat(p.jobType()).hasSize(JobEntity.MAX_JOB_TYPE);
    }

    @Test
    void searchPathIsNotTrimmed() {
        var page = WorkdayParser.parseSearch(b("{\"jobPostings\":[{\"externalPath\":\" /job/a \"}]}")).orElseThrow();
        assertThat(page.paths()).containsExactly(" /job/a ");
        assertThat(WorkdayRecipeJobSource.validPath(" /job/a ")).isFalse();
    }
}
