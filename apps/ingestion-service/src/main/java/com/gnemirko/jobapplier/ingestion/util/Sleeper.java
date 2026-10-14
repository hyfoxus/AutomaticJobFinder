package com.gnemirko.jobapplier.ingestion.util;

import java.time.Duration;

/** Injectable pause so tests do not really wait. */
@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = duration -> Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000);

    void sleep(Duration duration) throws InterruptedException;
}
