package com.gnemirko.jobapplier.ui.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/** A short fixed page written by a filter, with no input in it. */
public final class FixedResponse {

    private FixedResponse() {
    }

    public static void write(HttpServletResponse response, int status, String title, String text) throws IOException {
        response.setStatus(status);
        response.setContentType("text/html;charset=UTF-8");
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>" + title
                + "</title><link rel=\"stylesheet\" href=\"/ui.css\"></head><body><main><h1>" + status + " " + title
                + "</h1><p>" + text + "</p></main></body></html>";
        response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }
}
