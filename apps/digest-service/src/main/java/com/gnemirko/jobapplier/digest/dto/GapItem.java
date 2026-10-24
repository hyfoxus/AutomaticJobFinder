package com.gnemirko.jobapplier.digest.dto;

/** A gap as the scoring API sends it. Untrusted text. */
public record GapItem(String text, String kind) {
}
