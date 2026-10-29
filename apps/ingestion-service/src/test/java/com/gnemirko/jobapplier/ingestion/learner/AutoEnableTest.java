package com.gnemirko.jobapplier.ingestion.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.learner.config.CloneProperties;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.dto.CloneStarted;
import com.gnemirko.jobapplier.ingestion.learner.service.CountrySelector;
import com.gnemirko.jobapplier.ingestion.learner.service.ProposalWriter;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeCloner;
import com.gnemirko.jobapplier.ingestion.learner.service.RecipeLearner;
import com.gnemirko.jobapplier.ingestion.learner.source.LlmClient;
import com.gnemirko.jobapplier.ingestion.learner.source.SampleRunner;
import com.gnemirko.jobapplier.ingestion.service.LearnerTestRig;
import com.gnemirko.jobapplier.ingestion.service.RecipeRegistry;
import com.gnemirko.jobapplier.ingestion.service.RecipeValidator;

/** Auto-enable of cloned country recipes, against a local HTTPS stub site. No model, no network. */
class AutoEnableTest {

    @TempDir Path tmp;
    StubSite site;
    StubScoring scoring;
    LearnerTestRig rig;
    Path out;
    Path recipesDir;
    Path autoDir;
    Path profile;

    @BeforeEach
    void setUp() throws Exception {
        site = new StubSite();
        scoring = new StubScoring();
        rig = new LearnerTestRig("127.0.0.1");
        out = tmp.resolve("proposed");
        recipesDir = tmp.resolve("recipes");
        autoDir = tmp.resolve("auto");
        profile = tmp.resolve("profile.yml");
        Files.createDirectories(recipesDir);
        Files.writeString(recipesDir.resolve("acme-ie-en.json"), template());
        site.ok("/robots.txt", "User-agent: *\nAllow: /\n");
        profile("[ie, de]");
    }

    @AfterEach
    void tearDown() throws Exception {
        site.close();
        scoring.close();
    }

    private void profile(String list) throws Exception {
        Files.writeString(profile, "skillsSummary: TOPSECRET\nlocationCountries: " + list + "\n");
    }

    private String template() {
        return "{\"name\":\"acme-ie-en\",\"enabled\":true,\"strategy\":\"sitemap\",\"sitemap\":{\"url\":\""
                + site.base() + "/ie-en/jobpostings-sitemap.xml\",\"urlPattern\":\"^https://127\\\\.0\\\\.0\\\\.1:"
                + site.port + "/ie-en/careers/jobdetails\\\\?id=.*\",\"maxUrls\":300,\"keepQuery\":true},"
                + "\"detail\":{\"extractor\":\"json-ld\",\"idFrom\":\"urlRegex:[?&]id=([^&]+)\"},"
                + "\"limits\":{\"maxDetailFetchesPerRun\":40}}";
    }

