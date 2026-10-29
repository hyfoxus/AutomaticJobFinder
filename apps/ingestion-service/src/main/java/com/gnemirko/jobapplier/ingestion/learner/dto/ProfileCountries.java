package com.gnemirko.jobapplier.ingestion.learner.dto;

import java.util.List;

/**
 * The countries the profile asks for, after region expansion.
 *
 * @param codes   lower-case ISO alpha-2 codes in profile order (regions expanded in a fixed order), never null
 * @param problem a fixed-text reason when nothing can be enabled (unreadable file, bad token, empty list), else null
 */
public record ProfileCountries(List<String> codes, String problem) {

    public ProfileCountries {
        codes = codes == null ? List.of() : List.copyOf(codes);
    }

    public static ProfileCountries none(String problem) {
        return new ProfileCountries(List.of(), problem);
    }

    public boolean usable() {
        return problem == null && !codes.isEmpty();
    }
}
