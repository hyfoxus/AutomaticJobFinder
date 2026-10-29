package com.gnemirko.jobapplier.ingestion.learner.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the recipe clone run.
 *
 * @param cloneMaxRun         time budget of one run, checked before each locale; the rest is skipped
 * @param maxClones           locales tried per run (default 80, hard upper limit 120); extra ones are skipped with "cap"
 * @param autoEnable          off by default; when on, a clone run enables passing clones of the profile's countries
 * @param maxAutoEnabled      cap on files in the auto folder (default 20, 0..60), counting files already there
 * @param autoEnableBlocked   exact lower-case recipe names a clone run never enables
 * @param profileFile         the profile file; only its {@code locationCountries} field is ever read, and only when
 *                            auto-enable is on
 */
@ConfigurationProperties("ingestion.learner")
public record CloneProperties(
        @DefaultValue("30m") Duration cloneMaxRun,
        @DefaultValue("80") int maxClones,
        @DefaultValue("false") boolean autoEnable,
        @DefaultValue("20") int maxAutoEnabled,
        @DefaultValue List<String> autoEnableBlocked,
        @DefaultValue("./config/profile.yml") String profileFile) {

    public static final int HARD_MAX_CLONES = 120;
    public static final int HARD_MAX_AUTO_ENABLED = 60;
    private static final Pattern BLOCKED_NAME = Pattern.compile("[a-z0-9-]{1,64}");

    @ConstructorBinding
    public CloneProperties {
        if (cloneMaxRun == null || cloneMaxRun.isNegative()) {
            throw new IllegalArgumentException("ingestion.learner.clone-max-run must not be negative");
        }
        if (maxClones < 1 || maxClones > HARD_MAX_CLONES) {
            throw new IllegalArgumentException("ingestion.learner.max-clones must be 1.." + HARD_MAX_CLONES);
        }
        if (maxAutoEnabled < 0 || maxAutoEnabled > HARD_MAX_AUTO_ENABLED) {
            throw new IllegalArgumentException(
                    "ingestion.learner.max-auto-enabled must be 0.." + HARD_MAX_AUTO_ENABLED);
        }
        autoEnableBlocked = autoEnableBlocked == null ? List.of() : autoEnableBlocked.stream()
                .filter(n -> n != null && !n.isBlank()).map(n -> n.strip().toLowerCase(Locale.ROOT)).distinct().toList();
        for (String n : autoEnableBlocked) {
            if (!BLOCKED_NAME.matcher(n).matches()) {
                throw new IllegalArgumentException(
                        "ingestion.learner.auto-enable-blocked entries must match [a-z0-9-]{1,64}");
            }
        }
        profileFile = profileFile == null || profileFile.isBlank() ? "./config/profile.yml" : profileFile;
    }

    /** Auto-enable off; used where only the clone limits matter. */
    public CloneProperties(Duration cloneMaxRun, int maxClones) {
        this(cloneMaxRun, maxClones, false, 20, List.of(), "./config/profile.yml");
    }

    public boolean isBlocked(String name) {
        return name != null && autoEnableBlocked.contains(name.toLowerCase(Locale.ROOT));
    }
}
