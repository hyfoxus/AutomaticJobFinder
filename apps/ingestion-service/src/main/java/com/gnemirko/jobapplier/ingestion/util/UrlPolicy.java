package com.gnemirko.jobapplier.ingestion.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.gnemirko.jobapplier.ingestion.config.FetchProperties;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;

/**
 * Cheap, DNS-free URL checks plus the address filter used at connect time. Hosts on the
 * override list (tests only) may use http, any port, and skip the address filter.
 */
@Component
public class UrlPolicy {

    private static final Pattern NUMERIC_LAST_LABEL = Pattern.compile("(^|.*\\.)(\\d+|0x[0-9a-f]*)$");

    private final Set<String> overrideHosts = new HashSet<>();

    public UrlPolicy(FetchProperties props) {
        for (String host : props.allowInsecureHosts()) {
            if (host != null && !host.isBlank()) {
                overrideHosts.add(normalizeHost(host));
            }
        }
    }

    public static String normalizeHost(String host) {
        String h = host.strip().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    public boolean isOverride(String host) {
        return host != null && overrideHosts.contains(normalizeHost(host));
    }

    /** Throws {@link FetchException} when the URI may not be requested. */
    public void validate(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            throw new FetchException("URL has no scheme");
        }
        if (uri.getRawUserInfo() != null) {
            throw new FetchException("URL with credentials is not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new FetchException("URL has no host");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (isOverride(host)) {
            if (!scheme.equals("https") && !scheme.equals("http")) {
                throw new FetchException("Scheme not allowed: " + scheme);
            }
            return;
        }
        if (!scheme.equals("https")) {
            throw new FetchException("Only https is allowed");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new FetchException("Non-default port is not allowed");
        }
        String h = normalizeHost(host);
        if (h.contains(":") || h.startsWith("[") || NUMERIC_LAST_LABEL.matcher(h).matches()) {
            throw new FetchException("IP-literal host is not allowed");
        }
    }

    /** Host plus non-default port; {@code host} and {@code host:443} give the same key for https. */
    public static String hostKey(URI uri) {
        String host = normalizeHost(uri.getHost());
        int port = uri.getPort();
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
        int def = scheme.equals("http") ? 80 : 443;
        return port == -1 || port == def ? host : host + ":" + port;
    }

    public boolean sameHost(URI a, URI b) {
        return hostKey(a).equals(hostKey(b));
    }

    /** True for loopback, private, link-local, any-local, multicast, CGNAT and ULA addresses. */
    public static boolean isForbiddenAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;
        }
        return false;
    }
}
