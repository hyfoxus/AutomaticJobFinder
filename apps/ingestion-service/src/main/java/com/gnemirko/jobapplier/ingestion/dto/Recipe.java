package com.gnemirko.jobapplier.ingestion.dto;

/**
 * Declarative description of how to read one career site. Pure data; unknown fields are
 * rejected by the loader. Defaults are applied here, validation happens in the registry.
 */
public record Recipe(String name, Boolean enabled, String strategy, Listing listing, Detail detail, Limits limits) {

    public static final String STRATEGY_PAGINATED_LISTING = "paginated-listing";
    public static final String EXTRACTOR_JSON_LD = "json-ld";
    public static final String ID_JSONLD = "jsonld";
    public static final String ID_URL_REGEX_PREFIX = "urlRegex:";
    public static final int DEFAULT_DETAIL_CAP = 200;

    public Recipe {
        enabled = enabled != null && enabled;
        limits = limits == null ? new Limits(null) : limits;
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

    public record Detail(String extractor, String idFrom) {
    }

    public record Limits(Integer maxDetailFetchesPerRun) {

        public Limits {
            maxDetailFetchesPerRun = maxDetailFetchesPerRun == null ? DEFAULT_DETAIL_CAP : maxDetailFetchesPerRun;
        }
    }
}
