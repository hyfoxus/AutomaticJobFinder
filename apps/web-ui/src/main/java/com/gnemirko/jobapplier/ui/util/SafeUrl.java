package com.gnemirko.jobapplier.ui.util;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/** The one check that lets a job link into a page: a plain http or https url with a host and no credentials. */
public final class SafeUrl {

    public static final int MAX_URL = 2048;

    private SafeUrl() {
    }

    /** The url, trimmed, when it is a plain http or https link; empty otherwise. */
    public static Optional<URI> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_URL) {
            return Optional.empty();
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7f || Character.isWhitespace(c) || Character.isISOControl(c) || Escape.isBidi(c)
                    || Character.isSpaceChar(c)) {
                return Optional.empty();
            }
        }
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return Optional.empty();
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
    }
}
