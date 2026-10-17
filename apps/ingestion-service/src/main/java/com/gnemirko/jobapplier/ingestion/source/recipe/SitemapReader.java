package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gnemirko.jobapplier.ingestion.dto.FetchedPage;
import com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.service.PoliteFetcher;
import com.gnemirko.jobapplier.ingestion.util.UrlCanonicalizer;

/**
 * Reads job URLs out of a sitemap: plain XML, gzip (detected by its magic bytes) or one level of
 * sitemap index. XML is parsed with DTDs and entities switched off. A problem with the root
 * sitemap, or a malformed child, throws and fails the run.
 */
public class SitemapReader {

    public static final long MAX_UNCOMPRESSED_BYTES = 50L * 1024 * 1024;
    public static final int MAX_ENTRIES = 50_000;
    public static final int MAX_LOC_LENGTH = 2048;
    public static final int MAX_CHILDREN = 10;

    static final String NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9";

    private static final Logger log = LoggerFactory.getLogger(SitemapReader.class);
    private static final long MATCH_LIMIT_NANOS = Duration.ofMillis(100).toNanos();

    /** A job URL as listed in the sitemap: canonical, with its lastmod truncated to seconds (or null). */
    public record Entry(URI url, Instant lastmod) {
    }

    /**
     * All matching entries in document order, how many of them (the first ones) may be fetched under
     * the recipe's URL cap, and remarks for the run note.
     */
    public record Result(List<Entry> entries, int fetchCount, List<String> notes) {
    }

    private record Raw(String loc, Instant lastmod) {
    }

    private record Parsed(boolean index, List<Raw> entries) {
    }

    private final PoliteFetcher fetcher;
    private final long maxUncompressedBytes;
    private boolean timeoutWarned;

    public SitemapReader(PoliteFetcher fetcher) {
        this(fetcher, MAX_UNCOMPRESSED_BYTES);
    }

