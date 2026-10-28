package com.gnemirko.jobapplier.ingestion.learner.dto;

/** Body of POST /api/clone: a loaded sitemap recipe to copy, and the site's sitemap index. */
public record CloneRequest(String template, String indexUrl) {
}
