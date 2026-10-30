package com.gnemirko.jobapplier.ingestion.dto;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Declarative description of how to read one career site. Pure data; unknown fields are
 * rejected by the loader. Defaults are applied here, validation happens in the registry.
 */
public record Recipe(String name, Boolean enabled, String strategy, Listing listing, Sitemap sitemap,
        Workday workday, Detail detail, Limits limits, JsonApi jsonApi) {

    public static final String STRATEGY_PAGINATED_LISTING = "paginated-listing";
    public static final String STRATEGY_SITEMAP = "sitemap";
    public static final String STRATEGY_WORKDAY = "workday";
    public static final String STRATEGY_JSON_API = "json-api";
    public static final int DEFAULT_JSON_API_MAX_PAGES = 10;
    public static final int DEFAULT_JSON_API_MAX_ITEMS = 1000;
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

    /** A recipe without a json-api block. */
    public Recipe(String name, Boolean enabled, String strategy, Listing listing, Sitemap sitemap, Workday workday,
            Detail detail, Limits limits) {
        this(name, enabled, strategy, listing, sitemap, workday, detail, limits, null);
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

    /** {@code keepQuery}: keep the query string of job URLs (for sites whose job id is in it); default false. */
    public record Sitemap(String url, String urlPattern, Integer maxUrls, Boolean keepQuery) {

        public Sitemap {
            maxUrls = maxUrls == null ? DEFAULT_MAX_URLS : maxUrls;
            keepQuery = keepQuery != null && keepQuery;
        }

        public Sitemap(String url, String urlPattern, Integer maxUrls) {
            this(url, urlPattern, maxUrls, null);
        }
    }

    /** A Workday tenant; the field mapping is fixed in code, so only these names are configurable. */
    public record Workday(String host, String tenant, String site, String searchText, String company,
            Integer maxPages) {

        public Workday {
            maxPages = maxPages == null ? DEFAULT_WORKDAY_MAX_PAGES : maxPages;
        }
    }

    /**
     * A site that loads its jobs from a JSON endpoint. {@code bodyTemplate} (POST only) and {@code query} may hold the
     * slots {@code ${page}}, {@code ${offset}} and {@code ${size}} as whole values. Field values are a path string,
     * {@code {"const": "x"}} or {@code {"join": [paths], "sep": ", "}}; {@code url} is read like the others. Exactly one
     * of {@code fields.url} and {@code jobUrlTemplate} (for example {@code https://host/jobs/{id}}) gives the job URL.
     */
    public record JsonApi(String url, String method, Map<String, String> query, JsonNode bodyTemplate, Paging paging,
            String itemsPath, Map<String, JsonNode> fields, String jobUrlTemplate, Integer maxItems) {

        public JsonApi {
            method = method == null ? "GET" : method;
            requireNoNullValues(query, "jsonApi.query");
            requireNoNullValues(fields, "jsonApi.fields");
            query = query == null ? Map.of() : Map.copyOf(query);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            maxItems = maxItems == null ? DEFAULT_JSON_API_MAX_ITEMS : maxItems;
        }

        private static void requireNoNullValues(Map<String, ?> map, String label) {
            if (map != null) {
                map.forEach((k, v) -> {
                    if (v == null) {
                        throw new IllegalArgumentException(label + " has a null value for '" + k + "'");
                    }
                });
            }
        }
    }

    /**
     * {@code kind} is {@code page} or {@code offset}; {@code start} defaults to 1 for pages and 0 for offsets;
     * {@code totalPath} optionally names the number of postings in the reply.
     */
    public record Paging(String kind, Integer start, Integer size, Integer maxPages, String totalPath) {

        public Paging {
            start = start == null ? ("offset".equals(kind) ? 0 : 1) : start;
            maxPages = maxPages == null ? DEFAULT_JSON_API_MAX_PAGES : maxPages;
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
