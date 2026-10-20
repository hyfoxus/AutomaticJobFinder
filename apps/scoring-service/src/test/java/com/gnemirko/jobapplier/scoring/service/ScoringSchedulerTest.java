package com.gnemirko.jobapplier.scoring.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ScoringSchedulerTest {

    @Test
    void tickNeverThrows() {
        ScoringRunner runner = mock(ScoringRunner.class);
        ScoringScheduler scheduler = new ScoringScheduler(runner);

        when(runner.runNow()).thenReturn(false);
        assertThatCode(scheduler::tick).doesNotThrowAnyException();

        when(runner.runNow()).thenThrow(new IllegalStateException("boom"));
        assertThatCode(scheduler::tick).doesNotThrowAnyException();

        verify(runner, org.mockito.Mockito.times(2)).runNow();
    }
}
