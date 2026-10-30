package com.gnemirko.jobapplier.ingestion.source.recipe;

import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.gnemirko.jobapplier.ingestion.dto.Recipe;

import com.gnemirko.jobapplier.ingestion.util.UrlPolicy;

import tools.jackson.databind.JsonNode;

/** The static checks of a json-api recipe block. Throws IllegalArgumentException describing the first problem. */
public final class JsonApiValidator {

    public static final String FIELD_URL = "url";
    /** What a json-api recipe may map: the embedded-json fields plus the job url. */
    public static final Set<String> FIELD_NAMES;
    static final int MAX_PAGES = 50;
    static final int MAX_SIZE = 1000;
    static final int MAX_ITEMS = 2000;
    static final int MAX_URL_LENGTH = 500;
    static final int MAX_JOIN = 10;

    static {
        Set<String> names = new HashSet<>(EmbeddedJsonParser.FIELD_NAMES);
        names.add(FIELD_URL);
        FIELD_NAMES = Set.copyOf(names);
    }

    private JsonApiValidator() {
    }

    public static void validate(Recipe.JsonApi api, Recipe.Detail detail) {
        if (api == null) {
            throw new IllegalArgumentException("jsonApi is required");
        }
        URI endpoint = checkUrl(api.url(), "jsonApi.url", false);
        if (!"GET".equals(api.method()) && !"POST".equals(api.method())) {
            throw new IllegalArgumentException("jsonApi.method must be GET or POST");
        }
        Recipe.Paging paging = api.paging();
        if (paging == null) {
            throw new IllegalArgumentException("jsonApi.paging is required");
        }
        if (!"page".equals(paging.kind()) && !"offset".equals(paging.kind())) {
            throw new IllegalArgumentException("paging.kind must be page or offset");
        }
        if (paging.start() < 0 || paging.start() > 1_000_000) {
            throw new IllegalArgumentException("paging.start must be 0..1000000");
        }
        if (paging.size() == null || paging.size() < 1 || paging.size() > MAX_SIZE) {
            throw new IllegalArgumentException("paging.size must be 1.." + MAX_SIZE);
        }
        if (paging.maxPages() < 1 || paging.maxPages() > MAX_PAGES) {
            throw new IllegalArgumentException("paging.maxPages must be 1.." + MAX_PAGES);
        }
        if (paging.totalPath() != null) {
            JsonPath.compile(paging.totalPath());
        }
        if (api.maxItems() < 1 || api.maxItems() > MAX_ITEMS) {
            throw new IllegalArgumentException("jsonApi.maxItems must be 1.." + MAX_ITEMS);
        }
        JsonNode body = api.bodyTemplate() == null || api.bodyTemplate().isNull() ? null : api.bodyTemplate();
        if ("GET".equals(api.method())) {
            if (body != null) {
                throw new IllegalArgumentException("bodyTemplate does not belong to method GET");
            }
        } else if (body == null) {
            throw new IllegalArgumentException("bodyTemplate is required for method POST");
        } else if (!body.isContainer()) {
            throw new IllegalArgumentException("bodyTemplate must be a JSON object or array");
        }
        RequestTemplate.validateBody(api.bodyTemplate(), paging.kind());
        RequestTemplate.validateQuery(api.query(), paging.kind());
        if (api.itemsPath() == null) {
            throw new IllegalArgumentException("jsonApi.itemsPath is required");
        }
        JsonPath.compile(api.itemsPath());
        validateFields(api);
        if (api.jobUrlTemplate() != null) {
            checkJobUrlTemplate(api.jobUrlTemplate(), endpoint, detail != null);
        }
        String slot = "page".equals(paging.kind()) ? RequestTemplate.PAGE : RequestTemplate.OFFSET;
        if (paging.maxPages() > 1 && !RequestTemplate.usesSlot(body, api.query(), slot)) {
            throw new IllegalArgumentException("with paging.maxPages above 1, " + slot
                    + " must appear in the query or bodyTemplate");
        }
    }

