package com.gnemirko.jobapplier.digest.util;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * The one place where untrusted job and model text is made safe for a mail. Both outputs drop control
 * characters (CR/LF and tab become a space) and bidirectional overrides, and cut the text to a maximum length.
 * The HTML output also entity-escapes; the text output stays plain.
 */
public final class Escape {

    public static final int MAX_TEXT = 300;
    public static final int MAX_URL = 2048;

    private Escape() {
    }

    /** Safe as HTML element content or as a quoted attribute value. */
    public static String html(String s) {
        return html(s, MAX_TEXT);
    }

    public static String html(String s, int max) {
        String clean = clean(s, max);
        StringBuilder out = new StringBuilder(clean.length() + 16);
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Safe in the plain-text part: no line can be added or split by the text. */
    public static String text(String s) {
        return text(s, MAX_TEXT);
    }

    public static String text(String s, int max) {
        return clean(s, max);
    }

    /** The url, trimmed, when it is a plain http or https link; empty otherwise. */
    public static Optional<String> safeUrl(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > MAX_URL) {
            return Optional.empty();
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c == 0x7f || Character.isWhitespace(c) || Character.isISOControl(c) || isBidi(c)
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
            return Optional.of(s);
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(s.length(), max));
        int i = 0;
        while (i < s.length() && out.length() < max) {
            char c = s.charAt(i++);
            if (c == '\r' || c == '\n' || c == '\t' || c == '\u2028' || c == '\u2029') {
                out.append(' ');
            } else if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f) || isBidi(c)) {
                continue;
            } else {
                out.append(c);
            }
        }
        // Do not leave half of a surrogate pair at the cut.
        if (!out.isEmpty() && Character.isHighSurrogate(out.charAt(out.length() - 1))) {
            out.setLength(out.length() - 1);
        }
        return out.toString().strip();
    }

    private static boolean isBidi(char c) {
        return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)
                || c == 0x200E || c == 0x200F || c == 0x061C;
    }
}
