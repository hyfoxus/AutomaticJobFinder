package com.gnemirko.jobapplier.ingestion.learner.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.gnemirko.jobapplier.ingestion.learner.util.Text;
import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

/**
 * Reduces an untrusted page to the few facts a recipe needs: link samples, JSON-LD, embedded JSON snippets and the
 * sitemap lines of robots.txt. The result is plain text, hard-capped; page prose and markup never go to the model.
 */
public final class PageSkeleton {

    static final int MAX_LINKS = 40;
    static final int MAX_PAGINATION_LINKS = 6;
    static final int MAX_SNIPPETS = 3;
    static final int SNIPPET_CHARS = 1500;
    static final int MAX_SITEMAP_LINES = 5;
    private static final int LINK_CHARS = 200;

    private PageSkeleton() {
    }

    /** @param robotsTxt body of robots.txt, or null when it could not be read */
    public static String build(URI pageUri, byte[] html, String charset, byte[] robotsTxt, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("page: ").append(Text.clean(pageUri.toString(), LINK_CHARS)).append('\n');
        Document doc = null;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(html), charset, pageUri.toString());
        } catch (IOException | RuntimeException e) {
            sb.append("(page could not be parsed)\n");
        }
        if (doc != null) {
            sb.append("title: ").append(Text.clean(doc.title(), 150)).append('\n');
            appendLinks(sb, doc, pageUri);
            appendScripts(sb, doc, "script[type=application/ld+json]", "json-ld", "script[type=application/ld+json]");
            appendEmbedded(sb, doc);
        }
        appendSitemaps(sb, robotsTxt);
        return sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
    }

    private static void appendLinks(StringBuilder sb, Document doc, URI pageUri) {
        String key = UrlPolicy.hostKey(pageUri);
        Set<String> links = new LinkedHashSet<>();
        Set<String> pagination = new LinkedHashSet<>();
        for (Element a : doc.select("a[href]")) {
            String abs = a.absUrl("href");
            URI u;
            try {
                u = URI.create(abs);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (u.getHost() == null || !UrlPolicy.hostKey(u).equals(key)) {
                continue;
            }
            int hash = abs.indexOf('#');
            String clean = Text.clean(hash < 0 ? abs : abs.substring(0, hash), LINK_CHARS);
            String line = clean + "  | " + Text.clean(a.text(), 60);
            String lower = clean.toLowerCase(Locale.ROOT);
            if ((lower.contains("page=") || lower.contains("/page/") || lower.contains("offset=") || lower.contains("start="))
                    && pagination.size() < MAX_PAGINATION_LINKS) {
                pagination.add(line);
            } else if (links.size() < MAX_LINKS) {
                links.add(line);
            }
        }
        sb.append("[links on the same host]\n");
        links.forEach(l -> sb.append(l).append('\n'));
        if (!pagination.isEmpty()) {
            sb.append("[pagination-looking links]\n");
            pagination.forEach(l -> sb.append(l).append('\n'));
        }
    }

    private static void appendScripts(StringBuilder sb, Document doc, String css, String label, String selectorHint) {
        int n = 0;
        for (Element e : doc.select(css)) {
            if (n++ >= MAX_SNIPPETS) {
                break;
            }
            sb.append('[').append(label).append(" selector: ").append(selectorHint).append("]\n")
                    .append(Text.clean(e.data(), SNIPPET_CHARS)).append('\n');
        }
    }

    private static void appendEmbedded(StringBuilder sb, Document doc) {
        int n = 0;
        for (Element e : doc.select("script[type=application/json], script#__NEXT_DATA__, script#__NUXT_DATA__")) {
            if (n++ >= MAX_SNIPPETS) {
                break;
            }
            String hint = e.id().isEmpty() ? "script[type=application/json]"
                    : "script#" + Text.clean(e.id(), 60);
            sb.append("[embedded-json selector: ").append(hint).append("]\n")
                    .append(Text.clean(e.data(), SNIPPET_CHARS)).append('\n');
        }
    }

    private static void appendSitemaps(StringBuilder sb, byte[] robotsTxt) {
        if (robotsTxt == null) {
            return;
        }
        int n = 0;
        StringBuilder lines = new StringBuilder();
        for (String line : new String(robotsTxt, StandardCharsets.UTF_8).split("\n")) {
            String t = line.strip();
            if (t.regionMatches(true, 0, "sitemap:", 0, 8) && n < MAX_SITEMAP_LINES) {
                lines.append(Text.clean(t, 300)).append('\n');
                n++;
            }
        }
        if (n > 0) {
            sb.append("[sitemap lines from robots.txt]\n").append(lines);
        }
    }
}
