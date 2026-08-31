package com.neuroplan.auth.auth;

import java.time.Instant;

import com.neuroplan.auth.auth.dto.ReauthResponse;
import com.neuroplan.auth.config.AuthProperties;
import com.neuroplan.auth.error.ApiException;
import com.neuroplan.auth.session.AuthCookieService;
import com.neuroplan.auth.session.JwtClaims;
import com.neuroplan.auth.session.JwtTokenService;
import com.neuroplan.auth.user.UserRecord;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class ReauthService {
    private final CurrentUserService currentUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final AuthCookieService cookieService;
    private final AuthProperties authProperties;

    public ReauthService(
            CurrentUserService currentUserService,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            AuthCookieService cookieService,
            AuthProperties authProperties
    ) {
        this.currentUserService = currentUserService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.cookieService = cookieService;
        this.authProperties = authProperties;
    }

    public ReauthResponse verify(
            String password,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UserRecord user = currentUserService.require(request);
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "현재 비밀번호가 올바르지 않습니다.");
        }
        JwtClaims accessClaims = accessClaims(request);
        Instant issuedAt = Instant.now();
        String reauthToken = jwtTokenService.createReauthToken(user.id(), accessClaims.tokenId(), issuedAt);
        cookieService.writeReauth(response, reauthToken);
        return new ReauthResponse(issuedAt.plus(authProperties.reauthTokenTtl()));
    }

    public UserRecord require(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        JwtClaims accessClaims = accessClaims(request);
        String reauthToken = cookieService.reauthToken(request)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "계정 보안 확인이 필요합니다. 비밀번호를 다시 입력해 주세요."
                ));
        JwtClaims reauthClaims = jwtTokenService.parseReauthToken(reauthToken);
        if (reauthClaims.userId() != user.id()
                || reauthClaims.userId() != accessClaims.userId()
                || !reauthClaims.tokenId().equals(accessClaims.tokenId())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "현재 로그인 세션에서 비밀번호를 다시 확인해 주세요.");
        }
        return user;
    }

    private JwtClaims accessClaims(HttpServletRequest request) {
        String accessToken = cookieService.accessToken(request)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        return jwtTokenService.parseAccessToken(accessToken);
    }
}
