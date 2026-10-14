package com.gnemirko.jobapplier.ingestion.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jsoup.select.QueryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.source.JobSource;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Loads and validates recipe files once at startup. A bad file is logged and skipped. */
@Service
public class RecipeRegistry {

    private static final Logger log = LoggerFactory.getLogger(RecipeRegistry.class);

    static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,64}");
    static final int MAX_REGEX_LENGTH = 200;
    /** A group holding a quantifier that is itself quantified, e.g. (a+)+ or (a*){2,}. */
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^()]*[+*}][^()]*\\)\\s*[+*{]");

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final List<Recipe> recipes;

    @Autowired
    public RecipeRegistry(@Value("${ingestion.recipes.dir:./recipes}") String dir, List<JobSource> builtIns) {
        this(Path.of(dir), builtIns.stream().map(JobSource::name).toList());
    }

    public RecipeRegistry(Path dir, Collection<String> reservedNames) {
        this.recipes = List.copyOf(load(dir, reservedNames));
    }

    /** Valid recipes (enabled or not), in file-name order. */
    public List<Recipe> recipes() {
        return recipes;
    }

    private List<Recipe> load(Path dir, Collection<String> reservedNames) {
        List<Recipe> loaded = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            log.info("Recipes directory {} not found; no recipe sources", dir);
            return loaded;
        }
        Set<String> taken = new HashSet<>();
        for (String n : reservedNames) {
            taken.add(n.toLowerCase(Locale.ROOT));
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot list recipes directory {}: {}", dir, e.toString());
            return loaded;
        }
        for (Path file : files) {
            try {
                Recipe recipe = mapper.readValue(Files.readString(file), Recipe.class);
                validate(recipe);
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
    static void validate(Recipe r) {
        if (r == null) {
            throw new IllegalArgumentException("empty recipe");
        }
        if (r.name() == null || !NAME.matcher(r.name()).matches()) {
            throw new IllegalArgumentException("name must match [a-z0-9-]{1,64}");
        }
        if (!Recipe.STRATEGY_PAGINATED_LISTING.equals(r.strategy())) {
            throw new IllegalArgumentException("unsupported strategy " + r.strategy());
        }
        Recipe.Listing l = r.listing();
        Recipe.Detail d = r.detail();
        if (l == null || d == null) {
            throw new IllegalArgumentException("listing and detail are required");
        }
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
        if (!Recipe.EXTRACTOR_JSON_LD.equals(d.extractor())) {
            throw new IllegalArgumentException("unsupported extractor " + d.extractor());
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
        int cap = r.limits().maxDetailFetchesPerRun();
        if (cap < 1 || cap > 1000) {
            throw new IllegalArgumentException("limits.maxDetailFetchesPerRun must be 1..1000");
        }
    }

    private static void checkTemplate(String template) {
        if (template == null || template.indexOf("{page}") < 0
                || template.indexOf("{page}") != template.lastIndexOf("{page}")) {
            throw new IllegalArgumentException("listing.urlTemplate must contain {page} once");
        }
        URI uri;
        try {
            uri = URI.create(template.replace("{page}", "1"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("urlTemplate is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("urlTemplate must be an https URL with a host and no credentials");
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
