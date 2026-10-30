package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates and renders the request of a json-api recipe. A slot is a value that is exactly {@code ${page}},
 * {@code ${offset}} or {@code ${size}}: in a body it becomes an integer, in a query value its decimal text. A slot
 * inside a longer string, in a key, or any other {@code ${} is rejected.
 */
public final class RequestTemplate {

    public static final int MAX_BODY_BYTES = 4096;
    public static final int MAX_DEPTH = 6;
    public static final int MAX_NODES = 50;
    public static final int MAX_QUERY_ENTRIES = 20;
    static final int MAX_QUERY_VALUE = 200;
    private static final Pattern QUERY_KEY = Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,64}");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    static final String PAGE = "${page}";
    static final String OFFSET = "${offset}";
    static final String SIZE = "${size}";
    private static final Set<String> SLOTS = Set.of(PAGE, OFFSET, SIZE);

    private RequestTemplate() {
    }

    /** Throws IllegalArgumentException for the first problem. {@code kind} is the paging kind (page or offset). */
    public static void validateBody(JsonNode body, String kind) {
        if (body == null) {
            return;
        }
        if (!body.isContainer() && !body.isValueNode()) {
            throw new IllegalArgumentException("bodyTemplate has an unsupported node");
        }
        int[] nodes = {0};
        walk(body, 1, nodes, kind);
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bodyTemplate cannot be serialised");
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("bodyTemplate is larger than " + MAX_BODY_BYTES + " bytes");
        }
    }

    private static void walk(JsonNode node, int depth, int[] nodes, String kind) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("bodyTemplate is nested deeper than " + MAX_DEPTH);
        }
        if (++nodes[0] > MAX_NODES) {
            throw new IllegalArgumentException("bodyTemplate has more than " + MAX_NODES + " nodes");
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                checkText(e.getKey(), "a key of bodyTemplate");
                if (e.getKey().contains("${")) {
                    throw new IllegalArgumentException("a slot may not be used in a key of bodyTemplate");
                }
                walk(e.getValue(), depth + 1, nodes, kind);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                walk(child, depth + 1, nodes, kind);
            }
        } else if (node.isString()) {
            checkValue(node.asString(), kind);
        } else if (!(node.isNumber() || node.isBoolean() || node.isNull())) {
            throw new IllegalArgumentException("bodyTemplate has an unsupported node");
        }
    }

    /** True when a body node or a query value is exactly this slot. */
    public static boolean usesSlot(JsonNode body, Map<String, String> query, String slot) {
        if (query.containsValue(slot)) {
            return true;
        }
        if (body == null) {
            return false;
        }
        if (body.isString()) {
            return slot.equals(body.asString());
        }
        for (JsonNode child : body) {
            if (usesSlot(child, Map.of(), slot)) {
                return true;
            }
        }
        return false;
    }

    /** Throws IllegalArgumentException for the first problem in the query map. */
    public static void validateQuery(Map<String, String> query, String kind) {
        if (query.size() > MAX_QUERY_ENTRIES) {
            throw new IllegalArgumentException("query has more than " + MAX_QUERY_ENTRIES + " entries");
        }
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (e.getKey() == null || !QUERY_KEY.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("query key must match [A-Za-z0-9_.\\[\\]-]{1,64}");
            }
            if (e.getValue() == null || e.getValue().length() > MAX_QUERY_VALUE) {
                throw new IllegalArgumentException("query values are required, at most " + MAX_QUERY_VALUE + " characters");
            }
            checkValue(e.getValue(), kind);
        }
    }

    private static void checkValue(String value, String kind) {
        checkText(value, "a template value");
        if (SLOTS.contains(value)) {
            boolean ok = SIZE.equals(value) || ("page".equals(kind) ? PAGE.equals(value) : OFFSET.equals(value));
            if (!ok) {
                throw new IllegalArgumentException("slot " + value + " does not fit paging kind " + kind);
            }
        } else if (value.contains("${")) {
            throw new IllegalArgumentException(
                    "a slot must be the whole value and one of ${page}, ${offset}, ${size}");
        }
    }

    private static void checkText(String s, String what) {
        if (s.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " has a control character");
        }
    }

    /** The body with every slot replaced by an integer. */
    public static JsonNode renderBody(JsonNode template, int page, int offset, int size) {
        return render(template, page, offset, size);
    }

    private static JsonNode render(JsonNode node, int page, int offset, int size) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.set(e.getKey(), render(e.getValue(), page, offset, size));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                out.add(render(child, page, offset, size));
            }
            return out;
        }
        if (node.isString()) {
            switch (node.asString()) {
                case PAGE -> {
                    return MAPPER.getNodeFactory().numberNode(page);
                }
                case OFFSET -> {
                    return MAPPER.getNodeFactory().numberNode(offset);
                }
                case SIZE -> {
                    return MAPPER.getNodeFactory().numberNode(size);
                }
                default -> {
                    return node;
                }
            }
        }
        return node;
    }

    /** The query as {@code a=b&c=d}, keys sorted, slots filled, names and values percent-encoded; empty when none. */
    public static String renderQuery(Map<String, String> query, int page, int offset, int size) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(query).entrySet()) {
            String v = switch (e.getValue()) {
                case PAGE -> Integer.toString(page);
                case OFFSET -> Integer.toString(offset);
                case SIZE -> Integer.toString(size);
                default -> e.getValue();
            };
            sb.append(sb.length() == 0 ? "" : "&").append(encode(e.getKey())).append('=').append(encode(v));
        }
        return sb.toString();
    }

    /** Percent-encodes everything but the RFC 3986 unreserved characters. */
    public static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }
}
