package com.neuroplan.auth.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JwtSessionRepository {
    private static final RowMapper<JwtSessionRecord> SESSION_MAPPER = (rs, rowNum) -> new JwtSessionRecord(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("token_id"),
            rs.getString("refresh_token_hash"),
            rs.getTimestamp("issued_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    public JwtSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(long userId, String tokenId, String refreshTokenHash, Instant issuedAt, Instant expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO jwt_sessions (
                    user_id, token_id, refresh_token_hash, issued_at, expires_at, revoked_at
                ) VALUES (?, ?, ?, ?, ?, NULL)
                """,
                userId,
                tokenId,
                refreshTokenHash,
                Timestamp.from(issuedAt),
                Timestamp.from(expiresAt));
    }

    public Optional<JwtSessionRecord> findActiveByTokenId(String tokenId) {
        return jdbcTemplate.query("""
                SELECT id, user_id, token_id, refresh_token_hash, issued_at, expires_at, revoked_at
                  FROM jwt_sessions
                 WHERE token_id = ?
                   AND revoked_at IS NULL
                   AND expires_at > CURRENT_TIMESTAMP(6)
                """, SESSION_MAPPER, tokenId).stream().findFirst();
    }

    public Optional<JwtSessionRecord> findActiveByRefreshTokenHash(String refreshTokenHash) {
        return jdbcTemplate.query("""
                SELECT id, user_id, token_id, refresh_token_hash, issued_at, expires_at, revoked_at
                  FROM jwt_sessions
                 WHERE refresh_token_hash = ?
                   AND revoked_at IS NULL
                   AND expires_at > CURRENT_TIMESTAMP(6)
                """, SESSION_MAPPER, refreshTokenHash).stream().findFirst();
    }

    public void revoke(long id, Instant revokedAt) {
        jdbcTemplate.update("""
                UPDATE jwt_sessions
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE id = ?
                """, Timestamp.from(revokedAt), id);
    }

    public void revokeAllForUser(long userId, Instant revokedAt) {
        jdbcTemplate.update("""
                UPDATE jwt_sessions
                   SET revoked_at = COALESCE(revoked_at, ?)
                 WHERE user_id = ?
                   AND revoked_at IS NULL
                """, Timestamp.from(revokedAt), userId);
    }
}
