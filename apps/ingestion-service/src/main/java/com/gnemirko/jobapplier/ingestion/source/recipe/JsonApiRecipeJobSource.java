package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.FetchContext;
import com.gnemirko.jobapplier.ingestion.dto.FetchResult;
import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import com.gnemirko.jobapplier.ingestion.source.RunNotes;
import com.gnemirko.jobapplier.ingestion.util.UrlCanonicalizer;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The json-api strategy: page through a JSON endpoint and map each item onto a posting. Paging stops on an empty or
 * short page, on the reported total, on a page with no new ids, and at maxPages, maxItems or the run deadline. A failing
 * list page fails the run (nothing is stored). With a detail block the job pages are read through the shared detail
 * step; without one the list item is the posting. Every request goes through the polite fetcher; no headers, cookies or
 * tokens can be configured, so a site that needs them fails with a note.
 */
@Slf4j
public class JsonApiRecipeJobSource implements JobSource {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern JOB_ID = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    private final Recipe recipe;
    private final Recipe.JsonApi api;
    private final PoliteFetcher fetcher;
    private final Clock clock;
    private final Duration maxRun;
    private final URI endpoint;
    private final boolean httpsOnly;
    private final JsonFieldMapper mapper;
    private final JsonPath itemsPath;
    private final boolean wildcardItems;
    private final JsonPath totalPath;
    private final Pattern idPattern;

    public JsonApiRecipeJobSource(Recipe recipe, PoliteFetcher fetcher, Clock clock, Duration maxRun) {
        this(recipe, fetcher, clock, maxRun, null);
    }

    /** Tests point the endpoint at an http stub: {@code base} replaces scheme and authority of {@code jsonApi.url}. */
    public JsonApiRecipeJobSource(Recipe recipe, PoliteFetcher fetcher, Clock clock, Duration maxRun, URI base) {
        this.recipe = recipe;
        this.api = recipe.jsonApi();
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxRun = maxRun;
        URI configured = URI.create(api.url());
        this.endpoint = base == null ? configured
                : URI.create(base.toString().replaceAll("/+$", "") + configured.getRawPath());
        this.httpsOnly = base == null;
        this.mapper = new JsonFieldMapper(api.fields(), java.util.Map.of(), null, JsonApiValidator.FIELD_NAMES, true);
        this.itemsPath = JsonPath.compile(api.itemsPath());
        this.wildcardItems = api.itemsPath().endsWith("[*]");
        this.totalPath = api.paging().totalPath() == null ? null : JsonPath.compile(api.paging().totalPath());
        String idFrom = recipe.detail() == null ? null : recipe.detail().idFrom();
        this.idPattern = idFrom != null && idFrom.startsWith(Recipe.ID_URL_REGEX_PREFIX)
                ? Pattern.compile(idFrom.substring(Recipe.ID_URL_REGEX_PREFIX.length())) : null;
    }

    @Override
    public String name() {
        return recipe.name();
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant deadline = clock.instant().plus(maxRun);
        Recipe.Paging paging = api.paging();
        int size = paging.size();
        int cap = recipe.limits().maxDetailFetchesPerRun();
        DetailStep step = recipe.detail() == null ? null
                : new DetailStep(fetcher, name(), DetailStep.extractorFor(recipe, idPattern), cap);

        Set<String> seenIds = new HashSet<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> unchanged = new LinkedHashSet<>();
        List<JobPosting> postings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int skipped = 0;
        int notVisitedNew = 0;
        int considered = 0;
        long rawSeen = 0;
        boolean outOfTime = false;
        boolean itemLimit = false;

        pages:
        for (int p = 0; p < paging.maxPages(); p++) {
            if (!clock.instant().isBefore(deadline)) {
                outOfTime = true;
                break;
            }
            int page = paging.start() + p;
            int offset = paging.start() + p * size;
            JsonNode root;
            try {
                root = requestPage(page, offset, size);
            } catch (RobotsDisallowedException e) {
                log.warn("Recipe '{}': {} disallowed by robots.txt; stopping", name(), endpoint);
                notes.add("Endpoint " + endpoint + " " + RunNotes.ROBOTS_DISALLOWED);
                break;
            }
            List<JsonNode> items = items(root);
            if (p == 0 && items.isEmpty()) {
                notes.add("no items found at itemsPath on the first page");
            }
            rawSeen += items.size();
            int newIds = 0;
            int usable = 0;
            for (JsonNode item : items) {
                if (considered >= api.maxItems()) {
                    itemLimit = true;
                    break pages;
                }
                considered++;
                JsonFieldMapper.Parsed m = mapper.parse(item);
                if (m == null) {
                    skipped++;
                    continue;
                }
                usable++;
                if (!seenIds.add(m.id())) {
                    continue;
                }
                newIds++;
                String url = jobUrl(m, step != null);
                if (url == null) {
                    skipped++;
                    continue;
                }
                if (!seenUrls.add(url)) {
                    continue;
                }
                if (step == null) {
                    postings.add(new JobPosting(m.id(), name(), m.title(), m.company(), m.location(), url,
                            m.description(), m.postedAt(), m.category(), m.jobType(), null, null));
                    continue;
                }
                if (context.knownUrls().contains(url)) {
                    unchanged.add(url);
                    continue;
                }
                if (step.capReached()) {
                    notVisitedNew++;
                    continue;
                }
                if (!clock.instant().isBefore(deadline)) {
                    outOfTime = true;
                    break pages;
                }
                JobPosting posting = step.process(URI.create(url), null);
                if (posting != null) {
                    postings.add(posting);
                }
            }
            if (considered >= api.maxItems()) {
                itemLimit = items.size() >= size;
                break;
            }
            if (items.isEmpty() || items.size() < size) {
                break;
            }
            long total = total(root);
            if (total >= 0 && rawSeen >= total) {
                break;
            }
            if (newIds == 0) {
                notes.add(usable == 0 ? "page " + (p + 1) + ": no usable items"
                        : "page " + (p + 1) + " added no new ids (the endpoint may ignore paging)");
                break;
            }
        }

        if (outOfTime) {
            notes.add(RunNotes.stoppedEarly(maxRun));
        }
        if (itemLimit) {
            notes.add("Stopped at the limit of " + api.maxItems() + " items per run");
        }
        if (notVisitedNew > 0) {
            notes.add("Not visited because of the limit of " + cap + " detail fetches per run: " + notVisitedNew
                    + " new (retried next run)");
        }
        if (step != null) {
            skipped += step.skipped();
            if (step.bad() > 0) {
                notes.add(step.bad() + " bad page(s) skipped");
            }
            if (step.expired() > 0) {
                notes.add(step.expired() + " closed posting(s) not stored");
            }
            if (step.failed() > 0) {
                notes.add(step.failed() + " page fetch(es) failed");
            }
            if (step.robotsSkipped() > 0) {
                notes.add(step.robotsSkipped() + " page(s) " + RunNotes.ROBOTS_DISALLOWED);
            }
        }
        if (skipped > 0) {
            notes.add(skipped + " item(s) skipped (missing id, title or url)");
        }
        log.info("Recipe '{}': collected={} unchanged={} skipped={} items={}", name(), postings.size(),
                unchanged.size(), skipped, considered);
        return new FetchResult(postings, skipped, unchanged, notes.isEmpty() ? null : String.join("; ", notes));
    }

