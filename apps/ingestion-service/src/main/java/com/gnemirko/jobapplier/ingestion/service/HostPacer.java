package com.gnemirko.jobapplier.ingestion.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.util.Sleeper;

/** Hands out ordered per-host request slots. The slot is reserved under a lock; the sleep happens outside it. */
@Component
class HostPacer {

    private final Sleeper sleeper;
    private final LongSupplier nanos;
    private final Map<String, Long> lastSlot = new HashMap<>();

    @Autowired
    HostPacer(Sleeper sleeper) {
        this(sleeper, System::nanoTime);
    }

    HostPacer(Sleeper sleeper, LongSupplier nanos) {
        this.sleeper = sleeper;
        this.nanos = nanos;
    }

    /** Waits until this caller's slot for the host, extending the deadline by the wait. */
    void pace(String hostKey, Duration delay, Deadline deadline) {
        Duration wait = reserve(hostKey, delay);
        if (wait.isZero()) {
            return;
        }
        deadline.extend(wait);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("Interrupted while waiting for the per-host delay", e);
        }
    }

    private synchronized Duration reserve(String hostKey, Duration delay) {
        long now = nanos.getAsLong();
        Long last = lastSlot.get(hostKey);
        long slot = last == null ? now : Math.max(now, last + delay.toNanos());
        lastSlot.put(hostKey, slot);
        return Duration.ofNanos(slot - now);
    }
}
