package com.gnemirko.jobapplier.ingestion.service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ingestion.config.FetchProperties;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;

/** Per-host robots.txt fetch and cache. Fetches through the same policy, but never checks robots itself. */
@Service
public class RobotsService {

    static final String ROBOT_NAME = "jobapplierbot";
    static final long MAX_ROBOTS_BYTES = 500 * 1024;
    static final Duration SUCCESS_TTL = Duration.ofHours(24);
    static final Duration FAILURE_TTL = Duration.ofHours(1);
    static final Duration MAX_CRAWL_DELAY = Duration.ofSeconds(60);
    private static final int MAX_REDIRECTS = 5;

    /** Parsed rules for one host plus its (capped) Crawl-delay. */
    public record Rules(BaseRobotRules rules, Duration crawlDelay) {

        public boolean isAllowed(URI uri) {
            return rules.isAllowed(withoutFragment(uri));
        }

        private static String withoutFragment(URI uri) {
            String s = uri.toString();
            int i = s.indexOf('#');
            return i < 0 ? s : s.substring(0, i);
        }
    }

    private enum FailureKind { BLOCKED, DENIED }

    private record Entry(Rules rules, FailureKind failure, String message, long expiresAt) {

        FetchException toException() {
            return failure == FailureKind.BLOCKED ? new FetchBlockedException(message) : new FetchException(message);
        }
    }

    private final FetchProperties props;
    private final UrlPolicy policy;
    private final HttpExchange exchange;
    private final HostPacer pacer;
    private final LongSupplier nanos;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    RobotsService(FetchProperties props, UrlPolicy policy, HttpExchange exchange, HostPacer pacer) {
        this(props, policy, exchange, pacer, System::nanoTime);
    }

    RobotsService(FetchProperties props, UrlPolicy policy, HttpExchange exchange, HostPacer pacer, LongSupplier nanos) {
        this.props = props;
        this.policy = policy;
        this.exchange = exchange;
        this.pacer = pacer;
        this.nanos = nanos;
    }

    /** Rules for the host of {@code target}; throws if robots.txt cannot be trusted. */
    public Rules rulesFor(URI target) {
        policy.validate(target);
        String key = UrlPolicy.hostKey(target);
        Entry entry;
        // One lock per host: parallel first calls to a host fetch once, other hosts never wait.
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            entry = cache.get(key);
            if (entry == null || entry.expiresAt() - nanos.getAsLong() <= 0) {
                entry = load(target);
                cache.put(key, entry);
            }
        }
        if (entry.failure() != null) {
            throw entry.toException();
        }
        return entry.rules();
    }

    private Entry load(URI target) {
        URI robotsUri = target.resolve("/robots.txt");
        String key = UrlPolicy.hostKey(target);
        Deadline deadline = new Deadline(nanos, props.totalTimeout());
        try {
            URI current = robotsUri;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                policy.validate(current);
                if (!policy.sameHost(robotsUri, current)) {
                    throw new FetchException("robots.txt redirected to another host");
                }
                pacer.pace(key, props.minDelay(), deadline);
                HttpExchange.Reply reply = exchange.get(current, MAX_ROBOTS_BYTES, false, deadline);
                if (reply.isBlocked() || reply.status() == 401) {
                    throw new FetchBlockedException("robots.txt refused automated access (status " + reply.status() + ")");
                }
                if (reply.isRedirect()) {
                    current = PoliteFetcher.redirectTarget(current, reply);
                    continue;
                }
                if (reply.status() >= 500) {
                    return failure(FailureKind.DENIED, "robots.txt unavailable (status " + reply.status() + ")");
                }
                if (reply.status() >= 200 && reply.status() < 300) {
                    return success(parse(robotsUri, reply.body()), SUCCESS_TTL);
                }
                // 404, 410 and any other 4xx: no robots.txt, everything allowed.
                return success(new Rules(new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL), Duration.ZERO), SUCCESS_TTL);
            }
            throw new FetchException("robots.txt redirected too many times");
        } catch (FetchBlockedException e) {
            return failure(FailureKind.BLOCKED, e.getMessage());
        } catch (HttpExchange.IoFailure e) {
            return failure(FailureKind.DENIED, "robots.txt unreachable: " + e.getMessage());
        }
    }

    private Rules parse(URI robotsUri, byte[] body) {
        SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
        parser.setMaxCrawlDelay(Long.MAX_VALUE);
        BaseRobotRules rules = parser.parseContent(robotsUri.toString(), body, "text/plain", List.of(ROBOT_NAME));
        Duration delay = Duration.ZERO;
        long millis = rules.getCrawlDelay();
        if (millis != BaseRobotRules.UNSET_CRAWL_DELAY && millis > 0) {
            delay = Duration.ofMillis(millis);
            if (delay.compareTo(MAX_CRAWL_DELAY) > 0) {
                delay = MAX_CRAWL_DELAY;
            }
        }
        return new Rules(rules, delay);
    }

    private Entry success(Rules rules, Duration ttl) {
        return new Entry(rules, null, null, nanos.getAsLong() + ttl.toNanos());
    }

    private Entry failure(FailureKind kind, String message) {
        return new Entry(null, kind, message, nanos.getAsLong() + FAILURE_TTL.toNanos());
    }
}
