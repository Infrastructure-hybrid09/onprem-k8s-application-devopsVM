package com.neuroplan.auth.session;

import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import com.neuroplan.auth.config.AuthProperties;
import com.neuroplan.auth.error.ApiException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private final AuthProperties properties;
    private final SecretKey signingKey;
    private final JwtParser parser;

    public JwtTokenService(AuthProperties properties) {
        this.properties = properties;
        try {
            this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwtSecretBase64()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("JWT_SECRET_BASE64 must contain a Base64-encoded key of at least 32 bytes", exception);
        }
        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .build();
    }

    public String createAccessToken(long userId, String tokenId, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(Long.toString(userId))
                .id(tokenId)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public JwtClaims parseAccessToken(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            long userId = Long.parseLong(claims.getSubject());
            String tokenId = claims.getId();
            if (tokenId == null || tokenId.isBlank()) throw new JwtException("missing jti");
            return new JwtClaims(userId, tokenId);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "로그인 토큰이 만료되었거나 올바르지 않습니다.");
        }
    }
}
