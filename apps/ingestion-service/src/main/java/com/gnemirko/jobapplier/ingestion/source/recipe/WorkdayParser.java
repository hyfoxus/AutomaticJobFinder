package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.entity.JobEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure reading of Workday's JSON: a search page into paths, a detail document into a posting.
 * Workday's schema is the same for every tenant, so the mapping is fixed here. Never throws.
 */
final class WorkdayParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String LOCATION_SEPARATOR = "; ";

    private WorkdayParser() {
    }

    /**
     * One search page. {@code paths} has one entry per listed posting, in order; an entry without a
     * usable {@code externalPath} is the empty string (the caller rejects it as a bad path).
     * {@code total} is 0 when absent, as on every page after the first.
     */
    record SearchPage(List<String> paths, int total) {
    }

    /** Empty when the body is not JSON, or {@code jobPostings} is missing or not an array. */
    static Optional<SearchPage> parseSearch(byte[] body) {
        JsonNode root = read(body);
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode postings = root.get("jobPostings");
        if (postings == null || !postings.isArray()) {
            return Optional.empty();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode p : postings) {
            String path = p != null && p.isObject() ? raw(p.get("externalPath")) : null;
            paths.add(path == null ? "" : path);
        }
        JsonNode total = root.get("total");
        int t = total != null && total.isIntegralNumber() && total.canConvertToInt() ? Math.max(0, total.asInt()) : 0;
        return Optional.of(new SearchPage(paths, t));
    }

    /**
     * One detail document. Closed postings ({@code posted} is false) are EXPIRED; anything without a
     * {@code jobPostingInfo} object or a title is BAD. The external id is the path itself.
     */
    static DetailExtractor.Result parseDetail(byte[] body, String sourceName, String company, String externalPath,
            String url, Instant updatedAt) {
        JsonNode root = read(body);
        if (root == null || !root.isObject()) {
            return DetailExtractor.Result.BAD;
        }
        JsonNode info = root.get("jobPostingInfo");
        if (info == null || !info.isObject()) {
            return DetailExtractor.Result.BAD;
        }
        JsonNode posted = info.get("posted");
        if (posted != null && posted.isBoolean() && !posted.asBoolean()) {
            return DetailExtractor.Result.EXPIRED;
        }
        String title = cut(text(info.get("title")), JobEntity.MAX_TITLE);
        if (title == null) {
            return DetailExtractor.Result.BAD;
        }
        JobPosting posting = new JobPosting(
                cut(externalPath, JobEntity.MAX_EXTERNAL_ID),
                sourceName,
                title,
                cut(company, JobEntity.MAX_TEXT),
                cut(location(info), JobEntity.MAX_TEXT),
                cut(url, JobEntity.MAX_URL),
                raw(info.get("jobDescription")),
                date(info.get("startDate")),
                null,
                cut(text(info.get("timeType")), JobEntity.MAX_JOB_TYPE),
                null,
                updatedAt);
        return new DetailExtractor.Result(posting, false);
    }

    private static String location(JsonNode info) {
        List<String> parts = new ArrayList<>();
        String main = text(info.get("location"));
        if (main != null) {
            parts.add(main);
        }
        JsonNode more = info.get("additionalLocations");
        if (more != null && more.isArray()) {
            for (JsonNode n : more) {
                String v = text(n);
                if (v != null && !parts.contains(v)) {
                    parts.add(v);
                }
            }
        }
        return parts.isEmpty() ? null : String.join(LOCATION_SEPARATOR, parts);
    }

    /** A plain date such as 2026-10-01, read as the start of that day in UTC; anything else is null. */
    private static Instant date(JsonNode node) {
        String v = text(node);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonNode read(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Stripped text of a string node, or null when absent, not a string, or blank. */
    private static String text(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String v = node.asString().strip();
        return v.isEmpty() ? null : v;
    }

    /** The string exactly as received, or null when absent or not a string. */
    private static String raw(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
