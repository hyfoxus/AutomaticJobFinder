package com.gnemirko.jobapplier.ui.source;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.gnemirko.jobapplier.ui.config.UiEnabled;
import com.gnemirko.jobapplier.ui.config.UiProperties;
import com.gnemirko.jobapplier.ui.dto.AssessmentItem;
import com.gnemirko.jobapplier.ui.dto.AssessmentPage;
import com.gnemirko.jobapplier.ui.dto.AssessmentStatus;
import com.gnemirko.jobapplier.ui.dto.RunOutcome;
import com.gnemirko.jobapplier.ui.dto.Tier;
import com.gnemirko.jobapplier.ui.dto.UsageInfo;
import com.gnemirko.jobapplier.ui.exception.ScoringApiException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to the scoring service's REST API. It has its own HTTP client with its own timeouts, never follows
 * redirects, and reads every answer through a bounded stream. Exception messages never contain the answer.
 */
@Component
@UiEnabled
public class ScoringApiClient {

    private static final TypeReference<List<AssessmentItem>> ITEMS = new TypeReference<>() {
    };

    private final RestClient rest;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.cfg.EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .build();
    private final long maxBytes;
    private final java.time.Duration deadline;
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "scoring-client-watchdog");
                t.setDaemon(true);
                return t;
            });

    public ScoringApiClient(UiProperties props) {
        UiProperties.Scoring cfg = props.scoring();
        this.maxBytes = cfg.maxBytes();
        this.deadline = cfg.readTimeout();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(cfg.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(cfg.readTimeout());
        this.rest = RestClient.builder().baseUrl(cfg.baseUrl().trim()).requestFactory(factory).build();
    }

    /** One page of rows, newest first. {@code status} and {@code page} are required, {@code tier} may be null. */
    public AssessmentPage fetchAssessments(AssessmentStatus status, Tier tier, int page, int limit) {
        String uri = "/api/assessments?limit={limit}&page={page}" + (status == null ? "" : "&status={status}")
                + (tier == null ? "" : "&tier={tier}");
        java.util.List<Object> vars = new java.util.ArrayList<>(List.of(limit, page));
        if (status != null) {
            vars.add(status.name());
        }
        if (tier != null) {
            vars.add(tier.name());
        }
        return get(uri, (in, headers) -> {
            List<AssessmentItem> items = mapper.readValue(in, ITEMS);
            boolean more = "true".equalsIgnoreCase(headers.getFirst("X-Has-More"));
            return new AssessmentPage(items == null ? List.of() : items.stream().filter(java.util.Objects::nonNull).toList(), more);
        }, vars.toArray());
    }

    /** Today's spend. Throws {@link ScoringApiException} on any failure. */
    public UsageInfo fetchUsage() {
        UsageInfo usage = get("/api/usage/today", (in, headers) -> mapper.readValue(in, UsageInfo.class));
        if (usage == null) {
            throw new ScoringApiException("scoring answered with an empty usage document");
        }
        return usage;
    }

    /**
     * Asks scoring to start a run (once, never retried): 202 is STARTED, 409 is BUSY, anything else throws
     * {@link ScoringApiException}.
     */
    public RunOutcome startRun() {
        try {
            return rest.post().uri("/api/scoring/run").header("X-JobApplier-Run", "1")
                    .accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                        int code = response.getStatusCode().value();
                        if (code == 202) {
                            return RunOutcome.STARTED;
                        }
                        if (code == 409) {
                            return RunOutcome.BUSY;
                        }
                        throw new ScoringApiException("scoring API answered HTTP " + code);
                    });
        } catch (ScoringApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ScoringApiException("scoring API request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private interface Reader<T> {
        T read(InputStream in, HttpHeaders headers) throws Exception;
    }

    private <T> T get(String uri, Reader<T> reader, Object... vars) {
        try {
            return rest.get().uri(uri, vars).accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                int code = response.getStatusCode().value();
                if (code != 200) {
                    throw new ScoringApiException("scoring API answered HTTP " + code);
                }
                long declared = response.getHeaders().getContentLength();
                if (declared > maxBytes) {
                    throw tooLarge();
                }
                // The HTTP client's read timeout ends when the headers arrive. This one bounds the whole body
                // read: at the deadline the response is closed, which breaks a read that is stalled.
                var watchdog = WATCHDOG.schedule(() -> {
                    try {
                        response.close();
                    } catch (RuntimeException ignored) {
                        // already closed
                    }
                }, deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                try (InputStream in = new LimitedInputStream(response.getBody(), maxBytes)) {
                    return reader.read(in, response.getHeaders());
                } catch (Exception e) {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        if (t instanceof TooLarge) {
                            throw tooLarge();
                        }
                    }
                    throw new ScoringApiException("scoring API answer is not valid JSON of the expected shape");
                } finally {
                    watchdog.cancel(false);
                }
            });
        } catch (ScoringApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ScoringApiException("scoring API request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private ScoringApiException tooLarge() {
        return new ScoringApiException("scoring API answer exceeds the size limit of " + maxBytes + " bytes");
    }

    private static final class TooLarge extends IOException {
    }

    /** Fails with {@link TooLarge} as soon as more than {@code limit} bytes have been read. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long total;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1 && ++total > limit) {
                throw new TooLarge();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0 && (total += n) > limit) {
                throw new TooLarge();
            }
            return n;
        }
    }
}
