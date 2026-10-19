package com.gnemirko.jobapplier.scoring.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.gnemirko.jobapplier.scoring.dto.Profile;
import com.gnemirko.jobapplier.scoring.exception.ScoringConfigurationException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Reads and validates the YAML profile. Unknown keys are an error, so a stray CV or contact field is never read. */
public final class ProfileLoader {

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ProfileLoader() {
    }

    public static Profile load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ScoringConfigurationException("Profile file not found: " + path.toAbsolutePath()
                    + " (copy profile.sample.yml to it, or set scoring.profile-path)");
        }
        Profile profile;
        try {
            profile = MAPPER.readValue(Files.readString(path), Profile.class);
        } catch (IOException | JacksonException e) {
            throw new ScoringConfigurationException("Profile file " + path + " is not valid: " + e.getMessage(), e);
        }
        if (profile == null) {
            throw new ScoringConfigurationException("Profile file " + path + " is empty");
        }
        validate(profile, path);
        return profile;
    }

    private static void validate(Profile p, Path path) {
        if (p.skillsSummary() == null || p.skillsSummary().isBlank()) {
            throw new ScoringConfigurationException("Profile " + path + ": skillsSummary must not be blank");
        }
        if (p.mustHaves().isEmpty() || p.mustHaves().stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new ScoringConfigurationException("Profile " + path + ": mustHaves needs at least one non-blank item");
        }
        for (String regex : p.filters().excludeTitleRegex()) {
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException | NullPointerException e) {
                throw new ScoringConfigurationException(
                        "Profile " + path + ": invalid regex in filters.excludeTitleRegex: " + regex, e);
            }
        }
        requireNoBlank(p.niceToHaves(), "niceToHaves", path);
        requireNoBlank(p.goals(), "goals", path);
        requireNoBlank(p.filters().excludeCompanies(), "filters.excludeCompanies", path);
        requireNoBlank(p.filters().allowedLocationKeywords(), "filters.allowedLocationKeywords", path);
    }

    private static void requireNoBlank(List<String> items, String name, Path path) {
        if (items.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new ScoringConfigurationException("Profile " + path + ": " + name + " must not contain blank items");
        }
    }
}
