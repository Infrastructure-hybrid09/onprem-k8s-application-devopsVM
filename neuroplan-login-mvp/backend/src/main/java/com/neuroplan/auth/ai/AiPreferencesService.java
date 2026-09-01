package com.neuroplan.auth.ai;

import java.util.Locale;

import com.neuroplan.auth.error.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiPreferencesService {
    public static final String PRIVACY_NOTICE_VERSION = "neuroplan-ai-v1";

    private final JdbcTemplate jdbcTemplate;

    public AiPreferencesService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensure(long userId) {
        jdbcTemplate.update("""
                INSERT INTO user_ai_preferences (
                    user_id, ai_enabled, ai_consent_at, privacy_notice_version,
                    explanation_style, available_minutes, created_at, updated_at
                ) VALUES (?, FALSE, NULL, NULL, 'BRIEF', 30, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE user_id = VALUES(user_id)
                """, userId);
    }

    public AiPreferenceResponse status(long userId) {
        ensure(userId);
        return jdbcTemplate.query("""
                SELECT ai_enabled, ai_consent_at, privacy_notice_version,
                       explanation_style, available_minutes, updated_at
                  FROM user_ai_preferences
                 WHERE user_id = ?
                """, (rs, rowNum) -> new AiPreferenceResponse(
                rs.getBoolean("ai_enabled"),
                rs.getTimestamp("ai_consent_at") == null ? null : rs.getTimestamp("ai_consent_at").toInstant(),
                rs.getString("privacy_notice_version"),
                rs.getString("explanation_style"),
                rs.getInt("available_minutes"),
                rs.getTimestamp("updated_at").toInstant()
        ), userId).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR, "AI 사용 설정을 확인하지 못했습니다."
        ));
    }

    @Transactional
    public AiPreferenceResponse update(long userId, boolean enabled, boolean consent,
                                       String explanationStyle, int availableMinutes) {
        String style = normalizeStyle(explanationStyle);
        if (availableMinutes < 5 || availableMinutes > 240) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "희망 학습 시간은 5분에서 240분 사이여야 합니다.");
        }
        AiPreferenceResponse current = status(userId);
        if (enabled && current.consentAt() == null && !consent) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "외부 AI 처리 안내에 동의해야 AI 기능을 사용할 수 있습니다.");
        }
        if (enabled && consent) {
            jdbcTemplate.update("""
                    UPDATE user_ai_preferences
                       SET ai_enabled = TRUE,
                           ai_consent_at = COALESCE(ai_consent_at, CURRENT_TIMESTAMP(6)),
                           privacy_notice_version = ?, explanation_style = ?, available_minutes = ?,
                           updated_at = CURRENT_TIMESTAMP(6)
                     WHERE user_id = ?
                    """, PRIVACY_NOTICE_VERSION, style, availableMinutes, userId);
        } else {
            jdbcTemplate.update("""
                    UPDATE user_ai_preferences
                       SET ai_enabled = ?, explanation_style = ?, available_minutes = ?,
                           updated_at = CURRENT_TIMESTAMP(6)
                     WHERE user_id = ?
                    """, enabled, style, availableMinutes, userId);
        }
        return status(userId);
    }

    public AiPreferenceResponse requireEnabled(long userId) {
        AiPreferenceResponse preference = status(userId);
        if (!preference.enabled() || preference.consentAt() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI 기능 사용 동의와 활성화가 필요합니다.");
        }
        return preference;
    }

    private String normalizeStyle(String value) {
        String style = value == null ? "BRIEF" : value.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("BRIEF", "DETAILED", "PRACTICAL").contains(style)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI 설명 방식이 올바르지 않습니다.");
        }
        return style;
    }

    public record AiPreferenceResponse(
            boolean enabled,
            java.time.Instant consentAt,
            String privacyNoticeVersion,
            String explanationStyle,
            int availableMinutes,
            java.time.Instant updatedAt
    ) {}
}
