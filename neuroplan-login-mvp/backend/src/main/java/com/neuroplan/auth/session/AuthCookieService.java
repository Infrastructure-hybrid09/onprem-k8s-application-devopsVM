package com.neuroplan.auth.session;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import com.neuroplan.auth.config.AuthProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {
    public static final String ACCESS_COOKIE = "NEUROPLAN_ACCESS";
    public static final String REFRESH_COOKIE = "NEUROPLAN_REFRESH";

    private final AuthProperties properties;

    public AuthCookieService(AuthProperties properties) {
        this.properties = properties;
    }

    public void write(HttpServletResponse response, String accessToken, String refreshToken) {
        add(response, ACCESS_COOKIE, accessToken, "/", properties.accessTokenTtl());
        add(response, REFRESH_COOKIE, refreshToken, "/api/auth", properties.refreshTokenTtl());
    }

    public void clear(HttpServletResponse response) {
        add(response, ACCESS_COOKIE, "", "/", Duration.ZERO);
        add(response, REFRESH_COOKIE, "", "/api/auth", Duration.ZERO);
    }

    public Optional<String> accessToken(HttpServletRequest request) {
        return cookie(request, ACCESS_COOKIE);
    }

    public Optional<String> refreshToken(HttpServletRequest request) {
        return cookie(request, REFRESH_COOKIE);
    }

    private Optional<String> cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private void add(HttpServletResponse response, String name, String value, String path, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.secureCookies())
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
