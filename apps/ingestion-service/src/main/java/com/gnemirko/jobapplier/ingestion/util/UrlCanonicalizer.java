package com.gnemirko.jobapplier.ingestion.util;

import java.net.URI;
import java.util.Locale;

/**
 * Turns a link into one stable absolute URL: resolved against its base, fragment dropped, host
 * lowercased without trailing dot, default port dropped. The query is kept only on request.
 */
public final class UrlCanonicalizer {

    public static final int MAX_HREF = 2048;

    private UrlCanonicalizer() {
    }

    /** Returns null when the link is blank, malformed, not http(s) or has no host. */
    public static URI canonicalize(String href, URI base, boolean keepQuery) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String raw = href.strip();
        if (raw.length() > MAX_HREF) {
            raw = raw.substring(0, MAX_HREF);
        }
        try {
            URI resolved = base.resolve(raw);
            return canonicalize(resolved, keepQuery);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static URI canonicalize(URI uri, boolean keepQuery) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String host = UrlPolicy.normalizeHost(uri.getHost());
        if (host.isEmpty()) {
            return null;
        }
        int port = uri.getPort();
        if (port == (scheme.equals("http") ? 80 : 443)) {
            port = -1;
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        }
        sb.append(path);
        if (keepQuery && uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        try {
            return new URI(sb.toString());
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Host plus non-default port, lowercase; used to compare a link with the recipe host. */
    public static String hostOf(URI canonical) {
        return UrlPolicy.hostKey(canonical);
    }
}
