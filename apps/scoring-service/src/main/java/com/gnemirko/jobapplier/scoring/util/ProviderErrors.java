package com.gnemirko.jobapplier.scoring.util;

import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import org.springframework.web.client.RestClientResponseException;

/** Sorts provider exceptions into stop-the-batch, timeout or other, whatever SDK threw them. */
public final class ProviderErrors {

    public enum Kind {
        /** 401/403: the credentials are wrong. */
        AUTH,
        /** Any other non-transient 4xx (400, 404, 429 insufficient quota, ...): retrying cannot help. */
        REJECTED,
        TIMEOUT,
        OTHER
    }

    private ProviderErrors() {
    }

    /** Walks the whole cause chain. Precedence: auth, rejected request, timeout, other. */
    public static Kind classify(Throwable error) {
        boolean timeout = false;
        boolean rejected = false;
        int depth = 0;
        for (Throwable t = error; t != null && depth < 25; t = t.getCause(), depth++) {
            int status = statusOf(t);
            String name = t.getClass().getSimpleName();
            if (status == 401 || status == 403 || name.contains("Unauthorized") || name.contains("PermissionDenied")
                    || name.contains("Authentication")) {
                return Kind.AUTH;
            }
            if (status == 408) {
                timeout = true;
            } else if (status >= 400 && status < 500) {
                rejected = true;
            }
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException
                    || t instanceof InterruptedIOException || t instanceof TimeoutException
                    || name.contains("Timeout") || message.contains("timeout") || message.contains("timed out")) {
                timeout = true;
            }
        }
        if (rejected) {
            return Kind.REJECTED;
        }
        return timeout ? Kind.TIMEOUT : Kind.OTHER;
    }

    private static int statusOf(Throwable t) {
        if (t instanceof RestClientResponseException r) {
            return r.getStatusCode().value();
        }
        // Provider SDK exceptions expose statusCode() as an int.
        try {
            Method m = t.getClass().getMethod("statusCode");
            Object v = m.invoke(t);
            if (v instanceof Integer i) {
                return i;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not an SDK status exception
        }
        return -1;
    }
}
