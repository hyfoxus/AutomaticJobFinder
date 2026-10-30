package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.time.Instant;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.QueryParser;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;

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

    private final String selector;
    private final JsonFieldMapper mapper;

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
        this.mapper = new JsonFieldMapper(spec.fields(), spec.constants(), spec.expiredPath());
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
        if (mapper.expired(root)) {
            return new Outcome(Kind.EXPIRED, null);
        }
        JsonFieldMapper.Parsed p = mapper.parse(root);
        if (p == null) {
            return new Outcome(Kind.BAD, null);
        }
        return new Outcome(Kind.OK, new Parsed(p.id(), p.title(), p.company(), p.location(), p.jobType(),
                p.category(), p.description(), p.postedAt()));
    }

    private String scriptText(Document doc) {
        for (Element e : doc.select(selector)) {
            if (e.normalName().equals("script")) {
                return e.data();
            }
        }
        return null;
    }
}
