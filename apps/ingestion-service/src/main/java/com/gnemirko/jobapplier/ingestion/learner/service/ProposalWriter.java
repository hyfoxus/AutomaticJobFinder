package com.gnemirko.jobapplier.ingestion.learner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.service.RecipeRegistry;
import com.gnemirko.jobapplier.ingestion.service.RecipeValidator;

/**
 * Writes proposals into the review folder, never into the loaded recipes folder and never over an existing file.
 * A name is claimed by creating its report file with CREATE_NEW; the recipe file is created with CREATE_NEW too.
 */
@Component
@LearnerEnabled
public class ProposalWriter {

    static final int MAX_BASE = 56;

    /** One claimed output name and its two files. */
    public record Claim(String name, Path recipeFile, Path reportFile) {
    }

    private final Path dir;
    private final Path autoDir;
    private final Path autoRaw;

    @Autowired
    public ProposalWriter(LearnerProperties props, @Value("${ingestion.recipes.dir:./recipes}") String recipesDir,
            @Value("${ingestion.recipes.auto-dir:./recipes-auto}") String autoDir) {
        this(Path.of(props.outputDir()), Path.of(recipesDir), Path.of(autoDir));
    }

    /** Without an auto folder: {@link #writeEnabled} is unavailable. */
    public ProposalWriter(Path outputDir, Path recipesDir) {
        this(outputDir, recipesDir, null);
    }

    public ProposalWriter(Path outputDir, Path recipesDir, Path autoDirectory) {
        Path out = canonical(outputDir);
        Path loaded = canonical(recipesDir);
        if (out.startsWith(loaded) || loaded.startsWith(out)) {
            throw new IllegalStateException("ingestion.learner.output-dir (" + out
                    + ") must not be, contain or sit inside the recipes folder (" + loaded + ")");
        }
        this.dir = out;
        if (autoDirectory == null) {
            this.autoDir = null;
            this.autoRaw = null;
        } else {
            Path auto = canonical(autoDirectory);
            if (auto.startsWith(loaded) || loaded.startsWith(auto) || auto.startsWith(out) || out.startsWith(auto)) {
                throw new IllegalStateException("ingestion.recipes.auto-dir (" + auto
                        + ") must not be, contain or sit inside the recipes folder (" + loaded
                        + ") or the review folder (" + out + ")");
            }
            this.autoDir = auto;
            this.autoRaw = autoDirectory.toAbsolutePath().normalize();
        }
    }

    // ---- the auto folder: clones the profile asked for, written enabled ----

    private static final Pattern AUTO_NAME = Pattern.compile("[a-z0-9-]{1,64}");

    /** True when {@code <name>.json} is in the auto folder (a link counts). */
    public boolean autoExists(String name) {
        return autoDir != null && AUTO_NAME.matcher(name).matches()
                && Files.exists(autoDir.resolve(name + ".json"), LinkOption.NOFOLLOW_LINKS);
    }

