package com.gnemirko.jobapplier.ingestion.learner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;

import com.gnemirko.jobapplier.ingestion.learner.config.CloneProperties;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.learner.dto.ProfileCountries;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads one field, {@code locationCountries}, of the profile file and maps clone locales to countries. Nothing else
 * of the profile is looked at, kept or logged, and errors are reported as fixed text, never echoing file content.
 */
@Slf4j
@Component
@LearnerEnabled
public class CountrySelector {

    static final long MAX_PROFILE_BYTES = 64 * 1024;
    static final int MAX_ENTRIES = 100;
    private static final Pattern TOKEN = Pattern.compile("[a-z]{2,10}");

    private static final List<String> EU27 = List.of("at", "be", "bg", "hr", "cy", "cz", "dk", "ee", "fi", "fr", "de",
            "gr", "hu", "ie", "it", "lv", "lt", "lu", "mt", "nl", "pl", "pt", "ro", "sk", "si", "es", "se");
    private static final List<String> EUROPE_EXTRA = List.of("gb", "ch", "no", "is", "li", "ua", "rs", "ba", "me", "mk",
            "al", "md");
    private static final Set<String> ISO = Set.of(Locale.getISOCountries()).stream()
            .map(c -> c.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final Path file;
    private final YAMLMapper mapper;
    private final LoadSettings settings;

    @Autowired
    public CountrySelector(CloneProperties props) {
        this(Path.of(props.profileFile()));
    }

    public CountrySelector(Path profileFile) {
        this.file = profileFile;
        LoadSettings settings = LoadSettings.builder()
                .setCodePointLimit((int) MAX_PROFILE_BYTES).setAllowDuplicateKeys(false).build();
        this.settings = settings;
        this.mapper = YAMLMapper.builder(YAMLFactory.builder().loadSettings(settings).build()).build();
    }

    /** Reads the profile's country list. Never throws; a problem comes back as fixed text. */
    public ProfileCountries load() {
        JsonNode root;
        try {
            if (Files.isDirectory(file)) {
                return ProfileCountries.none("the profile path is a directory, not a file");
            }
            if (!Files.isRegularFile(file)) {
                return ProfileCountries.none("the profile file was not found");
            }
            if (Files.size(file) > MAX_PROFILE_BYTES) {
                return ProfileCountries.none("the profile file is larger than " + MAX_PROFILE_BYTES + " bytes");
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            // Anchors and aliases are not accepted anywhere in the file: treated as invalid, nothing is enabled.
            ParserImpl events = new ParserImpl(settings, new StreamReader(settings, text));
            while (events.hasNext()) {
                Event e = events.next();
                if (e instanceof AliasEvent || (e instanceof NodeEvent n && n.getAnchor().isPresent())) {
                    return ProfileCountries.none("the profile file uses YAML anchors or aliases, which are not accepted");
                }
            }
            root = mapper.readTree(text);
        } catch (IOException | RuntimeException e) {
            log.warn("Auto-enable: the profile file could not be read ({})", e.getClass().getSimpleName());
            return ProfileCountries.none("the profile file could not be read as YAML");
        }
        if (root == null || !root.isObject()) {
            return ProfileCountries.none("the profile file is not a YAML mapping");
        }
        JsonNode list = root.get("locationCountries");
        if (list == null || list.isNull()) {
            return ProfileCountries.none("locationCountries is missing or empty");
        }
        if (!list.isArray()) {
            return ProfileCountries.none("locationCountries must be a list of text values");
        }
        if (list.size() == 0) {
            return ProfileCountries.none("locationCountries is missing or empty");
        }
        if (list.size() > MAX_ENTRIES) {
            return ProfileCountries.none("locationCountries has more than " + MAX_ENTRIES + " entries");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode item : list) {
            if (!item.isString()) {
                return ProfileCountries.none("locationCountries must be a list of text values");
            }
            String token = item.asString().strip().toLowerCase(Locale.ROOT);
            if (token.equals("uk")) {
                token = "gb";
            }
            if (token.equals("europe")) {
                codes.addAll(EU27);
                codes.addAll(EUROPE_EXTRA);
            } else if (token.equals("eu")) {
                codes.addAll(EU27);
            } else if (token.length() == 2 && ISO.contains(token)) {
                codes.add(token);
            } else {
                String shown = TOKEN.matcher(token).matches() ? token : "(not a country code)";
                return ProfileCountries.none("locationCountries has an unknown country or region '" + shown + "'");
            }
        }
        return new ProfileCountries(new ArrayList<>(codes), null);
    }

    /** The country of a locale such as {@code ie-en}: its first part, only if a known two-letter code, else null. */
    public static String countryOf(String locale) {
        if (locale == null) {
            return null;
        }
        int dash = locale.indexOf('-');
        String first = (dash < 0 ? locale : locale.substring(0, dash)).toLowerCase(Locale.ROOT);
        if (first.equals("uk")) {
            first = "gb";
        }
        return first.length() == 2 && ISO.contains(first) ? first : null;
    }

    /** The language part of a locale ({@code ie-en} gives {@code en}), or empty. */
    public static String languageOf(String locale) {
        int dash = locale == null ? -1 : locale.indexOf('-');
        return dash < 0 ? "" : locale.substring(dash + 1).toLowerCase(Locale.ROOT);
    }
}
