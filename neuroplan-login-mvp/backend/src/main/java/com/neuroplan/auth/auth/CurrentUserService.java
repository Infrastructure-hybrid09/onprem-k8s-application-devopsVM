package com.neuroplan.auth.auth;

import com.neuroplan.auth.error.ApiException;
import com.neuroplan.auth.session.AuthCookieService;
import com.neuroplan.auth.session.JwtClaims;
import com.neuroplan.auth.session.JwtSessionRepository;
import com.neuroplan.auth.session.JwtTokenService;
import com.neuroplan.auth.user.UserRecord;
import com.neuroplan.auth.user.UserRepository;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CurrentUserService {
    private final AuthCookieService cookieService;
    private final JwtTokenService jwtTokenService;
    private final JwtSessionRepository sessionRepository;
    private final UserRepository userRepository;

    public CurrentUserService(
            AuthCookieService cookieService,
            JwtTokenService jwtTokenService,
            JwtSessionRepository sessionRepository,
            UserRepository userRepository
    ) {
        this.cookieService = cookieService;
        this.jwtTokenService = jwtTokenService;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    public UserRecord require(HttpServletRequest request) {
        String accessToken = cookieService.accessToken(request)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        JwtClaims claims = jwtTokenService.parseAccessToken(accessToken);
        long userId = sessionRepository.findActiveByTokenId(claims.tokenId())
                .filter(session -> session.userId() == claims.userId())
                .map(session -> session.userId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "로그인 세션이 만료되었거나 폐기되었습니다."
                ));
        UserRecord user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "로그인 정보를 다시 확인해 주세요."
                ));
        if (!"ACTIVE".equals(user.accountStatus())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "사용할 수 없는 계정 상태입니다: " + user.accountStatus()
            );
        }
        return user;
    }
}
