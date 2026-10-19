package com.gnemirko.jobapplier.scoring.util;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** Wraps a string so a regex match throws once its time limit passes (guards against backtracking blow-ups). */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class TimeLimitedCharSequence implements CharSequence {

    public static final class MatchTimeoutException extends RuntimeException {
        MatchTimeoutException() {
            super("regex match exceeded its time limit", null, false, false);
        }
    }

    private final CharSequence inner;
    private final long deadlineNanos;

    public static TimeLimitedCharSequence limited(CharSequence inner, long limitNanos) {
        return new TimeLimitedCharSequence(inner, System.nanoTime() + limitNanos);
    }

    @Override
    public char charAt(int index) {
        if (System.nanoTime() - deadlineNanos > 0) {
            throw new MatchTimeoutException();
        }
        return inner.charAt(index);
    }

    @Override
    public int length() {
        return inner.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new TimeLimitedCharSequence(inner.subSequence(start, end), deadlineNanos);
    }

    @Override
    public String toString() {
        return inner.toString();
    }
}
