package com.gnemirko.jobapplier.ingestion.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Always on: /api/health/** answers only to an allowed Host (a DNS-rebinding page gets 403). No CORS headers are added. */
@Component
public class HealthGuardFilter extends OncePerRequestFilter {

    private final Set<String> hosts;

    public HealthGuardFilter(@Value("${ingestion.api.allowed-hosts:localhost,127.0.0.1}") String allowedHosts) {
        this.hosts = Arrays.stream(allowedHosts.split(",")).map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
        return !path.toLowerCase(Locale.ROOT).startsWith("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hosts.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write("{\"error\":\"forbidden host\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}
