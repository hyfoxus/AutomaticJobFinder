package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.QueryParser;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads one job out of a JSON {@code <script>} block (for example {@code __NEXT_DATA__}) by
 * applying the path expressions of a recipe. Compiling validates the recipe; parsing never throws.
 */
@Slf4j
public final class EmbeddedJsonParser {

    public enum Kind {
        OK, EXPIRED, BAD
    }

    /** Fields as found on the page. */
    public record Parsed(String id, String title, String company, String location, String jobType,
            String category, String description, Instant postedAt) {
    }

    public record Outcome(Kind kind, Parsed parsed) {
    }

    public static final Set<String> FIELD_NAMES = Set.of("id", "title", "company", "location", "jobType",
            "category", "description", "postedAt");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String DEFAULT_SEP = ", ";

    private record FieldSpec(List<JsonPath> paths, String sep, String constant) {
    }

    private final String selector;
    private final Map<String, FieldSpec> fields;
    private final JsonPath expiredPath;

    /** Throws IllegalArgumentException when the spec is invalid. */
    public EmbeddedJsonParser(Recipe.EmbeddedJson spec) {
        if (spec == null) {
            throw new IllegalArgumentException("detail.embeddedJson is required");
        }
        if (spec.selector() == null || spec.selector().isBlank()) {
            throw new IllegalArgumentException("embeddedJson.selector is required");
        }
        try {
            QueryParser.parse(spec.selector());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad embeddedJson.selector: " + e.getMessage());
        }
        this.selector = spec.selector();
        Map<String, FieldSpec> compiled = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : spec.fields().entrySet()) {
            requireKnown(e.getKey());
            compiled.put(e.getKey(), compileField(e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, String> e : spec.constants().entrySet()) {
            requireKnown(e.getKey());
            if (compiled.containsKey(e.getKey())) {
                throw new IllegalArgumentException("field '" + e.getKey() + "' is both mapped and constant");
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                throw new IllegalArgumentException("constant '" + e.getKey() + "' is blank");
            }
            compiled.put(e.getKey(), new FieldSpec(List.of(), DEFAULT_SEP, e.getValue()));
        }
        for (String required : new String[] {"id", "title"}) {
            if (!compiled.containsKey(required)) {
                throw new IllegalArgumentException("embeddedJson must map '" + required + "'");
            }
        }
        this.fields = Map.copyOf(compiled);
        this.expiredPath = spec.expiredPath() == null ? null : JsonPath.compile(spec.expiredPath());
    }

    private static void requireKnown(String name) {
        if (!FIELD_NAMES.contains(name)) {
            throw new IllegalArgumentException("unknown or unmappable field '" + name + "'");
        }
    }

    private static FieldSpec compileField(String name, JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("field '" + name + "' has no mapping");
        }
        if (node.isString()) {
            return new FieldSpec(List.of(JsonPath.compile(node.asString())), DEFAULT_SEP, null);
        }
        if (node.isObject()) {
            for (String key : node.propertyNames()) {
                if (!key.equals("const") && !key.equals("join") && !key.equals("sep")) {
                    throw new IllegalArgumentException("field '" + name + "' has unknown key '" + key + "'");
                }
            }
            JsonNode constant = node.get("const");
            JsonNode join = node.get("join");
            if (constant != null) {
                if (join != null || node.has("sep") || !constant.isString() || constant.asString().isBlank()) {
                    throw new IllegalArgumentException("field '" + name + "': const must be a lone non-blank string");
                }
                return new FieldSpec(List.of(), DEFAULT_SEP, constant.asString());
            }
            if (join != null && join.isArray() && !join.isEmpty()) {
                List<JsonPath> paths = new ArrayList<>();
                for (JsonNode p : join) {
                    if (!p.isString()) {
                        throw new IllegalArgumentException("field '" + name + "': join entries must be strings");
                    }
                    paths.add(JsonPath.compile(p.asString()));
                }
                JsonNode sep = node.get("sep");
                if (sep != null && !sep.isString()) {
                    throw new IllegalArgumentException("field '" + name + "': sep must be a string");
                }
                return new FieldSpec(List.copyOf(paths), sep == null ? DEFAULT_SEP : sep.asString(), null);
            }
        }
        throw new IllegalArgumentException("field '" + name + "' must be a path, {const} or {join}");
    }

