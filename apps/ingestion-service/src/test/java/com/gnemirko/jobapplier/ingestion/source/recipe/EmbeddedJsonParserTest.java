package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EmbeddedJsonParserTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Map<String, JsonNode> fields(String json) {
        JsonNode node = MAPPER.readTree(json);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        node.propertyNames().forEach(k -> out.put(k, node.get(k)));
        return out;
    }

    private static final String FIELDS = """
            {"id":"j.id","title":"j.name",
             "location":{"join":["j.city","j.country[*]"],"sep":" / "},
             "category":"j.tags[*]","description":"j.desc","postedAt":"j.created","jobType":{"const":"Full"}}""";

    private static EmbeddedJsonParser parser(String fieldsJson, Map<String, String> constants, String expired) {
        return new EmbeddedJsonParser(new Recipe.EmbeddedJson("script#d", fields(fieldsJson), constants, expired));
    }

    private static org.jsoup.nodes.Document page(String json) {
        return Jsoup.parse("<html><body><script id=\"d\" type=\"application/json\">" + json + "</script></body></html>");
    }

    @Test
    void mapsFieldsAndCoercesTypes() {
        EmbeddedJsonParser p = parser(FIELDS, Map.of("company", "Acme"), "j.expired");
        var out = p.parse(page("{\"j\":{\"id\":77,\"name\":\"Dev\",\"city\":\"Kyiv\",\"country\":[\"UA\",\"PL\",{\"x\":1}],"
                + "\"tags\":[\"a\",\"b\"],\"desc\":\" <p>raw &amp; text</p> \",\"created\":\"2026-10-01\"}}"));
        assertThat(out.kind()).isEqualTo(EmbeddedJsonParser.Kind.OK);
        var r = out.parsed();
        assertThat(r.id()).isEqualTo("77");
        assertThat(r.title()).isEqualTo("Dev");
        assertThat(r.company()).isEqualTo("Acme");
        assertThat(r.location()).isEqualTo("Kyiv / UA / PL");
        assertThat(r.category()).isEqualTo("a, b");
        assertThat(r.jobType()).isEqualTo("Full");
        assertThat(r.description()).isEqualTo(" <p>raw &amp; text</p> ");
        assertThat(r.postedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void epochMillisecondsAndMissingValues() {
        EmbeddedJsonParser p = parser(FIELDS, Map.of(), null);
        var r = p.parse(page("{\"j\":{\"id\":\"x\",\"name\":\"T\",\"created\":1760000000000,\"city\":null}}")).parsed();
        assertThat(r.postedAt()).isEqualTo(Instant.ofEpochMilli(1760000000000L));
        assertThat(r.location()).isNull();
        assertThat(r.company()).isNull();
        assertThat(r.category()).isNull();
        assertThat(r.description()).isNull();
    }

    @Test
    void digitOnlyStringIsADateNotEpochMilliseconds() {
        EmbeddedJsonParser p = parser(FIELDS, Map.of(), null);
        assertThat(p.parse(page("{\"j\":{\"id\":\"1\",\"name\":\"T\",\"created\":\"20261001\"}}")).parsed().postedAt())
                .isNull();
        assertThat(p.parse(page("{\"j\":{\"id\":\"1\",\"name\":\"T\",\"created\":\"1760000000000\"}}")).parsed().postedAt())
                .isNull();
    }

    @Test
    void expiredOnlyForTrueOrTrueString() {
        EmbeddedJsonParser p = parser(FIELDS, Map.of(), "j.expired");
        assertThat(p.parse(page("{\"j\":{\"id\":\"1\",\"name\":\"T\",\"expired\":true}}")).kind())
                .isEqualTo(EmbeddedJsonParser.Kind.EXPIRED);
        assertThat(p.parse(page("{\"j\":{\"id\":\"1\",\"name\":\"T\",\"expired\":\"true\"}}")).kind())
                .isEqualTo(EmbeddedJsonParser.Kind.EXPIRED);
        for (String v : new String[] {"false", "\"false\"", "1", "\"yes\"", "null", "{}", "[true]"}) {
            assertThat(p.parse(page("{\"j\":{\"id\":\"1\",\"name\":\"T\",\"expired\":" + v + "}}")).kind())
                    .as(v).isEqualTo(EmbeddedJsonParser.Kind.OK);
        }
    }

    @Test
    void badPagesAreReportedNotThrown() {
        EmbeddedJsonParser p = parser(FIELDS, Map.of(), null);
        assertThat(p.parse(Jsoup.parse("<html><body>nothing</body></html>")).kind()).isEqualTo(EmbeddedJsonParser.Kind.BAD);
        assertThat(p.parse(page("{not json")).kind()).isEqualTo(EmbeddedJsonParser.Kind.BAD);
        assertThat(p.parse(page("")).kind()).isEqualTo(EmbeddedJsonParser.Kind.BAD);
        assertThat(p.parse(page("{\"j\":{\"id\":\"1\"}}")).kind()).isEqualTo(EmbeddedJsonParser.Kind.BAD);
        assertThat(p.parse(page("{\"j\":{\"name\":\"T\"}}")).kind()).isEqualTo(EmbeddedJsonParser.Kind.BAD);
        assertThat(p.parse(page("{\"j\":{\"id\":{\"o\":1},\"name\":\"T\"}}")).kind()).isEqualTo(EmbeddedJsonParser.Kind.BAD);
        assertThat(p.parse(page("42")).kind()).isEqualTo(EmbeddedJsonParser.Kind.BAD);
    }

    @Test
    void mappedValuesAreTruncatedAndCapped() {
        EmbeddedJsonParser p = parser(FIELDS, Map.of(), null);
        String longTitle = "t".repeat(JobEntity.MAX_TITLE + 50);
        var r = p.parse(page("{\"j\":{\"id\":\"1\",\"name\":\"" + longTitle + "\",\"country\":["
                + "\"c\",".repeat(1500) + "\"c\"]}}")).parsed();
        assertThat(r.title()).hasSize(JobEntity.MAX_TITLE);
        assertThat(r.location()).hasSize(JobEntity.MAX_TEXT);
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 1500; i++) {
            many.append(i == 0 ? "" : ",").append("\"v\"");
        }
        many.append("]");
        var capped = parser("{\"id\":\"j.id\",\"title\":\"j.name\",\"description\":\"j.d[*]\"}", Map.of(), null)
                .parse(page("{\"j\":{\"id\":\"1\",\"name\":\"T\",\"d\":" + many + "}}")).parsed();
        assertThat(capped.description().split(", ")).hasSize(1000);
    }

    @Test
    void firstMatchingScriptWins() {
        EmbeddedJsonParser p = new EmbeddedJsonParser(new Recipe.EmbeddedJson("script.x", fields(FIELDS), Map.of(), null));
        var doc = Jsoup.parse("<script class=x>{\"j\":{\"id\":\"1\",\"name\":\"First\"}}</script>"
                + "<script class=x>{\"j\":{\"id\":\"2\",\"name\":\"Second\"}}</script>");
        assertThat(p.parse(doc).parsed().title()).isEqualTo("First");
    }

    @Test
    void invalidSpecsAreRejected() {
        String minimal = "{\"id\":\"j.id\",\"title\":\"j.name\"}";
        parser(minimal, Map.of(), null);
        assertThatThrownBy(() -> parser("{\"id\":\"j.id\"}", Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser("{\"id\":\"j.id\",\"title\":\"a..b\"}", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser("{\"id\":\"j.id\",\"title\":\"j.n\",\"updatedAt\":\"j.u\"}", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser("{\"id\":\"j.id\",\"title\":\"j.n\",\"salary\":\"j.u\"}", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser("{\"id\":\"j.id\",\"title\":{\"join\":[]}}", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser("{\"id\":\"j.id\",\"title\":{\"const\":\"x\",\"junk\":1}}", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser("{\"id\":\"j.id\",\"title\":5}", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser(minimal, Map.of("title", "dup"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser(minimal, Map.of("updatedAt", "x"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser(minimal, Map.of(), "bad[")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddedJsonParser(new Recipe.EmbeddedJson("a[[", fields(minimal), Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddedJsonParser(new Recipe.EmbeddedJson(" ", fields(minimal), Map.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddedJsonParser(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
