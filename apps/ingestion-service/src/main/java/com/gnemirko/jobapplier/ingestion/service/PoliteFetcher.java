package com.gnemirko.jobapplier.ingestion.service;

import java.net.URI;
import java.time.Duration;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ingestion.config.FetchProperties;
import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

/**
 * Fetches one page while honouring robots.txt, per-host pacing, SSRF rules, size and time limits,
 * and stopping at bot challenges. Knows nothing about recipes, the database or LLMs.
 */
@Service
public class PoliteFetcher {

    private static final int MAX_REDIRECTS = 5;

    private final FetchProperties props;
    private final UrlPolicy policy;
    private final RobotsService robots;
    private final HttpExchange exchange;
    private final HostPacer pacer;
    private final LongSupplier nanos;

    @Autowired
    PoliteFetcher(FetchProperties props, UrlPolicy policy, RobotsService robots, HttpExchange exchange, HostPacer pacer) {
        this(props, policy, robots, exchange, pacer, System::nanoTime);
    }

    PoliteFetcher(FetchProperties props, UrlPolicy policy, RobotsService robots, HttpExchange exchange,
            HostPacer pacer, LongSupplier nanos) {
        this.props = props;
        this.policy = policy;
        this.robots = robots;
        this.exchange = exchange;
        this.pacer = pacer;
        this.nanos = nanos;
    }

    public FetchedPage fetch(URI uri) {
        policy.validate(uri);
        RobotsService.Rules rules = robots.rulesFor(uri);
        if (!rules.isAllowed(uri)) {
            throw new RobotsDisallowedException("robots.txt disallows " + uri);
        }
        Duration delay = props.minDelay().compareTo(rules.crawlDelay()) >= 0 ? props.minDelay() : rules.crawlDelay();
        String key = UrlPolicy.hostKey(uri);
        Deadline deadline = new Deadline(nanos, props.totalTimeout());

        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (hop > 0) {
                policy.validate(current);
                if (!policy.sameHost(uri, current)) {
                    throw new FetchException("Redirect to another host refused: " + current.getHost());
                }
                if (!rules.isAllowed(current)) {
                    throw new RobotsDisallowedException("robots.txt disallows redirect target " + current);
                }
            }
            pacer.pace(key, delay, deadline);
            HttpExchange.Reply reply = exchange.get(current, props.maxBodyBytes(), true, deadline);
            if (reply.isBlocked()) {
                throw new FetchBlockedException("Host refused automated access (status " + reply.status() + ")");
            }
            if (reply.isRedirect()) {
                current = redirectTarget(current, reply);
                continue;
            }
            if (reply.status() >= 500) {
                throw new FetchException("Server error " + reply.status() + " for " + current);
            }
            return new FetchedPage(current, reply.status(), reply.contentType(), reply.body());
        }
        throw new FetchException("Too many redirects for " + uri);
    }

    /** Resolves the Location header; a malformed one is a FetchException, never IllegalArgumentException. */
    static URI redirectTarget(URI current, HttpExchange.Reply reply) {
        String location = reply.location();
        if (location == null || location.isBlank()) {
            throw new FetchException("Redirect without Location header");
        }
        try {
            return current.resolve(location.strip());
        } catch (IllegalArgumentException e) {
            throw new FetchException("Malformed redirect Location", e);
        }
    }
}
