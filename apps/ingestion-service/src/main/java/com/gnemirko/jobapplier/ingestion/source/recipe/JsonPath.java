package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * A tiny path expression over a JSON tree: dotted keys, {@code [n]} and {@code [*]}, for example
 * {@code props.pageProps.job.country[*]}. A missing key, or an index on a non-array, yields
 * nothing; evaluation never throws.
 */
final class JsonPath {

    static final int MAX_SEGMENTS = 32;
    static final int MAX_RESULTS = 1000;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_$-]+");

    private sealed interface Segment permits Key, Index, Wildcard {
    }

    private record Key(String name) implements Segment {
    }

    private record Index(int position) implements Segment {
    }

    private record Wildcard() implements Segment {
    }

    private final String source;
    private final List<Segment> segments;

    private JsonPath(String source, List<Segment> segments) {
        this.source = source;
        this.segments = segments;
    }

    /** Throws IllegalArgumentException for an invalid expression. */
    static JsonPath compile(String expression) {
        if (expression == null || expression.isBlank() || !expression.equals(expression.strip())) {
            throw new IllegalArgumentException("empty or padded path");
        }
        List<Segment> segments = new ArrayList<>();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (c == '[') {
                int close = expression.indexOf(']', i);
                if (close < 0) {
                    throw new IllegalArgumentException("bad path '" + expression + "'");
                }
                String inner = expression.substring(i + 1, close);
                if (inner.equals("*")) {
                    segments.add(new Wildcard());
                } else if (inner.length() >= 1 && inner.length() <= 6 && inner.chars().allMatch(Character::isDigit)) {
                    segments.add(new Index(Integer.parseInt(inner)));
                } else {
                    throw new IllegalArgumentException("bad index in path '" + expression + "'");
                }
                i = close + 1;
            } else {
                if (!segments.isEmpty()) {
                    if (c != '.') {
                        throw new IllegalArgumentException("bad path '" + expression + "'");
                    }
                    i++;
                }
                int end = i;
                while (end < n && expression.charAt(end) != '.' && expression.charAt(end) != '[') {
                    end++;
                }
                String key = expression.substring(i, end);
                if (!KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("bad key '" + key + "' in path '" + expression + "'");
                }
                segments.add(new Key(key));
                i = end;
            }
            if (segments.size() > MAX_SEGMENTS) {
                throw new IllegalArgumentException("path longer than " + MAX_SEGMENTS + " segments");
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        return new JsonPath(expression, List.copyOf(segments));
    }

    /** Matching nodes in document order, at most {@link #MAX_RESULTS}. */
    List<JsonNode> select(JsonNode root) {
        List<JsonNode> current = new ArrayList<>();
        if (root != null) {
            current.add(root);
        }
        for (Segment segment : segments) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                switch (segment) {
                    case Key k -> {
                        if (node.isObject() && node.has(k.name())) {
                            next.add(node.get(k.name()));
                        }
                    }
                    case Index idx -> {
                        if (node.isArray() && idx.position() < node.size()) {
                            next.add(node.get(idx.position()));
                        }
                    }
                    case Wildcard w -> {
                        if (node.isArray()) {
                            for (JsonNode child : node) {
                                next.add(child);
                                if (next.size() >= MAX_RESULTS) {
                                    break;
                                }
                            }
                        }
                    }
                }
                if (next.size() >= MAX_RESULTS) {
                    break;
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    @Override
    public String toString() {
        return source;
    }
}
