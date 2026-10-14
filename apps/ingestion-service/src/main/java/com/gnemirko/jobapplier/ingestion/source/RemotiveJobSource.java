package com.gnemirko.jobapplier.ingestion.source;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.gnemirko.jobapplier.contracts.JobPosting;
import com.gnemirko.jobapplier.ingestion.dto.RemotiveJobsResponse;

/**
 * Remotive public API. Terms: at most about 4 fetches a day; keep source and url
 * visible downstream for attribution.
 */
@Component
public class RemotiveJobSource implements JobSource {

    public static final String NAME = "remotive";
    private static final Logger log = LoggerFactory.getLogger(RemotiveJobSource.class);

    private final RestClient client;
    private final AtomicInteger skipped = new AtomicInteger();

    public RemotiveJobSource(
            RestClient.Builder builder,
            @Value("${ingestion.remotive.url:https://remotive.com/api/remote-jobs}") String url,
            @Value("${ingestion.remotive.timeout:15s}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = builder.baseUrl(url).requestFactory(factory).build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<JobPosting> fetch() {
        RemotiveJobsResponse response = client.get().retrieve().body(RemotiveJobsResponse.class);
        List<JobPosting> result = new ArrayList<>();
        int bad = 0;
        if (response != null && response.jobs() != null) {
            for (RemotiveJobsResponse.Item item : response.jobs()) {
                JobPosting posting = map(item);
                if (posting == null) {
                    bad++;
                } else {
                    result.add(posting);
                }
            }
        }
        skipped.set(bad);
        if (bad > 0) {
            log.warn("Remotive: skipped {} malformed item(s)", bad);
        }
        return result;
    }

    @Override
    public int skippedInLastFetch() {
        return skipped.get();
    }

    private JobPosting map(RemotiveJobsResponse.Item i) {
        if (i == null || i.id() == null || blank(i.title()) || blank(i.url())) {
            return null;
        }
        return new JobPosting(String.valueOf(i.id()), NAME, i.title().strip(), i.companyName(),
                i.location(), i.url().strip(), i.description(), parse(i.publicationDate()));
    }

    private static Instant parse(String s) {
        if (blank(s)) {
            return null;
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
