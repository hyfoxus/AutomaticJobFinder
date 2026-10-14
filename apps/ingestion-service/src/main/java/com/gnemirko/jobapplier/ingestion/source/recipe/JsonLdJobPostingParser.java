package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the first schema.org JobPosting out of the application/ld+json blocks of an HTML page. */
public final class JsonLdJobPostingParser {

    /** Fields as found on the page; identifier is null when the page has none. */
    public record Parsed(String identifier, String title, String company, String location, String jobType,
            Instant postedAt, String description) {
    }

    private static final Logger log = LoggerFactory.getLogger(JsonLdJobPostingParser.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final DateTimeFormatter SPACE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_LOCATION = 512;
    private static final int MAX_COMPANY = 512;
    private static final int MAX_JOB_TYPE = 64;

    private JsonLdJobPostingParser() {
    }

    public static Optional<Parsed> parse(String html, String baseUri) {
        return parse(Jsoup.parse(html, baseUri == null ? "" : baseUri));
    }

    public static Optional<Parsed> parse(org.jsoup.nodes.Document doc) {
        List<JsonNode> nodes = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            String json = script.data();
            if (json.isBlank()) {
                json = script.html();
            }
            try {
                flatten(MAPPER.readTree(json.strip()), nodes);
            } catch (RuntimeException e) {
                log.debug("Ignoring unparseable ld+json block: {}", e.toString());
            }
        }
        for (JsonNode n : nodes) {
            if (isJobPosting(n)) {
                return Optional.of(map(n));
            }
        }
        return Optional.empty();
    }

    private static void flatten(JsonNode node, List<JsonNode> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode c : node) {
                flatten(c, out);
            }
        } else if (node.isObject()) {
            out.add(node);
            flatten(node.get("@graph"), out);
        }
    }

    private static boolean isJobPosting(JsonNode n) {
        JsonNode type = n.get("@type");
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (t.isString() && t.asString().endsWith("JobPosting")) {
                    return true;
                }
            }
            return false;
        }
        return type.isString() && type.asString().endsWith("JobPosting");
    }

    private static Parsed map(JsonNode n) {
        String title = decode(text(n.get("title")));
        if (title == null) {
            title = decode(text(n.get("name")));
        }
        return new Parsed(
                identifier(n.get("identifier")),
                title,
                truncate(decode(named(n.get("hiringOrganization"))), MAX_COMPANY),
                truncate(locations(n.get("jobLocation")), MAX_LOCATION),
                truncate(joinTexts(n.get("employmentType"), ","), MAX_JOB_TYPE),
                parseDate(text(n.get("datePosted"))),
                decode(text(n.get("description"))));
    }

    private static String identifier(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isArray()) {
            for (JsonNode c : n) {
                String v = identifier(c);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }
        if (n.isObject()) {
            for (String key : new String[] {"value", "@value", "name"}) {
                String v = identifier(n.get(key));
                if (v != null) {
                    return v;
                }
            }
            return null;
        }
        return text(n);
    }

    /** String, object with name, or array of those (first usable). */
    private static String named(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isArray()) {
            for (JsonNode c : n) {
                String v = named(c);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }
        if (n.isObject()) {
            return named(n.get("name"));
        }
        return text(n);
    }

    private static String locations(JsonNode n) {
        List<String> parts = new ArrayList<>();
        collectLocations(n, parts);
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static void collectLocations(JsonNode n, List<String> out) {
        if (n == null || n.isNull()) {
            return;
        }
        if (n.isArray()) {
            for (JsonNode c : n) {
                collectLocations(c, out);
            }
            return;
        }
        String one;
        if (n.isObject()) {
            JsonNode address = n.get("address");
            if (address != null && address.isObject()) {
                List<String> bits = new ArrayList<>();
                for (String key : new String[] {"addressLocality", "addressRegion", "addressCountry"}) {
                    String v = named(address.get(key));
                    if (v != null) {
                        bits.add(v);
                    }
                }
                one = bits.isEmpty() ? null : String.join(", ", bits);
            } else if (address != null) {
                one = text(address);
            } else {
                one = text(n.get("name"));
            }
        } else {
            one = text(n);
        }
        one = decode(one);
        if (one != null) {
            out.add(one);
        }
    }

    private static String joinTexts(JsonNode n, String sep) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            return text(n);
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode c : n) {
            String v = text(c);
            if (v != null) {
                parts.add(v);
            }
        }
        return parts.isEmpty() ? null : String.join(sep, parts);
    }

    static Instant parseDate(String s) {
        if (s == null) {
            return null;
        }
        String v = s.strip();
        try {
            return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // try the next shape
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (RuntimeException ignored) {
            // try the next shape
        }
        try {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // try the next shape
        }
        try {
            return LocalDateTime.parse(v, SPACE_DATE_TIME).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull() || n.isObject() || n.isArray()) {
            return null;
        }
        String v = n.asString().strip();
        return v.isEmpty() ? null : v;
    }

    private static String decode(String s) {
        return s == null ? null : Parser.unescapeEntities(s, false);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
