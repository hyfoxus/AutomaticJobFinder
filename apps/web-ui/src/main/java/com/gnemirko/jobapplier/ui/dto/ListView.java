package com.gnemirko.jobapplier.ui.dto;

import java.util.List;

/** One page of the list. {@code status} and {@code tier} are whitelisted enum names (tier may be empty). */
public record ListView(boolean scoringDown, String status, String tier, int page, boolean hasPrev, boolean hasNext,
                       List<RowView> rows) {
}
