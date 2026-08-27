package com.neuroplan.auth.user;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private static final RowMapper<UserRecord> USER_MAPPER = (rs, rowNum) -> new UserRecord(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("nickname"),
            rs.getString("account_status"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserRecord> findByEmail(String email) {
        return jdbcTemplate.query("""
                SELECT id, email, password_hash, nickname, account_status, created_at, updated_at
                  FROM users
                 WHERE email = ?
                """, USER_MAPPER, email).stream().findFirst();
    }

    public Optional<UserRecord> findById(long id) {
        return jdbcTemplate.query("""
                SELECT id, email, password_hash, nickname, account_status, created_at, updated_at
                  FROM users
                 WHERE id = ?
                """, USER_MAPPER, id).stream().findFirst();
    }

    public UserRecord insert(String email, String nickname, String passwordHash) {
        LocalDateTime createdAt = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users (
                        email, password_hash, nickname, account_status, created_at, updated_at
                    ) VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            statement.setString(3, nickname);
            statement.setObject(4, createdAt);
            statement.setObject(5, createdAt);
            return statement;
        }, keyHolder);

        Map<String, Object> keys = keyHolder.getKeys();
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("회원 번호를 생성하지 못했습니다.");
        }
        Number id = keys.values().stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("회원 번호를 생성하지 못했습니다."));
        return new UserRecord(id.longValue(), email, passwordHash, nickname, "ACTIVE", createdAt, createdAt);
    }

    public void updateAccountStatus(long userId, String accountStatus) {
        jdbcTemplate.update("""
                UPDATE users
                   SET account_status = ?, updated_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ?
                """, accountStatus, userId);
    }

    public void updatePasswordHash(long userId, String passwordHash) {
        jdbcTemplate.update("""
                UPDATE users
                   SET password_hash = ?, updated_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ?
                """, passwordHash, userId);
    }
}
