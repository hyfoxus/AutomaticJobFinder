package com.gnemirko.jobapplier.ui.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.gnemirko.jobapplier.ui.config.UiEnabled;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CSRF token kept in a cookie (the UI stores nothing). The token is 32 random bytes, base64url, followed by an HMAC
 * made under a key that exists only in memory, so a cookie injected from outside is recognised as foreign.
 * The same value goes into a hidden form field and the two are compared in constant time.
 */
@Service
@UiEnabled
public class CsrfService {

    public static final String COOKIE = "ui_csrf";
    public static final String FIELD = "_csrf";

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random = new SecureRandom();
    private final byte[] key = new byte[32];

    public CsrfService() {
        random.nextBytes(key);
    }

    /** The visitor's valid token, or a new one (set as a cookie on this response). Never rotated. */
    public String tokenFor(HttpServletRequest request, HttpServletResponse response) {
        String existing = cookieToken(request);
        if (existing != null) {
            return existing;
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String body = B64.encodeToString(raw);
        String token = body + "." + sign(body);
        // No Domain, Max-Age or Secure: plain http on loopback, a session cookie.
        response.addHeader("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        return token;
    }

    /** True when the request carries a valid cookie token and a form field equal to it. */
    public boolean matches(HttpServletRequest request) {
        String cookie = cookieToken(request);
        String field = request.getParameter(FIELD);
        if (cookie == null || field == null || field.length() != cookie.length()) {
            return false;
        }
        return MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), field.getBytes(StandardCharsets.UTF_8));
    }

    private String cookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName()) && valid(c.getValue())) {
                return c.getValue();
            }
        }
        return null;
    }

    private boolean valid(String token) {
        if (token == null || token.length() > 200) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return false;
        }
        String expected = sign(token.substring(0, dot));
        String actual = token.substring(dot + 1);
        return expected.length() == actual.length()
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC is not available", e);
        }
    }
}
