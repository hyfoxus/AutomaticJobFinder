package com.gnemirko.jobapplier.ui.config;

import java.io.IOException;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gnemirko.jobapplier.ui.service.CsrfService;
import com.gnemirko.jobapplier.ui.util.FixedResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Every request that is not a plain read needs (1) an Origin equal to {@code http://<Host>} (without an Origin, only
 * {@code Sec-Fetch-Site: same-origin} passes; {@code Origin: null} never does) and (2) the CSRF cookie plus the
 * equal form field. Anything else is 403 and the controller is never reached.
 */
@Component
@UiEnabled
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final CsrfService csrf;

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SAFE.contains(request.getMethod()) || (originOk(request) && csrf.matches(request))) {
            chain.doFilter(request, response);
            return;
        }
        FixedResponse.write(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden", "The request was refused.");
    }

    private static boolean originOk(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return "same-origin".equals(request.getHeader("Sec-Fetch-Site"));
        }
        String host = request.getHeader("Host");
        return host != null && origin.equals("http://" + host);
    }
}