    public Outcome parse(Document doc) {
        String json = scriptText(doc);
        if (json == null || json.isBlank()) {
            return new Outcome(Kind.BAD, null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json.strip());
        } catch (RuntimeException e) {
            log.debug("Embedded JSON is not valid: {}", e.toString());
            return new Outcome(Kind.BAD, null);
        }
        if (root == null || !root.isContainer()) {
            return new Outcome(Kind.BAD, null);
        }
        if (expiredPath != null && isExpired(root)) {
            return new Outcome(Kind.EXPIRED, null);
        }
        String id = text(root, "id", JobEntity.MAX_EXTERNAL_ID, true);
        String title = text(root, "title", JobEntity.MAX_TITLE, true);
        if (id == null || title == null) {
            return new Outcome(Kind.BAD, null);
        }
        return new Outcome(Kind.OK, new Parsed(id, title,
                text(root, "company", JobEntity.MAX_TEXT, true),
                text(root, "location", JobEntity.MAX_TEXT, true),
                text(root, "jobType", JobEntity.MAX_JOB_TYPE, true),
                text(root, "category", JobEntity.MAX_CATEGORY, true),
                text(root, "description", Integer.MAX_VALUE, false),
                postedAt(root)));
    }

    private String scriptText(Document doc) {
        for (Element e : doc.select(selector)) {
            if (e.normalName().equals("script")) {
                return e.data();
            }
        }
        return null;
    }

    private boolean isExpired(JsonNode root) {
        for (JsonNode n : expiredPath.select(root)) {
            if (n.isBoolean() ? n.asBoolean() : n.isString() && n.asString().equals("true")) {
                return true;
            }
            return false;
        }
        return false;
    }

    private String text(JsonNode root, String field, int max, boolean strip) {
        FieldSpec spec = fields.get(field);
        if (spec == null) {
            return null;
        }
        if (spec.constant() != null) {
            return truncate(spec.constant(), max);
        }
        List<String> values = values(root, spec, strip);
        if (values.isEmpty()) {
            return null;
        }
        return truncate(String.join(spec.sep(), values), max);
    }

    private Instant postedAt(JsonNode root) {
        FieldSpec spec = fields.get("postedAt");
        if (spec == null) {
            return null;
        }
        if (spec.constant() != null) {
            return JsonLdJobPostingParser.parseDate(spec.constant());
        }
        for (JsonPath path : spec.paths()) {
            for (JsonNode node : path.select(root)) {
                if (node.isIntegralNumber()) {
                    try {
                        return Instant.ofEpochMilli(node.asLong());
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
                List<String> one = new ArrayList<>();
                collect(node, true, one);
                if (!one.isEmpty()) {
                    return JsonLdJobPostingParser.parseDate(one.get(0));
                }
            }
        }
        return null;
    }

    private static List<String> values(JsonNode root, FieldSpec spec, boolean strip) {
        List<String> out = new ArrayList<>();
        for (JsonPath path : spec.paths()) {
            for (JsonNode node : path.select(root)) {
                collect(node, strip, out);
                if (out.size() >= JsonPath.MAX_RESULTS) {
                    return out;
                }
            }
        }
        return out;
    }

    /** Scalars become strings, objects are dropped, arrays contribute their scalars. */
    private static void collect(JsonNode node, boolean strip, List<String> out) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isObject()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, strip, out);
                if (out.size() >= JsonPath.MAX_RESULTS) {
                    return;
                }
            }
            return;
        }
        String v = node.asString();
        if (v == null) {
            return;
        }
        if (strip) {
            v = v.strip();
        }
        if (!v.isBlank()) {
            out.add(v);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
