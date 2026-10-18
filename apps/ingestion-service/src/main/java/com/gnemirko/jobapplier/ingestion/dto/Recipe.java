package com.gnemirko.jobapplier.ingestion.dto;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Declarative description of how to read one career site. Pure data; unknown fields are
 * rejected by the loader. Defaults are applied here, validation happens in the registry.
 */
public record Recipe(String name, Boolean enabled, String strategy, Listing listing, Sitemap sitemap,
        Workday workday, Detail detail, Limits limits) {

    public static final String STRATEGY_PAGINATED_LISTING = "paginated-listing";
    public static final String STRATEGY_SITEMAP = "sitemap";
    public static final String STRATEGY_WORKDAY = "workday";
    public static final int DEFAULT_WORKDAY_MAX_PAGES = 50;
    public static final String EXTRACTOR_JSON_LD = "json-ld";
    public static final String EXTRACTOR_EMBEDDED_JSON = "embedded-json";
    public static final String ID_JSONLD = "jsonld";
    public static final String ID_URL_REGEX_PREFIX = "urlRegex:";
    public static final int DEFAULT_DETAIL_CAP = 200;
    public static final int DEFAULT_MAX_URLS = 2000;

    public Recipe {
        enabled = enabled != null && enabled;
        limits = limits == null ? new Limits(null) : limits;
    }

    /** A sitemap recipe (no workday block). */
    public Recipe(String name, Boolean enabled, String strategy, Listing listing, Sitemap sitemap, Detail detail,
            Limits limits) {
        this(name, enabled, strategy, listing, sitemap, null, detail, limits);
    }

    /** A paginated-listing recipe (no sitemap block). */
    public Recipe(String name, Boolean enabled, String strategy, Listing listing, Detail detail, Limits limits) {
        this(name, enabled, strategy, listing, null, null, detail, limits);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record Listing(String urlTemplate, Integer firstPage, Integer maxPages, String linkSelector,
            String linkPattern) {

        public Listing {
            firstPage = firstPage == null ? 1 : firstPage;
        }
    }

    public record Sitemap(String url, String urlPattern, Integer maxUrls) {

        public Sitemap {
            maxUrls = maxUrls == null ? DEFAULT_MAX_URLS : maxUrls;
        }
    }

    /** A Workday tenant; the field mapping is fixed in code, so only these names are configurable. */
    public record Workday(String host, String tenant, String site, String searchText, String company,
            Integer maxPages) {

        public Workday {
            maxPages = maxPages == null ? DEFAULT_WORKDAY_MAX_PAGES : maxPages;
        }
    }

    public record Detail(String extractor, String idFrom, EmbeddedJson embeddedJson) {

        public Detail(String extractor, String idFrom) {
            this(extractor, idFrom, null);
        }
    }

    /**
     * Maps paths of a JSON {@code <script>} block onto job fields. A field value is a path string,
     * {@code {"const": "x"}} or {@code {"join": [paths], "sep": ", "}}.
     */
    public record EmbeddedJson(String selector, Map<String, JsonNode> fields, Map<String, String> constants,
            String expiredPath) {

        public EmbeddedJson {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            constants = constants == null ? Map.of() : Map.copyOf(constants);
        }
    }

    public record Limits(Integer maxDetailFetchesPerRun) {

        public Limits {
            maxDetailFetchesPerRun = maxDetailFetchesPerRun == null ? DEFAULT_DETAIL_CAP : maxDetailFetchesPerRun;
        }
    }
}
