package com.gnemirko.jobapplier.scoring.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.gnemirko.jobapplier.contracts.JobRecord;
import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.config.ScoringProperties;
import com.gnemirko.jobapplier.scoring.exception.IngestionException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pulls vacancies from ingestion's {@code GET /api/jobs?since=}. It has its own HTTP client with its own
 * timeouts, and reads the answer through a bounded stream so a runaway response cannot fill the memory.
 */
@Slf4j
@Component
@ScoringEnabled
public class IngestionClient {

    private static final TypeReference<List<JobRecord>> JOBS = new TypeReference<>() {
    };

    private final RestClient rest;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final long maxBytes;

    public IngestionClient(ScoringProperties props) {
        ScoringProperties.Ingestion cfg = props.ingestion();
        cfg.validate();
        this.maxBytes = cfg.maxBytes();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build());
        factory.setReadTimeout(cfg.readTimeout());
        this.rest = RestClient.builder().baseUrl(cfg.baseUrl()).requestFactory(factory).build();
    }

    /** Jobs first seen strictly after {@code since}, oldest first. Throws {@link IngestionException} on any failure. */
    public List<JobRecord> fetchSince(Instant since) {
        try {
            return rest.get()
                    .uri("/api/jobs?since={since}", since.toString())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IngestionException("ingestion answered HTTP " + response.getStatusCode().value());
                        }
                        long declared = response.getHeaders().getContentLength();
                        if (declared > maxBytes) {
                            throw tooLarge(declared);
                        }
                        try (InputStream in = new LimitedInputStream(response.getBody(), maxBytes)) {
                            List<JobRecord> jobs = mapper.readValue(in, JOBS);
                            return jobs == null ? List.<JobRecord>of() : jobs;
                        } catch (Exception e) {
                            for (Throwable t = e; t != null; t = t.getCause()) {
                                if (t instanceof TooLarge) {
                                    throw tooLarge(maxBytes + 1);
                                }
                            }
                            throw new IngestionException("ingestion answer is not valid JSON of job records", e);
                        }
                    });
        } catch (IngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new IngestionException("ingestion request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private IngestionException tooLarge(long size) {
        log.error("Ingestion answer is larger than the size limit of {} bytes (at least {}); pull abandoned, cursor "
                + "unchanged. Raise scoring.ingestion.max-bytes or run scoring more often.", maxBytes, size);
        return new IngestionException("ingestion answer exceeds the size limit of " + maxBytes + " bytes");
    }

    private static final class TooLarge extends IOException {
    }

    /** Fails with {@link TooLarge} as soon as more than {@code limit} bytes have been read. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
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
