package com.gnemirko.jobapplier.ingestion.learner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;

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

    @Autowired
    public ProposalWriter(LearnerProperties props, @Value("${ingestion.recipes.dir:./recipes}") String recipesDir) {
        this(Path.of(props.outputDir()), Path.of(recipesDir));
    }

    public ProposalWriter(Path outputDir, Path recipesDir) {
        Path out = canonical(outputDir);
        Path loaded = canonical(recipesDir);
        if (out.startsWith(loaded) || loaded.startsWith(out)) {
            throw new IllegalStateException("ingestion.learner.output-dir (" + out
                    + ") must not be, contain or sit inside the recipes folder (" + loaded + ")");
        }
        this.dir = out;
    }

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
    static Path canonical(Path p) {
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
