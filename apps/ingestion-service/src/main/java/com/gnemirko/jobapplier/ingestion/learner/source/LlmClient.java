package com.gnemirko.jobapplier.ingestion.learner.source;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.gnemirko.jobapplier.ingestion.learner.config.LearnerEnabled;
import com.gnemirko.jobapplier.ingestion.learner.config.LearnerProperties;
import com.gnemirko.jobapplier.ingestion.learner.dto.Completion;
import com.gnemirko.jobapplier.ingestion.learner.exception.LlmException;
import com.gnemirko.jobapplier.ingestion.learner.exception.LlmException.Kind;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls scoring's {@code POST /api/llm/complete}. It has its own HTTP client with its own timeouts, never follows
 * redirects and reads the answer through a bounded stream. 402 and 429 mean the budget is used up; every other
 * failure means the model is unavailable or timed out. Nothing is retried here.
 */
@Slf4j
@Component
@LearnerEnabled
public class LlmClient {

    static final String TOKEN_HEADER = "X-Learner-Token";
    static final String RUN_HEADER = "X-JobApplier-Run";
    static final int MIN_TOKEN_CHARS = 16;

    private final RestClient rest;
    private final String token;
    private final long maxBytes;
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public LlmClient(LearnerProperties props) {
        this.token = readToken(Path.of(props.tokenFile()));
        this.maxBytes = props.maxReplyBytes();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        factory.setReadTimeout(props.readTimeout());
        this.rest = RestClient.builder().baseUrl(props.scoringBaseUrl().trim()).requestFactory(factory).build();
    }

    private static String readToken(Path file) {
        String t;
        try {
            t = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The recipe learner is enabled but its token file " + file
                    + " cannot be read; create secrets/learner/token (at least " + MIN_TOKEN_CHARS + " characters)", e);
        }
        if (t.length() < MIN_TOKEN_CHARS) {
            throw new IllegalStateException(
                    "The learner token in " + file + " must be at least " + MIN_TOKEN_CHARS + " characters");
        }
        return t;
    }

    /** One model call. Throws {@link LlmException} on any failure. */
    public Completion complete(String host, String skeleton, String feedback) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("host", host);
        body.put("skeleton", skeleton);
        body.put("feedback", feedback);
        try {
            return rest.post().uri("/api/llm/complete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(TOKEN_HEADER, token)
                    .header(RUN_HEADER, "1")
                    .body(mapper.writeValueAsString(body))
                    .exchange((request, response) -> {
                        int code = response.getStatusCode().value();
                        if (code == 402 || code == 429) {
                            throw new LlmException(Kind.BUDGET, "scoring answered HTTP " + code);
                        }
                        if (code == 504) {
                            throw new LlmException(Kind.TIMEOUT, "scoring reports the model timed out");
                        }
                        if (code != 200) {
                            throw new LlmException(Kind.UNAVAILABLE, "scoring answered HTTP " + code);
                        }
                        if (response.getHeaders().getContentLength() > maxBytes) {
                            throw new LlmException(Kind.UNAVAILABLE, "scoring answer is too large");
                        }
                        try (InputStream in = new Limited(response.getBody(), maxBytes)) {
                            Completion c = mapper.readValue(in, Completion.class);
                            if (c == null || c.text() == null) {
                                throw new LlmException(Kind.UNAVAILABLE, "scoring answer has no text");
                            }
                            return c;
                        } catch (LlmException e) {
                            throw e;
                        } catch (Exception e) {
                            if (isTimeout(e)) {
                                throw new LlmException(Kind.TIMEOUT, "scoring did not finish the answer in time", e);
                            }
                            throw new LlmException(Kind.UNAVAILABLE, "scoring answer is not valid JSON of the expected shape", e);
                        }
                    });
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            if (isTimeout(e)) {
                throw new LlmException(Kind.TIMEOUT, "scoring did not answer in time", e);
            }
            throw new LlmException(Kind.UNAVAILABLE, "scoring request failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Fails as soon as more than {@code limit} bytes have been read. */
    private static final class Limited extends FilterInputStream {
        private final long limit;
        private long total;

        Limited(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1 && ++total > limit) {
                throw new IOException("answer too large");
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0 && (total += n) > limit) {
                throw new IOException("answer too large");
            }
            return n;
        }
    }
}
