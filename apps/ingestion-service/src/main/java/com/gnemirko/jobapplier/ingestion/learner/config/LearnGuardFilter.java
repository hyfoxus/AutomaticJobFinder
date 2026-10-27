package com.gnemirko.jobapplier.ingestion.learner.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards the learn endpoint only: Host must be localhost or 127.0.0.1 (a DNS-rebinding page gets 403) and the header
 * X-JobApplier-Run: 1 must be present (else 400). Other paths, such as /api/jobs for the scoring service, are untouched.
 */
@Component
@LearnerEnabled
public class LearnGuardFilter extends OncePerRequestFilter {

    private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Decoded and normalised like the dispatcher does, so /api/%6Cearn and /api/./learn cannot slip past.
        String path = org.springframework.util.StringUtils.cleanPath(
                UrlPathHelper.defaultInstance.getPathWithinApplication(request));
        return !path.toLowerCase(Locale.ROOT).startsWith("/api/learn");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "forbidden host");
            return;
        }
        if (!"1".equals(request.getHeader("X-JobApplier-Run"))) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "header X-JobApplier-Run: 1 is required");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
