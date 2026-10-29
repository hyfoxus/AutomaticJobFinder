package com.gnemirko.jobapplier.scoring.dto;

import java.util.List;

/**
 * The user's local profile. Only {@code skillsSummary}, {@code mustHaves}, {@code niceToHaves} and
 * {@code goals} are ever sent to a model; {@code filters} are applied in code. {@code locationCountries} is read by
 * the ingestion service (clone auto-enable) and is never sent to a model nor used by the hard filter.
 */
public record Profile(
        String skillsSummary,
        List<String> mustHaves,
        List<String> niceToHaves,
        List<String> goals,
        Filters filters,
        Object locationCountries) {

    /**
     * @param excludeTitleRegex       a job whose title matches any of these is dropped
     * @param excludeCompanies        a job from any of these companies is dropped (case-insensitive, exact name)
     * @param allowedLocationKeywords a job whose location contains none of these is dropped; empty = no filter
     */
    public record Filters(
            List<String> excludeTitleRegex,
            List<String> excludeCompanies,
            List<String> allowedLocationKeywords) {

        public Filters {
            excludeTitleRegex = excludeTitleRegex == null ? List.of() : List.copyOf(excludeTitleRegex);
            excludeCompanies = excludeCompanies == null ? List.of() : List.copyOf(excludeCompanies);
            allowedLocationKeywords = allowedLocationKeywords == null ? List.of() : List.copyOf(allowedLocationKeywords);
        }
    }

    public Profile {
        mustHaves = mustHaves == null ? List.of() : List.copyOf(mustHaves);
        niceToHaves = niceToHaves == null ? List.of() : List.copyOf(niceToHaves);
        goals = goals == null ? List.of() : List.copyOf(goals);
        filters = filters == null ? new Filters(null, null, null) : filters;
        // Lenient on purpose: the field belongs to ingestion, so a bad shape must not stop scoring.
        locationCountries = locationCountries instanceof java.util.Collection<?> c
                ? c.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList()
                : List.of();
    }

    /** The normalised country list (null items dropped; anything but a list reads as empty). */
    @SuppressWarnings("unchecked")
    public List<String> countries() {
        return (List<String>) locationCountries;
    }
}
