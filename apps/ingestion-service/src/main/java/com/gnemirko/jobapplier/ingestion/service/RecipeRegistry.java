package com.gnemirko.jobapplier.ingestion.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jsoup.select.QueryParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.service.ProposalWriter;
import com.gnemirko.jobapplier.ingestion.source.JobSource;
import com.gnemirko.jobapplier.ingestion.source.recipe.EmbeddedJsonParser;
import com.gnemirko.jobapplier.ingestion.source.recipe.JsonApiValidator;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

/** Loads and validates recipe files once at startup. A bad file is logged and skipped. */
@Service
@Slf4j
public class RecipeRegistry {

    static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,64}");
    static final int MAX_REGEX_LENGTH = 200;
    private static final Pattern WORKDAY_HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.myworkdayjobs\\.com");
    private static final Pattern WORKDAY_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final int MAX_SEARCH_TEXT = 100;
    /** A group holding a quantifier that is itself quantified, e.g. (a+)+ or (a*){2,}. */
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^()]*[+*}][^()]*\\)\\s*[+*{]");

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final List<Recipe> recipes;

    @Autowired
    public RecipeRegistry(@Value("${ingestion.recipes.dir:./recipes}") String dir,
            @Value("${ingestion.recipes.auto-dir:./recipes-auto}") String autoDir,
            @Value("${ingestion.learner.output-dir:./recipes-proposed}") String proposedDir,
            List<JobSource> builtIns, ObjectProvider<LearnerProperties> learner) {
        this(Path.of(dir), Path.of(autoDir), Path.of(proposedDir), builtIns.stream().map(JobSource::name).toList(),
                hostFilter(learner.getIfAvailable()));
    }

    public RecipeRegistry(Path dir, Collection<String> reservedNames) {
        this(dir, null, reservedNames, r -> true);
    }

    /**
     * Loads {@code dir} first, then {@code autoDir} (the folder the clone run writes enabled recipes into). A name in
     * both is an error that skips the auto one; an auto recipe the filter rejects is skipped.
     */
    public RecipeRegistry(Path dir, Path autoDir, Collection<String> reservedNames, Predicate<Recipe> autoFilter) {
        this(dir, autoDir, null, reservedNames, autoFilter);
    }

    /** As above, also refusing any equality or nesting with the review folder (checked even when the learner is off). */
    public RecipeRegistry(Path dir, Path autoDir, Path proposedDir, Collection<String> reservedNames,
            Predicate<Recipe> autoFilter) {
        Path r = ProposalWriter.canonical(dir);
        if (autoDir != null) {
            Path a = ProposalWriter.canonical(autoDir);
            if (a.startsWith(r) || r.startsWith(a)) {
                throw new IllegalStateException("ingestion.recipes.auto-dir (" + a
                        + ") must not be, contain or sit inside the recipes folder (" + r + ")");
            }
            if (proposedDir != null) {
                Path p = ProposalWriter.canonical(proposedDir);
                if (a.startsWith(p) || p.startsWith(a)) {
                    throw new IllegalStateException("ingestion.recipes.auto-dir (" + a
                            + ") must not be, contain or sit inside the review folder (" + p + ")");
                }
            }
        }
        if (proposedDir != null) {
            Path p = ProposalWriter.canonical(proposedDir);
            if (p.startsWith(r) || r.startsWith(p)) {
                throw new IllegalStateException("the review folder (" + p
                        + ") must not be, contain or sit inside the recipes folder (" + r + ")");
            }
        }
        Set<String> taken = new HashSet<>();
        for (String n : reservedNames) {
            taken.add(n.toLowerCase(Locale.ROOT));
        }
        List<Recipe> loaded = new ArrayList<>(load(dir, taken, false, x -> true));
        if (autoDir != null) {
            loaded.addAll(load(autoDir, taken, true, autoFilter));
        }
        this.recipes = List.copyOf(loaded);
    }

    private static Predicate<Recipe> hostFilter(LearnerProperties learner) {
        if (learner == null) {
            return r -> true;
        }
        return r -> {
            String host = hostOf(r);
            return host != null && learner.isAllowed(host);
        };
    }

    private static String hostOf(Recipe r) {
        try {
            if (r.sitemap() != null) {
                return URI.create(r.sitemap().url()).getHost();
            }
            if (r.listing() != null) {
                return URI.create(r.listing().urlTemplate().replace("{page}", "1")).getHost();
            }
            if (r.workday() != null) {
                return r.workday().host();
            }
            if (r.jsonApi() != null) {
                return URI.create(r.jsonApi().url()).getHost();
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** Valid recipes (enabled or not), in file-name order. */
    public List<Recipe> recipes() {
        return recipes;
    }

    private List<Recipe> load(Path dir, Set<String> taken, boolean auto, Predicate<Recipe> accept) {
        List<Recipe> loaded = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            if (auto) {
                log.info("Auto recipes folder {} not found; nothing has been auto-enabled yet (not a problem)", dir);
            } else {
                log.info("Recipes directory {} not found; no recipe sources", dir);
            }
            return loaded;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    // the auto folder is written by the service: only plain files, never links
                    .filter(p -> !auto || Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot list recipes directory {}: {}", dir, e.toString());
            return loaded;
        }
        for (Path file : files) {
            try {
                Recipe recipe = mapper.readValue(Files.readString(file), Recipe.class);
                validate(recipe);
                if (auto && !accept.test(recipe)) {
                    throw new IllegalArgumentException("host is not in ingestion.learner.allowed-hosts");
                }
                if (!taken.add(recipe.name().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("duplicate or reserved name '" + recipe.name() + "'");
                }
                loaded.add(recipe);
                log.info("Loaded recipe '{}' from {} (enabled={})", recipe.name(), file.getFileName(), recipe.enabled());
            } catch (IOException | RuntimeException e) {
                log.warn("Skipping recipe file {}: {}", file.getFileName(), e.toString());
            }
        }
        return loaded;
    }

    /** Throws IllegalArgumentException describing the first problem found. */
    public static void validate(Recipe r) {
        if (r == null) {
            throw new IllegalArgumentException("empty recipe");
        }
        if (r.name() == null || !NAME.matcher(r.name()).matches()) {
            throw new IllegalArgumentException("name must match [a-z0-9-]{1,64}");
        }
        Recipe.Listing l = r.listing();
        Recipe.Sitemap sm = r.sitemap();
        Recipe.Workday wd = r.workday();
        Recipe.Detail d = r.detail();
        if (r.jsonApi() != null && !Recipe.STRATEGY_JSON_API.equals(r.strategy())) {
            throw new IllegalArgumentException("jsonApi block does not belong to strategy " + r.strategy());
        }
        if (Recipe.STRATEGY_PAGINATED_LISTING.equals(r.strategy())) {
            if (sm != null) {
                throw new IllegalArgumentException("sitemap block does not belong to strategy paginated-listing");
            }
            if (wd != null) {
                throw new IllegalArgumentException("workday block does not belong to strategy paginated-listing");
            }
            if (l == null) {
                throw new IllegalArgumentException("listing is required");
            }
            validateListing(l);
        } else if (Recipe.STRATEGY_SITEMAP.equals(r.strategy())) {
            if (l != null) {
                throw new IllegalArgumentException("listing block does not belong to strategy sitemap");
            }
            if (wd != null) {
                throw new IllegalArgumentException("workday block does not belong to strategy sitemap");
            }
            if (sm == null) {
                throw new IllegalArgumentException("sitemap is required");
            }
            validateSitemap(sm);
        } else if (Recipe.STRATEGY_WORKDAY.equals(r.strategy())) {
            if (l != null || sm != null) {
                throw new IllegalArgumentException("listing and sitemap blocks do not belong to strategy workday");
            }
            if (d != null) {
                throw new IllegalArgumentException("detail block does not belong to strategy workday");
            }
            if (wd == null) {
                throw new IllegalArgumentException("workday is required");
            }
            validateWorkday(wd);
        } else if (Recipe.STRATEGY_JSON_API.equals(r.strategy())) {
            if (l != null || sm != null || wd != null) {
                throw new IllegalArgumentException("listing, sitemap and workday blocks do not belong to strategy json-api");
            }
            JsonApiValidator.validate(r.jsonApi(), d);
            if (d != null) {
                validateDetail(d);
            }
        } else {
            throw new IllegalArgumentException("unsupported strategy " + r.strategy());
        }
        if (!Recipe.STRATEGY_WORKDAY.equals(r.strategy()) && !Recipe.STRATEGY_JSON_API.equals(r.strategy())) {
            if (d == null) {
                throw new IllegalArgumentException("detail is required");
            }
            validateDetail(d);
        }
        int cap = r.limits().maxDetailFetchesPerRun();
        if (cap < 1 || cap > 1000) {
            throw new IllegalArgumentException("limits.maxDetailFetchesPerRun must be 1..1000");
        }
    }

    private static void validateWorkday(Recipe.Workday w) {
        if (w.host() == null || w.host().length() > 253 || hasLongLabel(w.host()) || !WORKDAY_HOST.matcher(w.host()).matches()) {
            throw new IllegalArgumentException("workday.host must be a host name ending .myworkdayjobs.com");
        }
        if (w.tenant() == null || !WORKDAY_NAME.matcher(w.tenant()).matches()) {
            throw new IllegalArgumentException("workday.tenant must match [A-Za-z0-9_-]{1,64}");
        }
        if (w.site() == null || !WORKDAY_NAME.matcher(w.site()).matches()) {
            throw new IllegalArgumentException("workday.site must match [A-Za-z0-9_-]{1,64}");
        }
        if (w.searchText() == null || w.searchText().length() > MAX_SEARCH_TEXT || hasControlChars(w.searchText())) {
            throw new IllegalArgumentException(
                    "workday.searchText is required, at most " + MAX_SEARCH_TEXT + " characters, no control characters");
        }
        if (w.company() == null || w.company().isBlank() || w.company().length() > JobEntity.MAX_TEXT
                || hasControlChars(w.company())) {
            throw new IllegalArgumentException("workday.company is required (no control characters)");
        }
        if (w.maxPages() < 1 || w.maxPages() > 200) {
            throw new IllegalArgumentException("workday.maxPages must be 1..200");
        }
    }

    private static boolean hasLongLabel(String host) {
        return java.util.Arrays.stream(host.split("\\.", -1)).anyMatch(l -> l.length() > 63);
    }

    private static boolean hasControlChars(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }

    private static void validateListing(Recipe.Listing l) {
        checkTemplate(l.urlTemplate());
        if (l.firstPage() < 0 || l.firstPage() > 1_000_000) {
            throw new IllegalArgumentException("firstPage out of range");
        }
        if (l.maxPages() == null || l.maxPages() < 1 || l.maxPages() > 500) {
            throw new IllegalArgumentException("listing.maxPages must be 1..500");
        }
        if (l.linkSelector() == null || l.linkSelector().isBlank()) {
            throw new IllegalArgumentException("listing.linkSelector is required");
        }
        try {
            QueryParser.parse(l.linkSelector());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad linkSelector: " + e.getMessage());
        }
        if (l.linkPattern() == null || l.linkPattern().isBlank()) {
            throw new IllegalArgumentException("listing.linkPattern is required");
        }
        checkRegex(l.linkPattern(), "linkPattern");
    }

    private static void validateSitemap(Recipe.Sitemap sm) {
        checkHttpsUrl(sm.url(), "sitemap.url");
        if (sm.urlPattern() == null || sm.urlPattern().isBlank()) {
            throw new IllegalArgumentException("sitemap.urlPattern is required");
        }
        checkRegex(sm.urlPattern(), "urlPattern");
        if (sm.maxUrls() < 1 || sm.maxUrls() > 5000) {
            throw new IllegalArgumentException("sitemap.maxUrls must be 1..5000");
        }
    }

    private static void validateDetail(Recipe.Detail d) {
        if (Recipe.EXTRACTOR_JSON_LD.equals(d.extractor())) {
            if (d.embeddedJson() != null) {
                throw new IllegalArgumentException("embeddedJson block does not belong to extractor json-ld");
            }
            String idFrom = d.idFrom();
            if (idFrom == null) {
                throw new IllegalArgumentException("detail.idFrom is required");
            }
            if (idFrom.startsWith(Recipe.ID_URL_REGEX_PREFIX)) {
                Pattern p = checkRegex(idFrom.substring(Recipe.ID_URL_REGEX_PREFIX.length()), "idFrom");
                if (p.matcher("").groupCount() != 1) {
                    throw new IllegalArgumentException("idFrom regex needs exactly one group");
                }
            } else if (!Recipe.ID_JSONLD.equals(idFrom)) {
                throw new IllegalArgumentException("idFrom must be 'jsonld' or 'urlRegex:<regex>'");
            }
        } else if (Recipe.EXTRACTOR_EMBEDDED_JSON.equals(d.extractor())) {
            if (d.idFrom() != null) {
                throw new IllegalArgumentException("idFrom does not belong to extractor embedded-json; map fields.id");
            }
            new EmbeddedJsonParser(d.embeddedJson());
        } else {
            throw new IllegalArgumentException("unsupported extractor " + d.extractor());
        }
    }

    private static void checkTemplate(String template) {
        if (template == null || template.indexOf("{page}") < 0
                || template.indexOf("{page}") != template.lastIndexOf("{page}")) {
            throw new IllegalArgumentException("listing.urlTemplate must contain {page} once");
        }
        checkHttpsUrl(template.replace("{page}", "1"), "urlTemplate");
    }

    private static void checkHttpsUrl(String url, String label) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(label + " must be an https URL with a host and no credentials");
        }
    }

    static Pattern checkRegex(String regex, String label) {
        if (regex.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException(label + " longer than " + MAX_REGEX_LENGTH + " chars");
        }
        if (NESTED_QUANTIFIER.matcher(regex).find()) {
            throw new IllegalArgumentException(label + " has nested quantifiers");
        }
        try {
            return Pattern.compile(regex);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad " + label + " regex: " + e.getMessage());
        }
    }
}
