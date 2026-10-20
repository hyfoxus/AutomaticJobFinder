package com.gnemirko.jobapplier.scoring.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards every path (no decision is made from the raw URI, which can be encoded or padded): only a Host of localhost or 127.0.0.1 is served (a DNS-rebinding page gets 403), and every
 * answer carries nosniff. No CORS headers are ever added.
 */
@Component
@ScoringEnabled
public class ApiGuardFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_HOSTS = Set.of("localhost", "127.0.0.1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!ALLOWED_HOSTS.contains(request.getServerName().toLowerCase(java.util.Locale.ROOT))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write("{\"error\":\"forbidden host\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
