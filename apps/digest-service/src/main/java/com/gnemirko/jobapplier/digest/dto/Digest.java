package com.gnemirko.jobapplier.digest.dto;

import java.util.List;
import java.util.UUID;

/** A built digest, ready to hand over. The subject is a fixed template with counts only. */
public record Digest(UUID id, String subject, String html, String text, List<Job> jobs) {
}
