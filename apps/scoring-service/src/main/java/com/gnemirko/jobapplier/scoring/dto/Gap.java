package com.gnemirko.jobapplier.scoring.dto;

/** A requirement of the job the profile does not cover. Text is model output: escape it wherever it is shown. */
public record Gap(String text, GapKind kind) {
}
