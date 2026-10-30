package com.gnemirko.jobapplier.ingestion.dto;

import java.time.Instant;

public record SourceHealthView(String source, String state, Instant since, int lastSeen, Integer baseline, String note,
        Instant updatedAt) {
}
