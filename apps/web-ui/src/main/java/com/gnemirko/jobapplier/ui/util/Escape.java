package com.gnemirko.jobapplier.ui.util;

/**
 * Cleans untrusted job and model text for display: control characters (CR/LF and tab become a space), bidirectional
 * overrides and line separators are dropped, and the text is cut to a maximum length. Output is plain text; the
 * template engine escapes it with {@code th:text}.
 */
public final class Escape {

    public static final int MAX_TEXT = 300;
    public static final int MAX_REASON = 500;

    private Escape() {
    }

    public static String text(String s) {
        return text(s, MAX_TEXT);
    }

    public static String text(String s, int max) {
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

    static boolean isBidi(char c) {
        return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)
                || c == 0x200E || c == 0x200F || c == 0x061C;
    }
}
