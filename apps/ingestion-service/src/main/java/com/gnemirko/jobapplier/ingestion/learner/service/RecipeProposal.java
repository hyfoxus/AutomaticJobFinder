package com.gnemirko.jobapplier.ingestion.learner.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.source.recipe.TimeLimitedCharSequence;
import com.gnemirko.jobapplier.ingestion.service.RecipeValidator;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Turns the model's reply into a recipe the engine may run, or says why not. The reply is parsed only into the
 * {@link Recipe} record (strict: unknown fields, trailing text, duplicate keys and scalar coercion are errors);
 * nothing in it is executed. The result is forced disabled, named from the host, host-locked and clamped, then
 * checked by the registry's own validation. The file content is re-serialised from that clamped record, never copied
 * from the reply.
 */
public final class RecipeProposal {

    public static final int MAX_LISTING_PAGES = 30;
    public static final int MAX_SITEMAP_URLS = 500;
    public static final int MAX_DETAIL_FETCHES = 100;
    public static final int MAX_PATTERN_CHARS = 120;
    static final int MAX_EMBEDDED_FIELDS = 20;
    static final int MAX_EMBEDDED_CONSTANTS = 10;
    static final int MAX_FIELD_NODE_CHARS = 600;
    static final int MAX_FIELD_DEPTH = 4;
    private static final long PROBE_NANOS = 100_000_000L;

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    private static final JsonMapper WRITER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    public sealed interface Result permits Accepted, NoStrategy, Rejected {
    }

    /** A usable recipe and the exact JSON text to save. */
    public record Accepted(Recipe recipe, String json) implements Result {
    }

    /** The model said nothing fits. */
    public record NoStrategy() implements Result {
    }

    /**
     * @param reason   for the report file (may quote the checker's message)
     * @param feedback fixed text for the model's retry note; never contains model text
     */
    public record Rejected(String reason, String feedback) implements Result {
    }

    /** A check of ours failed; the feedback is one of a few fixed strings. */
    private static final class Refused extends IllegalArgumentException {
        final String feedback;

        Refused(String message, String feedback) {
            super(message);
            this.feedback = feedback;
        }
    }

    static final String FB_JSON = "the reply was not valid JSON of the recipe format";
    static final String FB_UNKNOWN_FIELD = "the reply had an unknown field";
    static final String FB_HOST = "a URL in the reply is not on the allowed host over https";
    static final String FB_SELECTOR = "a selector is not allowed";
    static final String FB_VALIDATION = "the recipe failed validation; check required fields, patterns and limits";
    static final String FB_PATTERN = "a regex pattern is too long or too slow";
    static final int MAX_SELECTOR = 200;
    private static final Pattern BAD_SELECTOR = Pattern.compile(
            ":(matches|matchesown|matchtext|containsdata)\\b|~=", Pattern.CASE_INSENSITIVE);

    private RecipeProposal() {
    }

    /**
     * @param target the start URL; every URL of the recipe must be on its host (and port)
     * @param name   the name forced onto the recipe
     */
    public static Result parse(String reply, URI target, String name, RecipeValidator validator) {
        if (reply == null || reply.isBlank()) {
            return new Rejected("the reply was empty", FB_JSON);
        }
        JsonNode tree;
        Recipe raw;
        try {
            tree = STRICT.readTree(reply.strip());
            if (tree == null || !tree.isObject()) {
                return new Rejected("the reply is not a JSON object", FB_JSON);
            }
            JsonNode strategy = tree.get("strategy");
            if (tree.size() == 1 && strategy != null && strategy.isString() && "none".equals(strategy.asString())) {
                return new NoStrategy();
            }
            raw = STRICT.treeToValue(tree, Recipe.class);
        } catch (RuntimeException e) {
            boolean unknown = e instanceof tools.jackson.databind.exc.UnrecognizedPropertyException;
            return new Rejected("the reply is not valid recipe JSON: " + shorten(e.getMessage()),
                    unknown ? FB_UNKNOWN_FIELD : FB_JSON);
        }
        try {
            if (Recipe.STRATEGY_WORKDAY.equals(raw.strategy())) {
                throw new Refused("the workday strategy is not allowed here", FB_VALIDATION);
            }
            Recipe locked = lockAndClamp(raw, target, name);
            try {
                validator.validate(locked);
            } catch (Refused e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw new Refused(e.getMessage(), FB_VALIDATION);
            }
            probePatterns(locked, target);
            return new Accepted(locked, WRITER.writeValueAsString(locked));
        } catch (Refused e) {
            return new Rejected(shorten(e.getMessage()), e.feedback);
        } catch (RuntimeException e) {
            return new Rejected("the recipe could not be checked: " + shorten(e.getMessage()), FB_VALIDATION);
        }
    }

