package com.gnemirko.jobapplier.ui.dto;

import java.util.List;

/**
 * One assessment ready for a template: every string is cleaned and capped, the link is null unless it is a plain
 * http or https url. Templates show these with {@code th:text} only.
 */
public record RowView(String title, String company, String location, String status, String tier, String chance,
                      String reason, List<String> hardGaps, List<String> learnableGaps, int metCount,
                      String timeLabel, String time, String safeUrl, String urlHost) {
}
