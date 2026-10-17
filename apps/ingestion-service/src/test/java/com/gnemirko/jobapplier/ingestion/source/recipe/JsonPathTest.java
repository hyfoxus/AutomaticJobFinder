package com.gnemirko.jobapplier.ingestion.source.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JsonPathTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonNode DOC = MAPPER.readTree(
            "{\"a\":{\"b\":[{\"c\":1},{\"c\":2},{\"d\":3}],\"s\":\"x\"},\"$k-1\":true,\"list\":[[1,2],[3]]}");

    private static List<String> select(String path) {
        return JsonPath.compile(path).select(DOC).stream().map(JsonNode::asString).toList();
    }

    @Test
    void keysIndexesAndWildcards() {
        assertThat(select("a.s")).containsExactly("x");
        assertThat(select("a.b[1].c")).containsExactly("2");
        assertThat(select("a.b[*].c")).containsExactly("1", "2");
        assertThat(select("list[*][*]")).containsExactly("1", "2", "3");
        assertThat(select("list[0][1]")).containsExactly("2");
        assertThat(select("$k-1")).containsExactly("true");
    }

    @Test
    void missingThingsYieldNothing() {
        assertThat(select("a.zzz")).isEmpty();
        assertThat(select("a.b[7]")).isEmpty();
        assertThat(select("a.s[*]")).isEmpty();
        assertThat(select("a.s[0]")).isEmpty();
        assertThat(select("a.s.deeper")).isEmpty();
        assertThat(JsonPath.compile("a").select(null)).isEmpty();
    }

    @Test
    void invalidExpressionsAreRejected() {
        for (String bad : new String[] {"", " ", "a..b", ".a", "a.", "a[", "a[x]", "a[-1]", "a[0]b", "a b", "a.b c",
                "a['k']", "a[]"}) {
            assertThatThrownBy(() -> JsonPath.compile(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> JsonPath.compile(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segmentLimitIsEnforced() {
        String ok = String.join(".", Collections.nCopies(32, "k"));
        String tooLong = String.join(".", Collections.nCopies(33, "k"));
        JsonPath.compile(ok);
        assertThatThrownBy(() -> JsonPath.compile(tooLong)).isInstanceOf(IllegalArgumentException.class);
    }
}
