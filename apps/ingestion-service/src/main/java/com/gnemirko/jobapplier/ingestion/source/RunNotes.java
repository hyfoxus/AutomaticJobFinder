package com.gnemirko.jobapplier.ingestion.source;

import java.time.Duration;

/** Wording of run notes that other code (the health check) recognises. */
public final class RunNotes {

    public static final String STOPPED_EARLY_PREFIX = "Stopped early: run time budget";
    public static final String ROBOTS_DISALLOWED = "disallowed by robots.txt";

    private RunNotes() {
    }

    public static String stoppedEarly(Duration maxRun) {
        return STOPPED_EARLY_PREFIX + " of " + maxRun + " reached";
    }

    /** A run cut short by the time budget or by robots.txt saw only part of the site. */
    public static boolean isPartial(String note) {
        return note != null && (note.contains(STOPPED_EARLY_PREFIX) || note.contains(ROBOTS_DISALLOWED));
    }
}
