package com.gnemirko.jobapplier.ingestion.service;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Overall time budget for one fetch; pacing sleeps are added so politeness never causes a timeout. */
final class Deadline {

    private final LongSupplier nanos;
    private long end;

    Deadline(LongSupplier nanos, Duration total) {
        this.nanos = nanos;
        this.end = nanos.getAsLong() + total.toNanos();
    }

    synchronized void extend(Duration extra) {
        end += extra.toNanos();
    }

    synchronized Duration remaining() {
        return Duration.ofNanos(end - nanos.getAsLong());
    }

    boolean expired() {
        return remaining().isNegative() || remaining().isZero();
    }
}