    private static Recipe lockAndClamp(Recipe r, URI target, String name) {
        Recipe.Listing listing = r.listing();
        Recipe.Sitemap sitemap = r.sitemap();
        if (listing != null) {
            requireTargetHost(listing.urlTemplate() == null ? null : listing.urlTemplate().replace("{page}", "1"),
                    target, "listing.urlTemplate");
            checkSelector(listing.linkSelector(), "listing.linkSelector");
            Integer pages = listing.maxPages() == null ? null : Math.min(listing.maxPages(), MAX_LISTING_PAGES);
            listing = new Recipe.Listing(listing.urlTemplate(), listing.firstPage(), pages, listing.linkSelector(),
                    listing.linkPattern());
        }
        if (sitemap != null) {
            requireTargetHost(sitemap.url(), target, "sitemap.url");
            Integer max = sitemap.maxUrls() == null ? null : Math.min(sitemap.maxUrls(), MAX_SITEMAP_URLS);
            sitemap = new Recipe.Sitemap(sitemap.url(), sitemap.urlPattern(), max);
        }
        Recipe.Detail detail = r.detail();
        if (detail != null && detail.embeddedJson() != null) {
            checkEmbedded(detail.embeddedJson());
        }
        Integer cap = r.limits().maxDetailFetchesPerRun();
        Recipe.Limits limits = new Recipe.Limits(cap == null ? null : Math.min(cap, MAX_DETAIL_FETCHES));
        return new Recipe(name, false, r.strategy(), listing, sitemap, r.workday(), detail, limits);
    }

    private static void requireTargetHost(String url, URI target, String label) {
        URI u;
        try {
            u = URI.create(url == null ? "" : url);
        } catch (IllegalArgumentException e) {
            throw new Refused(label + " is not a valid URL", FB_HOST);
        }
        if (u.getHost() == null || !UrlPolicy.hostKey(u).equals(UrlPolicy.hostKey(target))) {
            throw new Refused(label + " must be on the host " + UrlPolicy.hostKey(target) + " only", FB_HOST);
        }
        if (u.getScheme() == null || !u.getScheme().equalsIgnoreCase("https")) {
            throw new Refused(label + " must use https", FB_HOST);
        }
    }

    private static void checkEmbedded(Recipe.EmbeddedJson e) {
        checkSelector(e.selector(), "embeddedJson.selector");
        if (e.fields().size() > MAX_EMBEDDED_FIELDS || e.constants().size() > MAX_EMBEDDED_CONSTANTS) {
            throw new Refused("embeddedJson has too many fields or constants", FB_VALIDATION);
        }
        for (Map.Entry<String, JsonNode> f : e.fields().entrySet()) {
            JsonNode n = f.getValue();
            if (n == null || depth(n) > MAX_FIELD_DEPTH || n.toString().length() > MAX_FIELD_NODE_CHARS) {
                throw new Refused("embeddedJson field '" + shorten(f.getKey()) + "' is too large or too deep", FB_VALIDATION);
            }
        }
        for (String v : e.constants().values()) {
            if (v != null && v.length() > 200) {
                throw new Refused("an embeddedJson constant is too long", FB_VALIDATION);
            }
        }
        if (e.expiredPath() != null && e.expiredPath().length() > 200) {
            throw new Refused("embeddedJson.expiredPath is too long", FB_VALIDATION);
        }
    }

        /** Plain CSS only: capped length and none of jsoup's regex selectors, which would skip the pattern checks. */
    static void checkSelector(String selector, String label) {
        if (selector == null) {
            return;
        }
        if (selector.length() > MAX_SELECTOR || BAD_SELECTOR.matcher(selector).find()) {
            throw new Refused(label + " is too long or uses a regex selector", FB_SELECTOR);
        }
    }

private static int depth(JsonNode n) {
        int max = 0;
        for (JsonNode c : n.values()) {
            max = Math.max(max, depth(c));
        }
        return n.isContainer() ? max + 1 : 0;
    }

    /** Runs every pattern of the recipe on hostile-looking input under a time limit; a slow pattern is rejected. */
    private static void probePatterns(Recipe r, URI target) {
        Map<String, String> patterns = new LinkedHashMap<>();
        if (r.listing() != null) {
            patterns.put("listing.linkPattern", r.listing().linkPattern());
        }
        if (r.sitemap() != null) {
            patterns.put("sitemap.urlPattern", r.sitemap().urlPattern());
        }
        String idFrom = r.detail() == null ? null : r.detail().idFrom();
        if (idFrom != null && idFrom.startsWith(Recipe.ID_URL_REGEX_PREFIX)) {
            patterns.put("detail.idFrom", idFrom.substring(Recipe.ID_URL_REGEX_PREFIX.length()));
        }
        String base = "https://" + UrlPolicy.hostKey(target) + "/";
        String[] samples = {
                base + "a".repeat(1500) + "!",
                base + "a-1".repeat(500) + "?x=" + "9".repeat(300) + "!",
                base + "jobs/" + "ab/".repeat(400) + "!" };
        for (Map.Entry<String, String> p : patterns.entrySet()) {
            String regex = p.getValue();
            if (regex.length() > MAX_PATTERN_CHARS) {
                throw new Refused(p.getKey() + " is longer than " + MAX_PATTERN_CHARS + " characters", FB_PATTERN);
            }
            Pattern compiled = Pattern.compile(regex);
            for (String sample : samples) {
                try {
                    compiled.matcher(TimeLimitedCharSequence.limited(sample, PROBE_NANOS)).find();
                } catch (TimeLimitedCharSequence.MatchTimeoutException e) {
                    throw new Refused(p.getKey() + " is too slow on long input; use a simpler pattern", FB_PATTERN);
                }
            }
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "unknown problem";
        }
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 300 ? one.substring(0, 300) : one;
    }
}