    public SitemapReader(PoliteFetcher fetcher, long maxUncompressedBytes) {
        this.fetcher = fetcher;
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    /**
     * @param root       sitemap URL; its host and scheme are the only ones accepted
     * @param urlPattern job URLs must match (found anywhere in the canonical URL)
     */
    public Result read(URI root, Pattern urlPattern, int maxUrls) {
        timeoutWarned = false;
        URI canonicalRoot = UrlCanonicalizer.canonicalize(root, false);
        if (canonicalRoot == null) {
            throw new FetchException("Unusable sitemap URL " + root);
        }
        String host = UrlCanonicalizer.hostOf(canonicalRoot);
        String scheme = canonicalRoot.getScheme();
        List<String> notes = new ArrayList<>();

        FetchedPage page = fetcher.fetch(root, fetcher.maxSitemapBytes());
        if (page.status() / 100 != 2) {
            throw new FetchException("Sitemap " + root + " returned " + page.status());
        }
        Parsed top = parse(page);
        List<Raw> raw = new ArrayList<>();
        if (top.index()) {
            List<URI> children = new ArrayList<>();
            Set<String> seenChildren = new HashSet<>();
            for (Raw r : top.entries()) {
                URI child = resolve(page.finalUri(), r.loc());
                // The query is part of a child's identity (?page=1 and ?page=2 differ).
                URI canonical = child == null ? null : UrlCanonicalizer.canonicalize(child, true);
                if (canonical == null || !sameSite(canonical, host, scheme) || !seenChildren.add(canonical.toString())) {
                    continue;
                }
                children.add(child);
            }
            if (children.size() > MAX_CHILDREN) {
                notes.add("Sitemap index truncated to the first " + MAX_CHILDREN + " of " + children.size()
                        + " child sitemaps");
                children = children.subList(0, MAX_CHILDREN);
            }
            for (URI child : children) {
                FetchedPage childPage;
                try {
                    childPage = fetcher.fetch(child, fetcher.maxSitemapBytes());
                } catch (RobotsDisallowedException e) {
                    notes.add("Child sitemap " + child + " disallowed by robots.txt");
                    continue;
                } catch (FetchBlockedException e) {
                    throw e;
                } catch (FetchException e) {
                    notes.add("Child sitemap " + child + " skipped: " + e.getMessage());
                    continue;
                }
                if (childPage.status() / 100 != 2) {
                    notes.add("Child sitemap " + child + " returned " + childPage.status() + " and was skipped");
                    continue;
                }
                Parsed parsed = parse(childPage);
                if (parsed.index()) {
                    notes.add("Nested sitemap index " + child + " ignored");
                    continue;
                }
                for (Raw r : parsed.entries()) {
                    URI resolved = resolve(childPage.finalUri(), r.loc());
                    raw.add(new Raw(resolved == null ? null : resolved.toString(), r.lastmod()));
                }
            }
        } else {
            for (Raw r : top.entries()) {
                URI resolved = resolve(page.finalUri(), r.loc());
                raw.add(new Raw(resolved == null ? null : resolved.toString(), r.lastmod()));
            }
        }

        Map<String, Entry> matched = new LinkedHashMap<>();
        for (Raw r : raw) {
            if (r.loc() == null) {
                continue;
            }
            URI canonical = UrlCanonicalizer.canonicalize(URI.create(r.loc()), false);
            if (canonical == null || !sameSite(canonical, host, scheme)) {
                continue;
            }
            String url = canonical.toString();
            if (matched.containsKey(url) || !find(urlPattern, url)) {
                continue;
            }
            matched.put(url, new Entry(canonical, r.lastmod()));
        }
        List<Entry> entries = new ArrayList<>(matched.values());
        if (entries.size() > maxUrls) {
            notes.add("URL cap: using the first " + maxUrls + " of " + entries.size() + " matching sitemap URLs");
        }
        return new Result(entries, Math.min(entries.size(), maxUrls), notes);
    }

    private static boolean sameSite(URI canonical, String host, String scheme) {
        return host.equals(UrlCanonicalizer.hostOf(canonical)) && scheme.equalsIgnoreCase(canonical.getScheme());
    }

    private static URI resolve(URI base, String loc) {
        if (loc == null) {
            return null;
        }
        try {
            return base.resolve(loc);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean find(Pattern pattern, String value) {
        try {
            return pattern.matcher(TimeLimitedCharSequence.limited(value, MATCH_LIMIT_NANOS)).find();
        } catch (TimeLimitedCharSequence.MatchTimeoutException e) {
            if (!timeoutWarned) {
                timeoutWarned = true;
                log.warn("Sitemap urlPattern timed out on a URL and was treated as no match; check the recipe pattern");
            }
            return false;
        }
    }

    // ---- parsing ----

    /** Counts bytes read and fails once the limit is passed. */
    private static final class CappedInputStream extends InputStream {
        private final InputStream in;
        private final long limit;
        private long count;
        boolean exceeded;

        CappedInputStream(InputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && ++count > limit) {
                exceeded = true;
                throw new IOException("uncompressed sitemap exceeds " + limit + " bytes");
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) {
                count += n;
                if (count > limit) {
                    exceeded = true;
                    throw new IOException("uncompressed sitemap exceeds " + limit + " bytes");
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static XMLInputFactory hardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newDefaultFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        for (String prop : new String[] {XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA}) {
            try {
                f.setProperty(prop, "");
            } catch (IllegalArgumentException ignored) {
                // not supported by this implementation; DTDs are off anyway
            }
        }
        return f;
    }

    private Parsed parse(FetchedPage page) {
        byte[] body = page.body();
        CappedInputStream in = null;
        XMLStreamReader xml = null;
        try {
            InputStream raw = new ByteArrayInputStream(body);
            if (body.length >= 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b) {
                raw = new GZIPInputStream(raw);
            }
            in = new CappedInputStream(raw, maxUncompressedBytes);
            xml = hardenedFactory().createXMLStreamReader(in);
            return readDocument(xml);
        } catch (XMLStreamException | IOException e) {
            if (in != null && in.exceeded) {
                throw new FetchException("Sitemap " + page.finalUri() + " is larger than "
                        + maxUncompressedBytes + " bytes uncompressed", e);
            }
            throw new FetchException("Sitemap " + page.finalUri() + " is not valid: " + e.getMessage(), e);
        } finally {
            if (xml != null) {
                try {
                    xml.close();
                } catch (XMLStreamException ignored) {
                    // nothing to do
                }
            }
        }
    }

    /** Text of a leaf element; an entity reference or child element is an error. Kept to the loc size limit. */
    private static String elementText(XMLStreamReader xml) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            switch (xml.next()) {
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (sb.length() <= MAX_LOC_LENGTH) {
                        sb.append(xml.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    return sb.toString();
                }
                case XMLStreamConstants.COMMENT, XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                    // ignored
                }
                case XMLStreamConstants.END_DOCUMENT -> throw new XMLStreamException("unexpected end of document");
                default -> throw new FetchException("Sitemap contains a DOCTYPE, entity reference or nested element"
                        + " where text was expected");
            }
        }
    }

    private Parsed readDocument(XMLStreamReader xml) throws XMLStreamException {
        List<Raw> entries = new ArrayList<>();
        boolean index = false;
        String entryName = null;
        int depth = 0;
        boolean inEntry = false;
        String loc = null;
        Instant lastmod = null;
        boolean sawRoot = false;
        while (xml.hasNext()) {
            int event = xml.next();
            switch (event) {
                case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                    throw new FetchException("Sitemap contains a DOCTYPE or entity reference");
                case XMLStreamConstants.START_ELEMENT -> {
                    boolean sitemapNs = NAMESPACE.equals(xml.getNamespaceURI());
                    String local = xml.getLocalName();
                    if (depth == 0) {
                        if (!sitemapNs || !(local.equals("urlset") || local.equals("sitemapindex"))) {
                            throw new FetchException("Not a sitemap: unexpected root element " + local);
                        }
                        index = local.equals("sitemapindex");
                        entryName = index ? "sitemap" : "url";
                        sawRoot = true;
                    } else if (depth == 1 && sitemapNs && local.equals(entryName)) {
                        inEntry = true;
                        loc = null;
                        lastmod = null;
                    } else if (depth == 2 && inEntry && sitemapNs && (local.equals("loc") || local.equals("lastmod"))) {
                        String text = elementText(xml);
                        if (local.equals("loc")) {
                            String v = text.strip();
                            if (v.length() > MAX_LOC_LENGTH) {
                                throw new FetchException("Sitemap loc longer than " + MAX_LOC_LENGTH + " characters");
                            }
                            if (loc == null) {
                                loc = v.isEmpty() ? null : v;
                            }
                        } else if (lastmod == null) {
                            Instant parsed = JsonLdJobPostingParser.parseDate(text);
                            lastmod = parsed == null ? null : parsed.truncatedTo(ChronoUnit.SECONDS);
                        }
                        break;
                    }
                    depth++;
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    depth--;
                    if (depth == 1 && inEntry) {
                        inEntry = false;
                        if (entries.size() >= MAX_ENTRIES) {
                            throw new FetchException("Sitemap has more than " + MAX_ENTRIES + " entries");
                        }
                        entries.add(new Raw(loc, lastmod));
                    }
                }
                default -> {
                    // text, comments, whitespace: nothing to do
                }
            }
        }
        if (!sawRoot) {
            throw new FetchException("Sitemap is empty");
        }
        return new Parsed(index, entries);
    }
}
