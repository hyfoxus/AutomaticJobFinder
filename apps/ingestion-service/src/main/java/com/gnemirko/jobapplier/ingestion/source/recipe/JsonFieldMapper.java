package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gnemirko.jobapplier.ingestion.entity.JobEntity;

import tools.jackson.databind.JsonNode;

/**
 * Maps one JSON object onto job fields with the path expressions of a recipe. A field value is a path string,
 * {@code {"const": "x"}} or {@code {"join": [paths], "sep": ", "}}. Compiling validates the spec; parsing never throws.
 * Shared by the embedded-json extractor and the json-api strategy.
 */
public final class JsonFieldMapper {

    /** Fields as found in the JSON; {@code url} is only mapped by json-api. */
    public record Parsed(String id, String title, String company, String location, String jobType, String category,
            String description, Instant postedAt, String url) {
    }

    /** Integer dates below this are epoch seconds when the seconds rule is on (10^11 ms is early 1973). */
    static final long SECONDS_LIMIT = 100_000_000_000L;
    private static final String DEFAULT_SEP = ", ";

    private record FieldSpec(List<JsonPath> paths, String sep, String constant) {
    }

    private final Map<String, FieldSpec> fields;
    private final JsonPath expiredPath;
    private final boolean jsonApiMode;

    /** The embedded-json mapping: integer dates are milliseconds. Throws IllegalArgumentException when invalid. */
    public JsonFieldMapper(Map<String, JsonNode> fields, Map<String, String> constants, String expiredPath) {
        this(fields, constants, expiredPath, EmbeddedJsonParser.FIELD_NAMES, false);
    }

    /**
     * @param allowedNames the field names that may be mapped
     * @param jsonApiMode json-api rules: integer dates below 10^11 are epoch seconds, and an over-long url is
     *        dropped instead of truncated
     */
    public JsonFieldMapper(Map<String, JsonNode> fields, Map<String, String> constants, String expiredPath,
            Set<String> allowedNames, boolean jsonApiMode) {
        Map<String, FieldSpec> compiled = new LinkedHashMap<>();
        this.jsonApiMode = jsonApiMode;
        for (Map.Entry<String, JsonNode> e : fields.entrySet()) {
            requireKnown(e.getKey(), allowedNames);
            compiled.put(e.getKey(), compileField(e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, String> e : constants.entrySet()) {
            requireKnown(e.getKey(), allowedNames);
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
                throw new IllegalArgumentException(
                        (jsonApiMode ? "jsonApi.fields" : "embeddedJson") + " must map '" + required + "'");
            }
        }
        this.fields = Map.copyOf(compiled);
        this.expiredPath = expiredPath == null ? null : JsonPath.compile(expiredPath);
    }

    private static void requireKnown(String name, Set<String> allowed) {
        if (!allowed.contains(name)) {
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

    /** True when the expired path (if any) selects a true value. */
    public boolean expired(JsonNode root) {
        if (expiredPath == null) {
            return false;
        }
        for (JsonNode n : expiredPath.select(root)) {
            if (n.isBoolean() ? n.asBoolean() : n.isString() && n.asString().equals("true")) {
                return true;
            }
            return false;
        }
        return false;
    }

    /** The mapped fields, or null when id or title is missing. */
    public Parsed parse(JsonNode item) {
        String id = text(item, "id", JobEntity.MAX_EXTERNAL_ID, true);
        String title = text(item, "title", JobEntity.MAX_TITLE, true);
        if (id == null || title == null) {
            return null;
        }
        return new Parsed(id, title,
                text(item, "company", JobEntity.MAX_TEXT, true),
                text(item, "location", JobEntity.MAX_TEXT, true),
                text(item, "jobType", JobEntity.MAX_JOB_TYPE, true),
                text(item, "category", JobEntity.MAX_CATEGORY, true),
                text(item, "description", Integer.MAX_VALUE, false),
                postedAt(item),
                text(item, "url", jsonApiMode ? Integer.MAX_VALUE : JobEntity.MAX_URL, true));
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
                        long v = node.asLong();
                        return jsonApiMode && Math.abs(v) < SECONDS_LIMIT ? Instant.ofEpochSecond(v)
                                : Instant.ofEpochMilli(v);
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
