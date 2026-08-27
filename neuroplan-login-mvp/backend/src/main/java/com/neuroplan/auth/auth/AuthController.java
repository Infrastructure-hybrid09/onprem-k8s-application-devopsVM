package com.neuroplan.auth.auth;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.neuroplan.auth.auth.dto.AuthResponse;
import com.neuroplan.auth.auth.dto.ChangePasswordRequest;
import com.neuroplan.auth.auth.dto.LoginRequest;
import com.neuroplan.auth.auth.dto.SignupRequest;
import com.neuroplan.auth.auth.dto.UserResponse;
import com.neuroplan.auth.auth.dto.WithdrawRequest;
import com.neuroplan.auth.config.AuthProperties;
import com.neuroplan.auth.error.ApiException;
import com.neuroplan.auth.session.AuthCookieService;
import com.neuroplan.auth.session.JwtClaims;
import com.neuroplan.auth.session.JwtSessionRecord;
import com.neuroplan.auth.session.JwtSessionRepository;
import com.neuroplan.auth.session.JwtTokenService;
import com.neuroplan.auth.session.RefreshTokenService;
import com.neuroplan.auth.user.UserRecord;
import com.neuroplan.auth.user.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository userRepository;
    private final JwtSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final AuthCookieService cookieService;
    private final AuthProperties authProperties;
    private final CurrentUserService currentUserService;

    public AuthController(
            UserRepository userRepository,
            JwtSessionRepository sessionRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            RefreshTokenService refreshTokenService,
            AuthCookieService cookieService,
            AuthProperties authProperties,
            CurrentUserService currentUserService
    ) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.cookieService = cookieService;
        this.authProperties = authProperties;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/signup")
    @Transactional
    ResponseEntity<AuthResponse> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletResponse response
    ) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
        UserRecord user;
        try {
            user = userRepository.insert(
                    email,
                    request.nickname().trim(),
                    passwordEncoder.encode(request.password())
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
        issueTokens(user, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(UserResponse.from(user)));
    }

    @PostMapping("/login")
    @Transactional
    AuthResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        UserRecord user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(this::invalidCredentials);
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw invalidCredentials();
        }
        requireActive(user);
        issueTokens(user, response);
        return new AuthResponse(UserResponse.from(user));
    }

    @GetMapping("/me")
    AuthResponse me(HttpServletRequest request) {
        String accessToken = cookieService.accessToken(request)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        JwtClaims claims = jwtTokenService.parseAccessToken(accessToken);
        JwtSessionRecord session = sessionRepository.findActiveByTokenId(claims.tokenId())
                .filter(found -> found.userId() == claims.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인 세션이 만료되었거나 폐기되었습니다."));
        UserRecord user = userRepository.findById(session.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인 정보를 다시 확인해 주세요."));
        requireActive(user);
        return new AuthResponse(UserResponse.from(user));
    }

    @PostMapping("/refresh")
    @Transactional
    AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookieService.refreshToken(request)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Refresh Token이 없습니다."));
        JwtSessionRecord oldSession = sessionRepository
                .findActiveByRefreshTokenHash(refreshTokenService.hash(refreshToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Refresh Token이 만료되었거나 폐기되었습니다."));
        UserRecord user = userRepository.findById(oldSession.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인 정보를 다시 확인해 주세요."));
        requireActive(user);

        sessionRepository.revoke(oldSession.id(), Instant.now());
        issueTokens(user, response);
        return new AuthResponse(UserResponse.from(user));
    }

    @PostMapping("/logout")
    @Transactional
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        cookieService.refreshToken(request)
                .map(refreshTokenService::hash)
                .flatMap(sessionRepository::findActiveByRefreshTokenHash)
                .ifPresent(session -> sessionRepository.revoke(session.id(), Instant.now()));
        cookieService.clear(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/withdraw")
    @Transactional
    ResponseEntity<Void> withdraw(
            @Valid @RequestBody WithdrawRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UserRecord user = currentUserService.require(request);
        if (!passwordEncoder.matches(body.password(), user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다.");
        }
        sessionRepository.revokeAllForUser(user.id(), Instant.now());
        userRepository.updateAccountStatus(user.id(), "WITHDRAWN");
        cookieService.clear(response);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/password")
    @Transactional
    ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UserRecord user = currentUserService.require(request);
        if (!passwordEncoder.matches(body.currentPassword(), user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "현재 비밀번호가 올바르지 않습니다.");
        }
        if (passwordEncoder.matches(body.newPassword(), user.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
        }
        userRepository.updatePasswordHash(user.id(), passwordEncoder.encode(body.newPassword()));
        sessionRepository.revokeAllForUser(user.id(), Instant.now());
        cookieService.clear(response);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions")
    @Transactional
    ResponseEntity<Void> revokeAllSessions(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UserRecord user = currentUserService.require(request);
        sessionRepository.revokeAllForUser(user.id(), Instant.now());
        cookieService.clear(response);
        return ResponseEntity.noContent().build();
    }

    private void issueTokens(UserRecord user, HttpServletResponse response) {
        Instant issuedAt = Instant.now();
        String tokenId = UUID.randomUUID().toString();
        String refreshToken = refreshTokenService.generate();
        sessionRepository.insert(
                user.id(),
                tokenId,
                refreshTokenService.hash(refreshToken),
                issuedAt,
                issuedAt.plus(authProperties.refreshTokenTtl())
        );
        String accessToken = jwtTokenService.createAccessToken(user.id(), tokenId, issuedAt);
        cookieService.write(response, accessToken, refreshToken);
    }

    private void requireActive(UserRecord user) {
        if (!"ACTIVE".equals(user.accountStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "사용할 수 없는 계정 상태입니다: " + user.accountStatus());
        }
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
