package com.gnemirko.jobapplier.ingestion.learner.util;

/** Cleaning of untrusted text before it goes into a report, a log line or a retry note. */
public final class Text {

    private Text() {
    }

    /** Removes ANSI escape sequences and control characters (newline and tab become a space), then caps the length. */
    public static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        StringBuilder sb = new StringBuilder(Math.min(t.length(), max));
        for (int i = 0; i < t.length() && sb.length() < max; i++) {
            char c = t.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r') {
                sb.append(' ');
            } else if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != ' ' && c != ' ') {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    /** Like {@link #clean} but keeps line breaks, for a multi-line report. */
    public static String cleanMultiline(String s, int max) {
        if (s == null) {
            return "";
        }
        String[] lines = s.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() >= max) {
                break;
            }
            sb.append(clean(line, max)).append('\n');
        }
        return sb.length() > max ? sb.substring(0, max) : sb.toString();
    }
}
