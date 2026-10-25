package com.gnemirko.jobapplier.ui.dto;

import java.util.List;

/** What the dashboard shows; strings are cleaned. A null {@code usage} or empty counts mean scoring did not answer. */
public record DashboardView(boolean scoringDown, String spent, String cap, String budget, String calls,
                            String total, List<Count> counts) {

    public record Count(String label, int value) {
    }
}
