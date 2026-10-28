package com.gnemirko.jobapplier.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.gnemirko.jobapplier.ingestion.config.FetchProperties;
import com.gnemirko.jobapplier.ingestion.exception.FetchException;
import com.gnemirko.jobapplier.ingestion.exception.RobotsDisallowedException;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.FakeTime;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.Rig;
import com.gnemirko.jobapplier.ingestion.service.StubSupport.Stub;
import com.gnemirko.jobapplier.ingestion.source.recipe.SitemapReader;

class SitemapReaderTest {

    private static final String NS = "xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"";
    private static final Pattern ANY = Pattern.compile("/jobs/");

    private StubSupport stub;
    private Rig rig;
    private SitemapReader reader;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubSupport();
        rig = new Rig(StubSupport.props("127.0.0.1", "localhost"), new FakeTime());
        reader = new SitemapReader(rig.fetcher);
        stub.route("/robots.txt", Stub.of(200, "User-agent: *\nDisallow: /closed\n"));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private String url(String path) {
        return stub.uri(path).toString();
    }

    private String urlset(String... entries) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset " + NS + ">");
        for (String e : entries) {
            sb.append(e);
        }
        return sb.append("</urlset>").toString();
    }

    private String entry(String loc, String lastmod) {
        return "<url><loc>" + loc + "</loc>" + (lastmod == null ? "" : "<lastmod>" + lastmod + "</lastmod>") + "</url>";
    }

    private void xml(String path, String body) {
        stub.route(path, Stub.of(200, body, "Content-Type", "application/xml"));
    }

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private SitemapReader.Result read(String path, int max) {
        return reader.read(stub.uri(path), ANY, max);
    }

    @Test
    void keepQueryKeepsJobUrlsThatDifferOnlyByQuery() {
        xml("/sitemap.xml", urlset(entry(url("/jobs/detail?id=1"), null), entry(url("/jobs/detail?id=2"), null),
                entry(url("/jobs/detail?id=3"), null), entry("https://elsewhere.example/jobs/detail?id=4", null)));
        var kept = reader.read(stub.uri("/sitemap.xml"), ANY, 100, true);
        assertThat(kept.entries()).extracting(e -> e.url().toString()).containsExactly(
                url("/jobs/detail?id=1"), url("/jobs/detail?id=2"), url("/jobs/detail?id=3"));
        var dropped = reader.read(stub.uri("/sitemap.xml"), ANY, 100);
        assertThat(dropped.entries()).extracting(e -> e.url().toString()).containsExactly(url("/jobs/detail"));
    }

    @Test
    void plainSitemapIsFilteredCanonicalisedAndDeduped() {
        xml("/sitemap.xml", urlset(
                entry(url("/jobs/a?x=1#f"), "2026-10-01T10:20:30.999Z"),
                entry(url("/jobs/b"), "2026-10-02"),
                entry(url("/other/c"), null),
                entry("http://elsewhere.example/jobs/d", null),
                entry("https://127.0.0.1:" + stub.port + "/jobs/e", null),
                entry(url("/jobs/a"), "2030-01-01"),
                entry(url("/jobs/f"), "garbage"),
                "<url><lastmod>2026-10-02</lastmod></url>",
                entry("/jobs/rel", null)));
        var result = read("/sitemap.xml", 100);
        assertThat(result.entries()).extracting(e -> e.url().toString())
                .containsExactly(url("/jobs/a"), url("/jobs/b"), url("/jobs/f"), url("/jobs/rel"));
        assertThat(result.entries().get(0).lastmod()).isEqualTo(Instant.parse("2026-10-01T10:20:30Z"));
        assertThat(result.entries().get(1).lastmod()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(result.entries().get(2).lastmod()).isNull();
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void extensionElementsNamedLocAreIgnored() {
        xml("/sitemap.xml", "<urlset " + NS + " xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\">"
                + "<url><image:image><image:loc>" + url("/jobs/img") + "</image:loc></image:image>"
                + "<loc>" + url("/jobs/real") + "</loc></url></urlset>");
        assertThat(read("/sitemap.xml", 10).entries()).extracting(e -> e.url().toString())
                .containsExactly(url("/jobs/real"));
    }

    @Test
    void gzipIsDetectedByMagicBytesNotByNameOrHeaders() throws Exception {
        byte[] gz = gzip(urlset(entry(url("/jobs/a"), null)));
        stub.route("/plain-name.xml", new Stub(200, Map.of("Content-Type", "application/xml"), gz));
        stub.route("/s.xml.gz", new Stub(200, Map.of("Content-Type", "application/gzip"), gz));
        assertThat(read("/plain-name.xml", 10).entries()).hasSize(1);
        assertThat(read("/s.xml.gz", 10).entries()).hasSize(1);
        // A plain document served under a .gz name is still plain.
        stub.route("/fake.xml.gz", Stub.of(200, urlset(entry(url("/jobs/b"), null))));
        assertThat(read("/fake.xml.gz", 10).entries()).hasSize(1);
    }

    @Test
    void uncompressedOutputOverTheCapFails() throws Exception {
        stub.route("/big.xml.gz", new Stub(200, Map.of(), gzip(urlset(entry(url("/jobs/a"), null) + " ".repeat(5000)))));
        SitemapReader small = new SitemapReader(rig.fetcher, 1000);
        assertThatThrownBy(() -> small.read(stub.uri("/big.xml.gz"), ANY, 10))
                .isInstanceOf(FetchException.class).hasMessageContaining("larger than");
    }

    @Test
    void downloadOverTheSitemapByteLimitFails() {
        var tiny = new FetchProperties("ua", Duration.ZERO, Duration.ofSeconds(5), Duration.ofSeconds(30),
                5 * 1024 * 1024, List.of("127.0.0.1"), 200);
        Rig r = new Rig(tiny, new FakeTime());
        xml("/sitemap.xml", urlset(entry(url("/jobs/a"), null) + " ".repeat(500)));
        assertThatThrownBy(() -> new SitemapReader(r.fetcher).read(stub.uri("/sitemap.xml"), ANY, 10))
                .isInstanceOf(FetchException.class).hasMessageContaining("too large");
    }

    @Test
    void indexReadsFirstTenSameHostChildrenInOrderAndNotesTruncation() {
        StringBuilder idx = new StringBuilder("<sitemapindex " + NS + ">");
        idx.append("<sitemap><loc>http://elsewhere.example/off.xml</loc></sitemap>");
        for (int i = 0; i < 12; i++) {
            idx.append("<sitemap><loc>").append(url("/c" + i + ".xml")).append("</loc></sitemap>");
            xml("/c" + i + ".xml", urlset(entry(url("/jobs/j" + i), null),
                    entry(url("/jobs/dup"), "2026-01-0" + (i % 9 + 1))));
        }
        idx.append("<sitemap><loc>").append(url("/c0.xml")).append("</loc></sitemap></sitemapindex>");
        xml("/index.xml", idx.toString());
        var result = read("/index.xml", 100);
        assertThat(result.entries()).extracting(e -> e.url().getPath())
                .containsExactly("/jobs/j0", "/jobs/dup", "/jobs/j1", "/jobs/j2", "/jobs/j3", "/jobs/j4", "/jobs/j5",
                        "/jobs/j6", "/jobs/j7", "/jobs/j8", "/jobs/j9");
        assertThat(result.entries().get(1).lastmod()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(result.notes()).anyMatch(n -> n.contains("truncated"));
        assertThat(stub.hits("/c10.xml")).isZero();
    }

    @Test
    void childProblemsAndNestedIndexes() {
        xml("/index.xml", "<sitemapindex " + NS + "><sitemap><loc>" + url("/gone.xml") + "</loc></sitemap>"
                + "<sitemap><loc>" + url("/nested.xml") + "</loc></sitemap>"
                + "<sitemap><loc>" + url("/closed/x.xml") + "</loc></sitemap>"
                + "<sitemap><loc>/ok.xml</loc></sitemap></sitemapindex>");
        xml("/nested.xml", "<sitemapindex " + NS + "><sitemap><loc>" + url("/deeper.xml") + "</loc></sitemap></sitemapindex>");
        xml("/ok.xml", urlset(entry(url("/jobs/a"), null)));
        xml("/deeper.xml", urlset(entry(url("/jobs/deep"), null)));
        var result = read("/index.xml", 10);
        assertThat(result.entries()).extracting(e -> e.url().getPath()).containsExactly("/jobs/a");
        assertThat(result.notes()).anyMatch(n -> n.contains("gone.xml") && n.contains("404"));
        assertThat(stub.hits("/deeper.xml")).isZero();
        assertThat(stub.hits("/closed/x.xml")).isZero();
    }

    @Test
    void childrenDifferingOnlyByQueryAreBothRead() {
        xml("/index.xml", "<sitemapindex " + NS + "><sitemap><loc>" + url("/c.xml?page=1") + "</loc></sitemap>"
                + "<sitemap><loc>" + url("/c.xml?page=2") + "</loc></sitemap>"
                + "<sitemap><loc>" + url("/c.xml?page=2#x") + "</loc></sitemap></sitemapindex>");
        xml("/c.xml", urlset(entry(url("/jobs/a"), null)));
        read("/index.xml", 10);
        assertThat(stub.hits("/c.xml")).isEqualTo(2);
    }

    @Test
    void childServerErrorIsSkippedWithNoteButBlockedFailsTheRun() {
        xml("/index.xml", "<sitemapindex " + NS + "><sitemap><loc>" + url("/down.xml") + "</loc></sitemap>"
                + "<sitemap><loc>" + url("/ok.xml") + "</loc></sitemap></sitemapindex>");
        stub.route("/down.xml", Stub.of(503, "down"));
        xml("/ok.xml", urlset(entry(url("/jobs/a"), null)));
        var result = read("/index.xml", 10);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.notes()).anyMatch(n -> n.contains("down.xml") && n.contains("skipped"));

        stub.route("/down.xml", Stub.of(429, "slow down"));
        assertThatThrownBy(() -> read("/index.xml", 10))
                .isInstanceOf(com.gnemirko.jobapplier.ingestion.exception.FetchBlockedException.class);
    }

    @Test
    void childParseErrorFailsTheRun() {
        xml("/index.xml", "<sitemapindex " + NS + "><sitemap><loc>" + url("/bad.xml") + "</loc></sitemap></sitemapindex>");
        xml("/bad.xml", "<urlset " + NS + "><url><loc>x</loc>");
        assertThatThrownBy(() -> read("/index.xml", 10)).isInstanceOf(FetchException.class);
        xml("/bad.xml", "<!DOCTYPE urlset [<!ENTITY e \"x\">]>" + urlset());
        assertThatThrownBy(() -> read("/index.xml", 10)).isInstanceOf(FetchException.class);
    }

    @Test
    void urlCapKeepsDocumentOrderAndNotes() {
        xml("/sitemap.xml", urlset(entry(url("/jobs/1"), null), entry(url("/jobs/2"), null), entry(url("/jobs/3"), null)));
        var result = read("/sitemap.xml", 2);
        assertThat(result.entries()).extracting(e -> e.url().getPath()).containsExactly("/jobs/1", "/jobs/2", "/jobs/3");
        assertThat(result.fetchCount()).isEqualTo(2);
        assertThat(result.notes()).anyMatch(n -> n.contains("cap"));
    }

    @Test
    void hostileDocumentsFailTheRun() {
        String[] bad = {
            "<!DOCTYPE urlset [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" + urlset(entry("&xxe;", null)),
            "<!DOCTYPE urlset>" + urlset(entry(url("/jobs/a"), null)),
            "<!DOCTYPE urlset [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>" + urlset(entry("&b;", null)),
            urlset(entry("&undefined;", null)),
            urlset("<url><loc>" + url("/jobs/a") + "</loc>"),
            "not xml at all",
            "",
            "<html " + NS + "></html>",
            "<urlset></urlset>",
        };
        for (String body : bad) {
            xml("/sitemap.xml", body);
            org.junit.jupiter.api.Assertions.assertThrows(FetchException.class, () -> read("/sitemap.xml", 10), "BODY " + body);
        }
    }

    @Test
    void rootProblemsFailTheRun() {
        assertThatThrownBy(() -> read("/missing.xml", 10)).isInstanceOf(FetchException.class).hasMessageContaining("404");
        stub.route("/redirect.xml", Stub.of(302, "", "Location", "http://elsewhere.example/s.xml"));
        assertThatThrownBy(() -> read("/redirect.xml", 10)).isInstanceOf(FetchException.class);
        xml("/closed/s.xml", urlset());
        assertThatThrownBy(() -> read("/closed/s.xml", 10)).isInstanceOf(RobotsDisallowedException.class);
        assertThat(stub.hits("/closed/s.xml")).isZero();
        xml("/long.xml", urlset(entry(url("/jobs/" + "a".repeat(2100)), null)));
        assertThatThrownBy(() -> read("/long.xml", 10)).isInstanceOf(FetchException.class).hasMessageContaining("2048");
    }

    @Test
    void moreThanFiftyThousandEntriesFails() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= SitemapReader.MAX_ENTRIES; i++) {
            sb.append("<url><loc>").append(url("/jobs/" + i)).append("</loc></url>");
        }
        xml("/huge.xml", urlset(sb.toString()));
        assertThatThrownBy(() -> read("/huge.xml", 10)).isInstanceOf(FetchException.class).hasMessageContaining("50000");
        sb.setLength(0);
        for (int i = 0; i < SitemapReader.MAX_ENTRIES; i++) {
            sb.append("<url><loc>").append(url("/jobs/" + i)).append("</loc></url>");
        }
        xml("/max.xml", urlset(sb.toString()));
        var max = read("/max.xml", 5000);
        assertThat(max.entries()).hasSize(SitemapReader.MAX_ENTRIES);
        assertThat(max.fetchCount()).isEqualTo(5000);
    }
}