    private static String urlset(List<String> locs) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        locs.forEach(l -> sb.append("<url><loc>").append(l.replace("&", "&amp;")).append("</loc></url>"));
        return sb.append("</urlset>").toString();
    }

    private void locale(String loc, int n) {
        List<String> urls = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String id = loc + i;
            urls.add(site.base() + "/" + loc + "/careers/jobdetails?id=" + id);
            site.ok("/" + loc + "/careers/jobdetails?id=" + id,
                    "<html><head><script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"Engineer "
                            + id + "\",\"datePosted\":\"2026-10-01\",\"description\":\"d\","
                            + "\"hiringOrganization\":{\"name\":\"Acme\"},\"jobLocation\":{\"address\":"
                            + "{\"addressLocality\":\"X\",\"addressCountry\":\"UA\"}}}</script></head></html>");
        }
        site.ok("/" + loc + "/jobpostings-sitemap.xml", urlset(urls));
    }

    private void index(String... locs) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        for (String l : locs) {
            sb.append("<sitemap><loc>").append(site.base()).append('/').append(l)
                    .append("/jobpostings-sitemap.xml</loc></sitemap>");
        }
        site.ok("/sitemap-index.xml", sb.append("</sitemapindex>").toString());
    }

    private RecipeCloner cloner(CloneProperties cp) {
        return cloner(cp, new CountrySelector(profile));
    }

    private RecipeCloner cloner(CloneProperties cp, CountrySelector selector) {
        LearnerProperties props = new LearnerProperties(true, List.of("127.0.0.1"), 12000, scoring.baseUrl(),
                token(), out.toString(), Duration.ofSeconds(5), Duration.ofSeconds(5), 65536, Duration.ofMinutes(2));
        RecipeRegistry registry = new RecipeRegistry(recipesDir, autoDir, List.of(), r -> true);
        ProposalWriter writer = new ProposalWriter(out, recipesDir, autoDir);
        RecipeValidator validator = new RecipeValidator(registry.recipes().stream().map(Recipe::name).toList());
        SampleRunner sampler = new SampleRunner(rig.fetcher, Clock.systemUTC(), props);
        RecipeLearner learner = new RecipeLearner(props, rig.fetcher, rig.policy, new LlmClient(props), validator,
                sampler, writer, Runnable::run);
        return new RecipeCloner(props, cp, registry, validator, rig.fetcher, rig.policy, sampler, writer, learner,
                Clock.systemUTC(), selector, Runnable::run);
    }

    private String token() {
        try {
            Path f = tmp.resolve("token");
            Files.writeString(f, "0123456789abcdef-test-secret\n");
            return f.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CloneProperties on(int cap, String... blocked) {
        return new CloneProperties(Duration.ofMinutes(30), 80, true, cap, List.of(blocked), "unused");
    }

    private CloneProperties on() {
        return on(20);
    }

    private String run(RecipeCloner c) throws Exception {
        CloneStarted s = c.start("acme-ie-en", site.base() + "/sitemap-index.xml");
        return Files.readString(out.resolve(s.report() + ".report.txt"));
    }

    private List<String> files(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private void threeLocales() {
        locale("ie-en", 3);
        locale("de-de", 4);
        locale("fr-fr", 4);
        index("ie-en", "de-de", "fr-fr");
    }

    @Test
    void listedCountryIsWrittenEnabledAndTheOtherStaysAProposal() throws Exception {
        threeLocales();
        List<String> recipesBefore = files(recipesDir);

        String report = run(cloner(on()));

        assertThat(files(autoDir)).containsExactly("acme-de-de.json");
        assertThat(Files.readString(autoDir.resolve("acme-de-de.json"))).contains("\"enabled\" : true");
        assertThat(files(out)).contains("acme-de-de.json", "acme-fr-fr.json");
        assertThat(Files.readString(out.resolve("acme-fr-fr.json"))).contains("\"enabled\" : false");
        assertThat(files(recipesDir)).isEqualTo(recipesBefore);
        assertThat(report).contains("COVERED: 2", "ENABLED: 1", "not in profile: 1", "restart")
                .containsPattern("COVERED \\S+/de-de/\\S+ : acme-de-de.json .*\\[ENABLED\\]")
                .containsPattern("COVERED \\S+/fr-fr/\\S+ : acme-fr-fr.json .*\\[not in profile\\]")
                .doesNotContain("TOPSECRET");
        // The enabled file loads through the real registry, enabled.
        assertThat(new RecipeRegistry(recipesDir, autoDir, List.of(), r -> true).recipes())
                .filteredOn(r -> r.name().equals("acme-de-de")).singleElement()
                .satisfies(r -> assertThat(r.enabled()).isTrue());
    }

    @Test
    void regionEnablesEveryCloneInTheEuropeSetUpToTheCap() throws Exception {
        profile("[Europe]");
        locale("ie-en", 3);
        locale("de-de", 4);
        locale("fr-fr", 4);
        locale("us-en", 4);
        index("ie-en", "de-de", "fr-fr", "us-en");

        String report = run(cloner(on()));

        assertThat(files(autoDir)).containsExactly("acme-de-de.json", "acme-fr-fr.json");
        assertThat(report).containsPattern("us-en/\\S+ : acme-us-en.json .*\\[not in profile\\]");
    }

    @Test
    void emptyMissingAndBadProfilesEnableNothingAndSayWhy() throws Exception {
        threeLocales();
        String[][] cases = {
                {"[]", "missing or empty"},
                {"[europa]", "unknown country or region 'europa'"},
                {"ie", "must be a list"},
                {"[1, 2]", "must be a list of text"},
        };
        for (String[] c : cases) {
            profile(c[0]);
            String report = run(cloner(on(), new CountrySelector(profile)));
            assertThat(report).contains("nothing is enabled").contains(c[1]);
            assertThat(files(autoDir)).isEmpty();
        }
        Files.writeString(profile, "skillsSummary: TOPSECRET\n");
        assertThat(run(cloner(on()))).contains("missing or empty").doesNotContain("TOPSECRET");
        Files.writeString(profile, "[[[ not: yaml: TOPSECRET");
        assertThat(run(cloner(on()))).contains("could not be read as YAML").doesNotContain("TOPSECRET");
        Files.delete(profile);
        assertThat(run(cloner(on()))).contains("was not found");
        Files.createDirectory(profile);
        assertThat(run(cloner(on()))).contains("is a directory");
        Files.delete(profile);
        Files.writeString(profile, "skillsSummary: " + "x".repeat(70_000) + "\n");
        assertThat(run(cloner(on()))).contains("larger than");
        assertThat(files(autoDir)).isEmpty();
    }

    @Test
    void failedCloneIsNeverEnabled() throws Exception {
        locale("ie-en", 3);
        locale("de-de", 0);
        index("ie-en", "de-de");
        String report = run(cloner(on()));
        assertThat(report).contains("FAILED " + site.base() + "/de-de/");
        assertThat(files(autoDir)).isEmpty();
        assertThat(files(out)).doesNotContain("acme-de-de.json");
    }

    @Test
    void capLimitsWritesAndCountsFilesAlreadyThereValidOrNot() throws Exception {
        profile("[de, fr]");
        threeLocales();
        String report = run(cloner(on(1)));
        assertThat(files(autoDir)).containsExactly("acme-de-de.json");
        assertThat(report).containsPattern("fr-fr/\\S+ : acme-fr-fr.json .*\\[cap\\]");

        // An invalid file in the folder counts toward the cap.
        Files.delete(autoDir.resolve("acme-de-de.json"));
        Files.deleteIfExists(out.resolve("acme-de-de.json"));
        Files.writeString(autoDir.resolve("junk.json"), "not json");
        report = run(cloner(on(1)));
        assertThat(files(autoDir)).containsExactly("junk.json");
        assertThat(report).contains("[cap]");
    }

    @Test
    void existingAutoFileIsReportedAlreadyEnabledAndUntouched() throws Exception {
        threeLocales();
        run(cloner(on()));
        byte[] before = Files.readAllBytes(autoDir.resolve("acme-de-de.json"));
        String report = run(cloner(on()));   // a new cloner loads the auto folder, as after a restart
        assertThat(Files.readAllBytes(autoDir.resolve("acme-de-de.json"))).isEqualTo(before);
        assertThat(files(autoDir)).containsExactly("acme-de-de.json");
        assertThat(report).contains("[already enabled]");
    }

    @Test
    void nameTakenInTheHandWrittenFolderIsReportedAndNotWritten() throws Exception {
        threeLocales();
        Files.writeString(recipesDir.resolve("acme-de-de.json"),
                template().replace("\"acme-ie-en\"", "\"acme-de-de\"").replace("/ie-en/", "/de-de/"));
        String report = run(cloner(on()));
        assertThat(files(autoDir)).isEmpty();
        assertThat(report).contains("[name taken]");
    }

    @Test
    void blockedNameIsNeverEnabled() throws Exception {
        threeLocales();
        String report = run(cloner(on(20, "acme-de-de")));
        assertThat(files(autoDir)).isEmpty();
        assertThat(report).containsPattern("de-de/\\S+ : acme-de-de.json .*\\[blocked\\]");
        assertThat(files(out)).contains("acme-de-de.json");   // still a proposal
    }

    @Test
    void switchOffBehavesAsBeforeAndReadsNoProfile() throws Exception {
        threeLocales();
        Files.writeString(profile, "TOPSECRET: [unreadable");
        CloneProperties off = new CloneProperties(Duration.ofMinutes(30), 80);
        String report = run(cloner(off, new CountrySelector(profile) {
            @Override
            public com.gnemirko.jobapplier.ingestion.learner.dto.ProfileCountries load() {
                throw new AssertionError("the profile must not be read");
            }
        }));
        assertThat(files(autoDir)).isEmpty();
        assertThat(report).doesNotContain("auto-enable").doesNotContain("ENABLED").doesNotContain("[");
        assertThat(files(out)).contains("acme-de-de.json", "acme-fr-fr.json");
    }

    @Test
    void oneLocalePerCountryPrefersEnglish() throws Exception {
        profile("[ch]");
        locale("ie-en", 3);
        locale("ch-de", 4);
        locale("ch-en", 4);
        index("ie-en", "ch-de", "ch-en");
        String report = run(cloner(on()));
        assertThat(files(autoDir)).containsExactly("acme-ch-en.json");
        assertThat(report).contains("[ENABLED]", "[same country already enabled]");
    }

    @Test
    void existingDisabledProposalOfAListedCountryIsTestedAgainAndEnabledFromTheTemplate() throws Exception {
        threeLocales();
        // first run with auto-enable off leaves disabled proposals
        run(cloner(new CloneProperties(Duration.ofMinutes(30), 80)));
        assertThat(files(out)).contains("acme-de-de.json", "acme-fr-fr.json");
        String proposalBefore = Files.readString(out.resolve("acme-de-de.json"));
        Files.writeString(out.resolve("acme-de-de.json"), proposalBefore.replace("Engineer", "Tampered"));
        byte[] tampered = Files.readAllBytes(out.resolve("acme-de-de.json"));

        String report = run(cloner(on()));

        assertThat(Files.readAllBytes(out.resolve("acme-de-de.json"))).isEqualTo(tampered);
        assertThat(files(autoDir)).containsExactly("acme-de-de.json");
        assertThat(Files.readString(autoDir.resolve("acme-de-de.json"))).contains("\"enabled\" : true");
        assertThat(report).contains("tested again", "[ENABLED]").contains("SKIPPED " + site.base() + "/fr-fr/")
                .contains("already proposed");
    }

    @Test
    void blockedRunStillEnablesWhatAlreadyPassed() throws Exception {
        locale("ie-en", 3);
        locale("de-de", 4);
        site.routes.put("/fr-fr/jobpostings-sitemap.xml", new StubSite.Route(403, "denied"));
        index("ie-en", "de-de", "fr-fr");
        String report = run(cloner(on()));
        assertThat(report).contains("stopped: host blocked");
        assertThat(files(autoDir)).containsExactly("acme-de-de.json");
    }

    @Test
    void writeErrorIsFailedAndNotCounted() throws Exception {
        threeLocales();
        Files.writeString(autoDir, "a file where the folder should be");
        String report = run(cloner(on()));
        assertThat(report).contains("could not write the enabled recipe").doesNotContain("[ENABLED]")
                .contains("FAILED: 1");
    }

    @Test
    void registryLoadsAutoFolderAfterRecipesSkipsClashesAndForeignHosts() throws Exception {
        Files.createDirectories(autoDir);
        Files.writeString(autoDir.resolve("acme-de-de.json"),
                template().replace("\"acme-ie-en\"", "\"acme-de-de\"").replace("/ie-en/", "/de-de/"));
        Files.writeString(autoDir.resolve("clash.json"), template());   // same name as recipes/acme-ie-en
        Files.writeString(autoDir.resolve("foreign.json"), template().replace("\"acme-ie-en\"", "\"foreign\"")
                .replace("127.0.0.1", "evil.example.com").replace("127\\\\.0\\\\.0\\\\.1", "evil\\\\.example\\\\.com"));
        RecipeRegistry r = new RecipeRegistry(recipesDir, autoDir, List.of(),
                x -> !x.sitemap().url().contains("evil.example.com"));
        assertThat(r.recipes()).extracting(Recipe::name).containsExactly("acme-ie-en", "acme-de-de");
        assertThat(new RecipeRegistry(recipesDir, List.of()).recipes()).extracting(Recipe::name)
                .containsExactly("acme-ie-en");
    }

    @Test
    void foldersMayNotNestAndWritesNeverOverwriteOrFollowLinks() throws Exception {
        assertThatThrownBy(() -> new RecipeRegistry(recipesDir, recipesDir.resolve("auto"), List.of(), r -> true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProposalWriter(out, recipesDir, out.resolve("auto")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProposalWriter(out, recipesDir, recipesDir))
                .isInstanceOf(IllegalStateException.class);

        Path real = tmp.resolve("elsewhere");
        Files.createDirectories(real);
        Path link = tmp.resolve("linked");
        Files.createSymbolicLink(link, real);
        ProposalWriter linked = new ProposalWriter(out, recipesDir, link);
        Recipe r = new RecipeRegistry(recipesDir, List.of()).recipes().get(0);
        RecipeValidator v = new RecipeValidator(List.of());
        assertThatThrownBy(() -> linked.writeEnabled(r, v)).isInstanceOf(java.io.IOException.class);
        assertThat(files(real)).isEmpty();

        ProposalWriter w = new ProposalWriter(out, recipesDir, autoDir);
        assertThat(w.writeEnabled(r, v)).isTrue();
        assertThat(w.writeEnabled(r, v)).isFalse();
        assertThat(w.countAuto()).isEqualTo(1);
        assertThat(files(recipesDir)).containsExactly("acme-ie-en.json");
    }

    @Test
    void countrySelectorMapsLocalesAndRegions() throws Exception {
        assertThat(CountrySelector.countryOf("ie-en")).isEqualTo("ie");
        assertThat(CountrySelector.countryOf("uk-en")).isEqualTo("gb");
        assertThat(CountrySelector.countryOf("zz-en")).isNull();
        assertThat(CountrySelector.countryOf("eng-us")).isNull();
        profile("[uk, eu, ch]");
        var c = new CountrySelector(profile).load();
        assertThat(c.codes()).startsWith("gb", "at").contains("ie", "ch").doesNotContain("no");
        assertThat(c.problem()).isNull();
        profile("[europe]");
        assertThat(new CountrySelector(profile).load().codes()).contains("ie", "no", "gb", "ua").doesNotContain("tr",
                "ru", "by");
    }

    @Test
    void templateCountryIsAlreadyCoveredSoAnotherLocaleOfItIsNotEnabled() throws Exception {
        profile("[ie]");
        locale("ie-en", 3);
        locale("ie-ga", 4);
        index("ie-en", "ie-ga");
        String report = run(cloner(on()));
        assertThat(files(autoDir)).isEmpty();
        assertThat(report).contains("[same country already enabled]");
    }

    @Test
    void invalidFileInAutoFolderIsReportedAndDoesNotCoverItsCountry() throws Exception {
        profile("[ch]");
        locale("ie-en", 3);
        locale("ch-de", 4);
        locale("ch-en", 4);
        index("ie-en", "ch-de", "ch-en");
        Files.createDirectories(autoDir);
        Files.createSymbolicLink(autoDir.resolve("acme-ch-en.json"), tmp.resolve("missing-target"));
        String report = run(cloner(on()));
        assertThat(report).contains("[invalid file present]");
        assertThat(files(autoDir)).containsExactly("acme-ch-de.json", "acme-ch-en.json");
        assertThat(report).containsPattern("ch-de/\\S+ : acme-ch-de.json .*\\[ENABLED\\]");
    }

    @Test
    void listedCountriesAreSampledFirstAndLostOnesAreMarked() throws Exception {
        profile("[de]");
        locale("ie-en", 3);
        locale("fr-fr", 4);
        locale("de-de", 4);
        index("ie-en", "fr-fr", "de-de");
        String report = run(cloner(new CloneProperties(Duration.ofMinutes(30), 1, true, 20, List.of(), "unused")));
        assertThat(files(autoDir)).containsExactly("acme-de-de.json");
        assertThat(report).containsPattern("fr-fr/\\S+ : (run|cap).*\\[not in profile\\]");
        // with the listed country last in the index and a clone cap of 1, a listed one that is cut is marked
        profile("[de, fr]");
        Files.delete(autoDir.resolve("acme-de-de.json"));
        Files.deleteIfExists(out.resolve("acme-de-de.json"));
        Files.deleteIfExists(out.resolve("acme-fr-fr.json"));
        report = run(cloner(new CloneProperties(Duration.ofMinutes(30), 1, true, 20, List.of(), "unused")));
        assertThat(report).contains("[not tested: clone cap]");
    }

    @Test
    void partialWriteIsDeletedAndDoesNotCountTowardTheCap() throws Exception {
        ProposalWriter w = new ProposalWriter(out, recipesDir, autoDir) {
            @Override
            protected void writeContent(Path file, String text) throws java.io.IOException {
                Files.writeString(file, text.substring(0, 5));
                throw new java.io.IOException("disk full");
            }
        };
        Recipe r = new RecipeRegistry(recipesDir, List.of()).recipes().get(0);
        assertThatThrownBy(() -> w.writeEnabled(r, new RecipeValidator(List.of())))
                .isInstanceOf(java.io.IOException.class);
        assertThat(files(autoDir)).isEmpty();
        assertThat(w.countAuto()).isZero();
    }

    @Test
    void hostIsRecheckedAgainstTheAllowListBeforeAWrite() throws Exception {
        Recipe r = new RecipeRegistry(recipesDir, List.of()).recipes().get(0);
        LearnerProperties ok = new LearnerProperties(true, List.of("127.0.0.1"), 12000, scoring.baseUrl(), token(),
                out.toString(), Duration.ofSeconds(5), Duration.ofSeconds(5), 65536, Duration.ofMinutes(2));
        LearnerProperties other = new LearnerProperties(true, List.of("example.com"), 12000, scoring.baseUrl(),
                token(), out.toString(), Duration.ofSeconds(5), Duration.ofSeconds(5), 65536, Duration.ofMinutes(2));
        assertThat(RecipeCloner.hostAllowed(ok, r)).isTrue();
        assertThat(RecipeCloner.hostAllowed(other, r)).isFalse();
    }

    @Test
    void registryHostFilterWorksThroughTheRealLearnerPropertyPath() throws Exception {
        Files.createDirectories(autoDir);
        Files.writeString(autoDir.resolve("acme-de-de.json"),
                template().replace("\"acme-ie-en\"", "\"acme-de-de\"").replace("/ie-en/", "/de-de/"));
        var on = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        on.addBean("p", new LearnerProperties(true, List.of("example.com"), 12000, scoring.baseUrl(), token(),
                out.toString(), Duration.ofSeconds(5), Duration.ofSeconds(5), 65536, Duration.ofMinutes(2)));
        var off = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        assertThat(new RecipeRegistry(recipesDir.toString(), autoDir.toString(), out.toString(), List.of(),
                on.getBeanProvider(LearnerProperties.class)).recipes()).extracting(Recipe::name)
                .containsExactly("acme-ie-en");
        assertThat(new RecipeRegistry(recipesDir.toString(), autoDir.toString(), out.toString(), List.of(),
                off.getBeanProvider(LearnerProperties.class)).recipes()).extracting(Recipe::name)
                .containsExactly("acme-ie-en", "acme-de-de");
        assertThatThrownBy(() -> new RecipeRegistry(recipesDir.toString(), autoDir.toString(),
                autoDir.resolve("p").toString(), List.of(), off.getBeanProvider(LearnerProperties.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void yamlAnchorsAndAliasesMakeTheProfileInvalid() throws Exception {
        Files.writeString(profile, "x: &a ie\nlocationCountries: [*a]\n");
        var c = new CountrySelector(profile).load();
        assertThat(c.codes()).isEmpty();
        assertThat(c.problem()).contains("anchors or aliases");
    }
}