    /** One list page; any failure here fails the run, so earlier pages are never stored half-way. */
    private JsonNode requestPage(int page, int offset, int size) {
        String query = RequestTemplate.renderQuery(api.query(), page, offset, size);
        URI uri = URI.create(endpoint + (query.isEmpty() ? "" : "?" + query));
        FetchedPage reply = "POST".equals(api.method())
                ? fetcher.post(uri, RequestTemplate.renderBody(api.bodyTemplate(), page, offset, size))
                : fetcher.fetchJson(uri);
        int status = reply.status();
        if (status == 401) {
            throw new FetchException("json-api endpoint needs authentication (status 401)");
        }
        if (status / 100 != 2) {
            throw new FetchException("json-api endpoint returned status " + status);
        }
        String type = reply.contentType();
        if (type != null && !type.isBlank() && !type.toLowerCase(Locale.ROOT).contains("json")) {
            throw new FetchException("json-api endpoint reply is not JSON (login page?)");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(reply.body());
        } catch (RuntimeException e) {
            throw new FetchException("json-api endpoint reply is not JSON (login page?)");
        }
        if (root == null || !root.isContainer()) {
            throw new FetchException("json-api endpoint reply is not JSON (login page?)");
        }
        return root;
    }

    private List<JsonNode> items(JsonNode root) {
        List<JsonNode> selected = itemsPath.select(root);
        if (wildcardItems) {
            return selected;
        }
        if (selected.size() == 1 && selected.get(0).isArray()) {
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode n : selected.get(0)) {
                out.add(n);
                if (out.size() >= JsonApiValidator.MAX_SIZE) {
                    break;
                }
            }
            return out;
        }
        return List.of();
    }

    /** The reported total, or -1 when there is none. */
    private long total(JsonNode root) {
        if (totalPath == null) {
            return -1;
        }
        for (JsonNode n : totalPath.select(root)) {
            if (n.isIntegralNumber()) {
                return n.asLong();
            }
            if (n.isString() && n.asString().strip().matches("\\d{1,9}")) {
                return Long.parseLong(n.asString().strip());
            }
            return -1;
        }
        return -1;
    }

    /** The public url of an item, canonical, or null when it has none that is acceptable. */
    private String jobUrl(JsonFieldMapper.Parsed m, boolean hasDetail) {
        URI uri;
        try {
            if (api.jobUrlTemplate() != null) {
                if (!JOB_ID.matcher(m.id()).matches() || m.id().equals(".") || m.id().equals("..")) {
                    return null;
                }
                String built = api.jobUrlTemplate().replace("{id}", RequestTemplate.encode(m.id()));
                if (JsonApiValidator.unsafePath(built)) {
                    return null;
                }
                uri = URI.create(built);
            } else {
                if (m.url() == null || m.url().isBlank()) {
                    return null;
                }
                String raw = m.url().strip();
                if (raw.length() > JobEntity.MAX_URL || JsonApiValidator.unsafePath(raw)) {
                    return null;
                }
                uri = endpoint.resolve(raw);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        URI canonical = UrlCanonicalizer.canonicalize(uri, true);
        if (canonical == null || (httpsOnly && !"https".equals(canonical.getScheme()))
                || canonical.toString().length() > JobEntity.MAX_URL
                || JsonApiValidator.unsafePath(canonical.toString())) {
            return null;
        }
        if (hasDetail && !JsonApiValidator.sameHost(canonical, endpoint)) {
            return null;
        }
        return canonical.toString();
    }
}
