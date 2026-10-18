package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.FetchContext;
import com.gnemirko.jobapplier.ingestion.dto.FetchResult;
import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The workday strategy: page through a tenant's JSON search, read the JSON detail of every posting
 * not stored yet and map it with the fixed Workday mapping. A failed or unusable search page fails
 * the run (nothing is stored); detail problems only skip that posting. Known jobs are not
 * refetched, only reported as seen.
 */
@Slf4j
public class WorkdayRecipeJobSource implements JobSource {

    static final int PAGE_SIZE = 20;
    private static final Pattern EXTERNAL_PATH = Pattern.compile("^/job/[^?#\\s]+$");
    private static final Pattern ENCODED_TRAVERSAL = Pattern.compile("%2e|%2f|%5c", Pattern.CASE_INSENSITIVE);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Recipe recipe;
    private final Recipe.Workday workday;
    private final PoliteFetcher fetcher;
    private final Clock clock;
    private final Duration maxRun;
    /** Scheme and authority, no trailing slash: {@code https://{host}} in production. */
    private final String origin;

    public WorkdayRecipeJobSource(Recipe recipe, PoliteFetcher fetcher, Clock clock, Duration maxRun) {
        this(recipe, fetcher, clock, maxRun, URI.create("https://" + recipe.workday().host()));
    }

    /** Tests point this at an http stub. */
    WorkdayRecipeJobSource(Recipe recipe, PoliteFetcher fetcher, Clock clock, Duration maxRun, URI base) {
        this.recipe = recipe;
        this.workday = recipe.workday();
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxRun = maxRun;
        this.origin = base.toString().replaceAll("/+$", "");
    }

    @Override
    public String name() {
        return recipe.name();
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant deadline = clock.instant().plus(maxRun);
        int cap = recipe.limits().maxDetailFetchesPerRun();
        String publicPrefix = origin + "/" + workday.site();
        String apiPrefix = origin + "/wday/cxs/" + workday.tenant() + "/" + workday.site();
        URI searchUri = URI.create(apiPrefix + "/jobs");

        DetailStep step = new DetailStep(fetcher::fetchJson, name(), (page, link, updatedAt) -> WorkdayParser
                .parseDetail(page.body(), name(), workday.company(), link.toString().substring(publicPrefix.length()),
                        link.toString(), updatedAt),
                cap);
        Set<String> seenPaths = new HashSet<>();
        Set<String> unchanged = new LinkedHashSet<>();
        List<JobPosting> postings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int badPaths = 0;
        int notVisitedNew = 0;
        int total = 0;
        boolean outOfTime = false;

        pages:
        for (int page = 0; page < workday.maxPages(); page++) {
            if (outOfTime(deadline)) {
                outOfTime = true;
                break;
            }
            WorkdayParser.SearchPage listed = search(searchUri, page * PAGE_SIZE);
            if (page == 0) {
                total = listed.total();
            }
            for (String path : listed.paths()) {
                URI fetchUri = null;
                String url = null;
                if (validPath(path)) {
                    url = publicPrefix + path;
                    fetchUri = uriOrNull(apiPrefix + path);
                    if (fetchUri != null && uriOrNull(url) == null) {
                        fetchUri = null;
                    }
                }
                if (fetchUri == null) {
                    log.warn("Recipe '{}': posting path rejected", name());
                    badPaths++;
                    continue;
                }
                if (!seenPaths.add(path)) {
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
                if (outOfTime(deadline)) {
                    outOfTime = true;
                    break pages;
                }
                JobPosting posting = step.process(fetchUri, URI.create(url), null);
                if (posting != null) {
                    postings.add(posting);
                }
            }
            if (listed.paths().size() < PAGE_SIZE || (total > 0 && (page + 1) * PAGE_SIZE >= total)) {
                break;
            }
        }

        if (outOfTime) {
            notes.add("Stopped early: run time budget of " + maxRun + " reached");
        } else if (total > seenPaths.size()) {
            notes.add("Workday reported " + total + " postings but " + seenPaths.size() + " unique were listed");
        }
        if (notVisitedNew > 0) {
            notes.add("Not visited because of the limit of " + cap + " detail fetches per run: " + notVisitedNew
                    + " new (retried next run)");
        }
        if (badPaths > 0) {
            notes.add(badPaths + " posting path(s) rejected");
        }
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
            notes.add(step.robotsSkipped() + " page(s) disallowed by robots.txt");
        }
        int skipped = step.skipped() + badPaths;
        log.info("Recipe '{}': collected={} unchanged={} skipped={} detailFetches={}", name(), postings.size(),
                unchanged.size(), skipped, step.detailFetches());
        return new FetchResult(postings, skipped, unchanged, notes.isEmpty() ? null : String.join("; ", notes));
    }

    /** One search page; any failure here fails the run, so earlier pages are never stored half-way. */
    private WorkdayParser.SearchPage search(URI searchUri, int offset) {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("appliedFacets");
        body.put("limit", PAGE_SIZE);
        body.put("offset", offset);
        body.put("searchText", workday.searchText());
        FetchedPage reply = fetcher.post(searchUri, body);
        if (reply.status() / 100 != 2) {
            throw new FetchException("Workday search returned status " + reply.status());
        }
        return WorkdayParser.parseSearch(reply.body())
                .orElseThrow(() -> new FetchException("Workday search returned an unusable answer"));
    }

    static boolean validPath(String path) {
        return path != null && path.length() <= JobEntity.MAX_EXTERNAL_ID && EXTERNAL_PATH.matcher(path).matches()
                && !path.contains("..") && !path.contains("//") && !ENCODED_TRAVERSAL.matcher(path).find();
    }

    private static URI uriOrNull(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean outOfTime(Instant deadline) {
        return !clock.instant().isBefore(deadline);
    }
}