    /** Regular {@code *.json} files in the auto folder, valid or not; links are counted, never followed. */
    public int countAuto() {
        if (autoDir == null || !Files.isDirectory(autoDir)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(autoDir)) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(".json")
                    && (Files.isSymbolicLink(p) || Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))).count();
        } catch (IOException | RuntimeException e) {
            // Unknown content: treat the folder as full so nothing more is written.
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Re-validates the recipe, writes an enabled copy of it into the auto folder (CREATE_NEW, no link followed) and
     * returns false when the file exists already. Never overwrites, never writes outside the folder.
     */
    public synchronized boolean writeEnabled(Recipe recipe, RecipeValidator validator) throws IOException {
        if (autoDir == null) {
            throw new IOException("no auto folder is configured");
        }
        if (recipe == null || recipe.name() == null || !AUTO_NAME.matcher(recipe.name()).matches()) {
            throw new IOException("recipe name is not allowed");
        }
        Recipe enabled = new Recipe(recipe.name(), true, recipe.strategy(), recipe.listing(), recipe.sitemap(),
                recipe.workday(), recipe.detail(), recipe.limits());
        try {
            validator.validate(enabled);
        } catch (IllegalArgumentException e) {
            throw new IOException("recipe failed validation: " + e.getMessage());
        }
        if (Files.isSymbolicLink(autoRaw)) {
            throw new IOException("the auto folder is a link");
        }
        Files.createDirectories(autoDir);
        Path real = autoDir.toRealPath();
        if (!real.equals(autoDir)) {
            throw new IOException("the auto folder resolves through a link");
        }
        Path file = real.resolve(recipe.name() + ".json");
        if (!file.getParent().equals(real)) {
            throw new IOException("output path escapes the auto folder");
        }
        String text = RecipeProposal.serialize(enabled) + "\n";
        try {
            writeContent(file, text);
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            // never leave a partial file behind: it would count toward the cap
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
            throw e;
        }
        if (!file.toRealPath(LinkOption.NOFOLLOW_LINKS).getParent().equals(real)) {
            Files.deleteIfExists(file);
            throw new IOException("output path escapes the auto folder");
        }
        return true;
    }

    /** Creates the file (CREATE_NEW, no link followed) and writes the text. Overridable for tests. */
    protected void writeContent(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    /** True when the auto file is a plain file the registry would load as an enabled recipe of that name. */
    public boolean autoLoadedEnabled(String name) {
        if (autoDir == null || !AUTO_NAME.matcher(name).matches()) {
            return false;
        }
        Path file = autoDir.resolve(name + ".json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1_000_000) {
                return false;
            }
            Recipe r = LOADER.readValue(Files.readString(file), Recipe.class);
            RecipeRegistry.validate(r);
            return r.isEnabled() && name.equals(r.name());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static final tools.jackson.databind.json.JsonMapper LOADER = tools.jackson.databind.json.JsonMapper
            .builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** Output name for a host: dots become dashes, anything else outside [a-z0-9-] too, truncated. */
    public static String baseName(String host) {
        String n = host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-");
        if (n.length() > MAX_BASE) {
            n = n.substring(0, MAX_BASE);
        }
        return n.isEmpty() ? "site" : n;
    }

    /** Reserves a free name (numeric suffix on a clash) and creates its empty report file. */
    public synchronized Claim claim(String host) throws IOException {
        Files.createDirectories(dir);
        Path real = dir.toRealPath();
        String base = baseName(host);
        for (int i = 1; i < 1000; i++) {
            String name = i == 1 ? base : base + "-" + i;
            Path recipe = real.resolve(name + ".json");
            Path report = real.resolve(name + ".report.txt");
            if (Files.exists(recipe, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.createFile(report);
            } catch (FileAlreadyExistsException e) {
                continue;
            }
            if (!report.toRealPath().getParent().equals(real)) {
                throw new IOException("output path escapes the review folder");
            }
            return new Claim(name, recipe, report);
        }
        throw new IOException("no free output name for " + base);
    }

    /** Claims a report-only name {@code <base>.report.txt} (numeric suffix on a clash) for a clone run. */
    public synchronized Claim claimReport(String base) throws IOException {
        Files.createDirectories(dir);
        Path real = dir.toRealPath();
        for (int i = 1; i < 1000; i++) {
            String name = i == 1 ? base : base + "-" + i;
            Path report = real.resolve(name + ".report.txt");
            try {
                Files.createFile(report);
            } catch (FileAlreadyExistsException e) {
                continue;
            }
            if (!report.toRealPath().getParent().equals(real)) {
                throw new IOException("output path escapes the review folder");
            }
            return new Claim(name, real.resolve(name + ".json"), report);
        }
        throw new IOException("no free output name for " + base);
    }

    /** True when {@code <name>.json} is already in the review folder (a link counts). */
    public boolean recipeExists(String name) {
        return Files.exists(dir.resolve(name + ".json"), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    /** Creates {@code <name>.json} with CREATE_NEW; false when it exists already. Never overwrites. */
    public synchronized boolean writeNewRecipe(String name, String json) throws IOException {
        Files.createDirectories(dir);
        Path real = dir.toRealPath();
        Path file = real.resolve(name + ".json");
        if (!file.getParent().equals(real)) {
            throw new IOException("output path escapes the review folder");
        }
        try {
            Files.writeString(file, json + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    /** Removes the empty report file of a claim whose run never started. */
    public void discard(Claim claim) {
        try {
            Files.deleteIfExists(claim.reportFile());
        } catch (IOException | RuntimeException ignored) {
            // a stray empty report is harmless
        }
    }

    public void writeRecipe(Claim claim, String json) throws IOException {
        Files.writeString(claim.recipeFile(), json + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public void writeReport(Claim claim, String text) throws IOException {
        Files.writeString(claim.reportFile(), text, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Absolute, normalised, symlinks resolved as far as the path exists. */
    public static Path canonical(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return abs;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(abs)).normalize();
        } catch (IOException e) {
            return abs;
        }
    }
}
