package com.gnemirko.jobapplier.ui.dto;

/** A gap as the scoring API sends it. Untrusted text. */
public record GapItem(String text, GapKind kind) {
}
