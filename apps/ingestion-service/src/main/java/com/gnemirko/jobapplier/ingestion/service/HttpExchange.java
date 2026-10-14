package com.gnemirko.jobapplier.ingestion.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.ingestion.config.FetchProperties;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSource;

/**
 * One HTTP GET, no redirects, bounded body. The address filter runs inside the client's own DNS
 * lookup, so the address that is checked is the address that is connected to.
 */
@Component
class HttpExchange {

    /** Network-level failure (connect, DNS, I/O); safe to remember for a while. */
    static final class IoFailure extends FetchException {
        IoFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record Reply(int status, String contentType, String cfMitigated, String location, byte[] body) {

        boolean isRedirect() {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }

        /** 403/429, cf-mitigated challenge, or a 503 carrying challenge markers. */
        boolean isBlocked() {
            if (status == 403 || status == 429) {
                return true;
            }
            if (cfMitigated != null && cfMitigated.strip().equalsIgnoreCase("challenge")) {
                return true;
            }
            if (status == 503) {
                String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1)
                        .toLowerCase(Locale.ROOT);
                return head.contains("<title>just a moment") || head.contains("/cdn-cgi/challenge-platform/");
            }
            return false;
        }
    }

    private final FetchProperties props;
    private final OkHttpClient client;

    HttpExchange(FetchProperties props, UrlPolicy policy) {
        this.props = props;
        Dns dns = hostname -> {
            List<InetAddress> all = Arrays.asList(InetAddress.getAllByName(hostname));
            if (!policy.isOverride(hostname)) {
                for (InetAddress a : all) {
                    if (UrlPolicy.isForbiddenAddress(a)) {
                        throw new UnknownHostException("Refusing to connect to a non-public address for " + hostname);
                    }
                }
            }
            return new ArrayList<>(all);
        };
        this.client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(props.connectTimeout())
                .callTimeout(props.totalTimeout())
                .dns(dns)
                .build();
    }

    /**
     * @param strictLimit true: Content-Length or stream above {@code maxBytes} is an error;
     *                    false: the body is silently cut at {@code maxBytes}
     */
    Reply get(URI uri, long maxBytes, boolean strictLimit, Deadline deadline) {
        HttpUrl url = HttpUrl.parse(uri.toString());
        if (url == null) {
            throw new FetchException("Unusable URL: " + uri);
        }
        Duration remaining = deadline.remaining();
        if (remaining.isNegative() || remaining.isZero()) {
            throw new FetchException("Fetch deadline exceeded");
        }
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", props.userAgent())
                .header("Accept-Encoding", "identity")
                .get()
                .build();
        java.util.concurrent.atomic.AtomicInteger networkRequests = new java.util.concurrent.atomic.AtomicInteger();
        OkHttpClient perCall = client.newBuilder().callTimeout(remaining)
                .addNetworkInterceptor(chain -> {
                    if (networkRequests.incrementAndGet() > 1) {
                        throw new IOException("Refusing to re-send a request");
                    }
                    return chain.proceed(chain.request());
                }).build();
        try (Response response = perCall.newCall(request).execute()) {
            okhttp3.ResponseBody body = response.body();
            int code = response.code();
            // Blocked signals are judged on the first 4 KB only, before any size cap applies.
            boolean signal = code == 403 || code == 429 || code == 503 || response.header("cf-mitigated") != null;
            long declared = body == null ? -1 : body.contentLength();
            if (strictLimit && !signal && declared > maxBytes) {
                throw new FetchException("Response too large: Content-Length " + declared);
            }
            byte[] bytes = new byte[0];
            if (body != null) {
                BufferedSource source = body.source();
                Buffer buffer = new Buffer();
                long limit = signal ? Math.min(4096, maxBytes) : strictLimit ? maxBytes + 1 : maxBytes;
                while (buffer.size() < limit && source.read(buffer, Math.min(8192, limit - buffer.size())) != -1) {
                    // keep reading
                }
                if (strictLimit && !signal && buffer.size() > maxBytes) {
                    throw new FetchException("Response too large: over " + maxBytes + " bytes");
                }
                bytes = buffer.readByteArray();
            }
            String contentType = response.header("Content-Type");
            return new Reply(response.code(), contentType, response.header("cf-mitigated"), response.header("Location"), bytes);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted() || Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted during request", e);
            }
            if (deadline.expired()) {
                throw new FetchException("Fetch deadline exceeded", e);
            }
            throw new IoFailure("Request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
