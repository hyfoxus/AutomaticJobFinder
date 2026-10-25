package com.gnemirko.jobapplier.ui.config;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gnemirko.jobapplier.ui.util.FixedResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Guards every path (no decision is made from the raw URI): only a Host on {@code ui.allowed-hosts}
 * (default localhost, 127.0.0.1) is served, so a DNS-rebinding page gets 403.
 */
@Component
@UiEnabled
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HostGuardFilter extends OncePerRequestFilter {

    private final Set<String> allowedHosts;

    public HostGuardFilter(UiProperties props) {
        this.allowedHosts = Set.copyOf(props.normalizedHosts());
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName();
        if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            FixedResponse.write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "This host name is not allowed.");
            return;
        }
        chain.doFilter(request, response);
    }
}
