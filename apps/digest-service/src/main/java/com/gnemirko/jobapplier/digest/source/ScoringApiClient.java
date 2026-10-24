package com.gnemirko.jobapplier.digest.source;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.gnemirko.jobapplier.digest.config.DigestEnabled;
import com.gnemirko.jobapplier.digest.config.DigestProperties;
import com.gnemirko.jobapplier.digest.dto.AssessmentItem;
import com.gnemirko.jobapplier.digest.dto.UsageInfo;
import com.gnemirko.jobapplier.digest.exception.ScoringApiException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the scoring service's REST API. It has its own HTTP client with its own timeouts, never follows
 * redirects, and reads every answer through a bounded stream so a runaway response cannot fill the memory.
 */
@Slf4j
@Component
@DigestEnabled
public class ScoringApiClient {

    private static final TypeReference<List<AssessmentItem>> ITEMS = new TypeReference<>() {
    };

    private final RestClient rest;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final long maxBytes;

    public ScoringApiClient(DigestProperties props) {
        DigestProperties.Scoring cfg = props.scoring();
        this.maxBytes = cfg.maxBytes();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(cfg.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(cfg.readTimeout());
        this.rest = RestClient.builder().baseUrl(cfg.baseUrl().trim()).requestFactory(factory).build();
    }

    /**
     * SCORED rows after the position (scoredSince, afterId), oldest first, at most {@code limit}.
     * Throws {@link ScoringApiException} on any failure.
     */
    public List<AssessmentItem> fetchScored(Instant scoredSince, long afterId, int limit) {
        List<AssessmentItem> items = get("/api/assessments?status=SCORED&scoredSince={since}&afterId={after}&limit={limit}",
                in -> mapper.readValue(in, ITEMS), scoredSince.toString(), afterId, limit);
        return items == null ? List.of() : items;
    }

    /** Today's spend. Throws {@link ScoringApiException} on any failure. */
    public UsageInfo fetchUsage() {
        UsageInfo usage = get("/api/usage/today", in -> mapper.readValue(in, UsageInfo.class));
        if (usage == null) {
            throw new ScoringApiException("scoring answered with an empty usage document");
        }
        return usage;
    }

    private interface Reader<T> {
        T read(InputStream in) throws Exception;
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
                try (InputStream in = new LimitedInputStream(response.getBody(), maxBytes)) {
                    return reader.read(in);
                } catch (Exception e) {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        if (t instanceof TooLarge) {
                            throw tooLarge();
                        }
                    }
                    throw new ScoringApiException("scoring API answer is not valid JSON of the expected shape", e);
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