    private static void validateFields(Recipe.JsonApi api) {
        for (Map.Entry<String, JsonNode> e : api.fields().entrySet()) {
            JsonNode join = e.getValue() == null ? null : e.getValue().get("join");
            if (join != null && join.isArray() && join.size() > MAX_JOIN) {
                throw new IllegalArgumentException("field '" + e.getKey() + "' joins more than " + MAX_JOIN + " paths");
            }
        }
        new JsonFieldMapper(api.fields(), Map.of(), null, FIELD_NAMES, true);
        boolean mappedUrl = api.fields().containsKey(FIELD_URL);
        boolean template = api.jobUrlTemplate() != null;
        if (mappedUrl == template) {
            throw new IllegalArgumentException("set exactly one of fields.url and jobUrlTemplate");
        }
    }

    private static void checkJobUrlTemplate(String template, URI endpoint, boolean hasDetail) {
        int at = template.indexOf("{id}");
        if (at < 0 || at != template.lastIndexOf("{id}")) {
            throw new IllegalArgumentException("jobUrlTemplate must contain {id} once");
        }
        int authorityEnd = template.length();
        for (char c : new char[] {'/', '?'}) {
            int i = template.indexOf(c, "https://".length());
            if (i >= 0) {
                authorityEnd = Math.min(authorityEnd, i);
            }
        }
        if (at < authorityEnd) {
            throw new IllegalArgumentException("{id} may only appear in the path or query of jobUrlTemplate");
        }
        URI uri = checkUrl(template.replace("{id}", "x"), "jobUrlTemplate", true);
        if (unsafePath(template.replace("{id}", "x"))) {
            throw new IllegalArgumentException("jobUrlTemplate has a dot segment, '//' or an encoded slash or dot");
        }
        if (hasDetail && !sameHost(uri, endpoint)) {
            throw new IllegalArgumentException("with a detail block, jobUrlTemplate must be on the endpoint's host");
        }
    }

    private static URI checkUrl(String url, String label, boolean allowQuery) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH
                || url.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))) {
            throw new IllegalArgumentException(label + " is required (at most " + MAX_URL_LENGTH
                    + " characters, no whitespace or control characters)");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(label + " is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(label + " must be an https URL with a host and no credentials");
        }
        if (uri.getRawFragment() != null || url.contains("#")) {
            throw new IllegalArgumentException(label + " must not have a fragment");
        }
        if (!allowQuery && (uri.getRawQuery() != null || url.contains("?"))) {
            throw new IllegalArgumentException(label + " must not have a query; use jsonApi.query");
        }
        return uri;
    }

    /** Same normalized host key (trailing dots, case and default ports ignored). */
    static boolean sameHost(URI a, URI b) {
        return a.getHost() != null && b.getHost() != null && UrlPolicy.hostKey(a).equals(UrlPolicy.hostKey(b));
    }

    private static final Pattern ENCODED_TRAVERSAL = Pattern.compile("%2e|%2f|%5c", Pattern.CASE_INSENSITIVE);

    /**
     * True when the path part of a url or relative reference has a dot segment, an empty segment, a backslash or an
     * encoded dot, slash or backslash. A scheme and authority are skipped; the query and fragment are not looked at.
     */
    static boolean unsafePath(String url) {
        String path = url;
        if (path.startsWith("https://") || path.startsWith("http://")) {
            int slash = path.indexOf('/', path.indexOf("//") + 2);
            path = slash < 0 ? "" : path.substring(slash);
        }
        int cut = path.length();
        for (char c : new char[] {'?', '#'}) {
            int i = path.indexOf(c);
            if (i >= 0) {
                cut = Math.min(cut, i);
            }
        }
        path = path.substring(0, cut);
        if (path.contains("//") || path.contains("\\") || ENCODED_TRAVERSAL.matcher(path).find()) {
            return true;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
