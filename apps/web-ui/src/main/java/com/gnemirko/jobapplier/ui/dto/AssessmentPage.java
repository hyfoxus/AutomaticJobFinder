package com.gnemirko.jobapplier.ui.dto;

import java.util.List;

/** One answer of the assessment list and whether the scoring service says another page follows. */
public record AssessmentPage(List<AssessmentItem> items, boolean hasMore) {
}
