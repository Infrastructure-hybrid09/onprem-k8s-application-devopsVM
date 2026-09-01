package com.neuroplan.auth.ai;

import java.util.List;

import com.neuroplan.auth.error.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiQuotaService {
    private static final int LEGACY_DEFAULT_DAILY_LIMIT = 5000;
    private final JdbcTemplate jdbcTemplate;
    private final AiProperties properties;

    public AiQuotaService(JdbcTemplate jdbcTemplate, AiProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void ensureQuota(long userId) {
        jdbcTemplate.update("""
                INSERT INTO ai_token_quotas (
                    user_id, daily_token_limit, monthly_token_limit, is_enabled, created_at, updated_at
                ) VALUES (?, ?, NULL, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    updated_at = CASE
                        WHEN daily_token_limit = ? THEN CURRENT_TIMESTAMP(6)
                        ELSE updated_at
                    END,
                    daily_token_limit = CASE
                        WHEN daily_token_limit = ? THEN VALUES(daily_token_limit)
                        ELSE daily_token_limit
                    END
                """, userId, properties.getDailyTokenLimit(),
                LEGACY_DEFAULT_DAILY_LIMIT, LEGACY_DEFAULT_DAILY_LIMIT);
    }

    public AiQuotaResponse status(long userId) {
        ensureQuota(userId);
        AiQuotaResponse quota = jdbcTemplate.query("""
                SELECT q.daily_token_limit, q.monthly_token_limit, q.is_enabled,
                       COALESCE(SUM(CASE WHEN l.created_at >= CURRENT_DATE THEN l.token_delta ELSE 0 END), 0) AS used_today,
                       COALESCE(SUM(CASE WHEN l.created_at >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01') THEN l.token_delta ELSE 0 END), 0) AS used_month
                  FROM ai_token_quotas q
             LEFT JOIN ai_token_ledger l ON l.user_id = q.user_id
                 WHERE q.user_id = ?
                 GROUP BY q.user_id, q.daily_token_limit, q.monthly_token_limit, q.is_enabled
                """, (rs, rowNum) -> {
            int dailyLimit = rs.getInt("daily_token_limit");
            Integer monthlyLimit = (Integer) rs.getObject("monthly_token_limit");
            int usedToday = Math.max(rs.getInt("used_today"), 0);
            int usedMonth = Math.max(rs.getInt("used_month"), 0);
            return new AiQuotaResponse(
                    rs.getBoolean("is_enabled"),
                    dailyLimit,
                    usedToday,
                    Math.max(dailyLimit - usedToday, 0),
                    monthlyLimit,
                    usedMonth,
                    monthlyLimit == null ? null : Math.max(monthlyLimit - usedMonth, 0),
                    List.of()
            );
        }, userId).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR, "AI 사용 한도를 확인하지 못했습니다."
        ));
        return new AiQuotaResponse(
                quota.enabled(), quota.dailyLimit(), quota.usedToday(), quota.remainingToday(),
                quota.monthlyLimit(), quota.usedMonth(), quota.remainingMonth(), featureAverages());
    }

    private List<AiFeatureTokenAverage> featureAverages() {
        return jdbcTemplate.query("""
                SELECT usage.feature, ROUND(AVG(usage.token_delta)) AS average_tokens,
                       COUNT(*) AS sample_count
                  FROM (
                        SELECT CASE
                                 WHEN r.request_type = 'PLAN'
                                  AND JSON_VALUE(r.output_json, '$.questions[0].questionNo') IS NOT NULL
                                   THEN 'QUESTION_DRAFT'
                                 WHEN r.request_type = 'PLAN'
                                  AND JSON_VALUE(r.output_json, '$.priority') IS NOT NULL
                                   THEN 'WEEKLY_INSIGHT'
                                 ELSE r.request_type
                               END AS feature,
                               charged.token_delta
                          FROM ai_generation_runs r
                          JOIN ai_token_ledger charged
                            ON charged.generation_run_id = r.id
                           AND charged.entry_type = 'USAGE'
                     LEFT JOIN ai_token_ledger refunded
                            ON refunded.generation_run_id = r.id
                           AND refunded.entry_type = 'REFUND'
                         WHERE r.generation_status = 'SUCCEEDED'
                           AND refunded.id IS NULL
                       ) usage
                 WHERE usage.feature IN ('PLAN', 'WRONG_FEEDBACK', 'WEEKLY_INSIGHT', 'QUESTION_DRAFT')
                 GROUP BY usage.feature
                 ORDER BY FIELD(usage.feature, 'PLAN', 'QUESTION_DRAFT', 'WRONG_FEEDBACK', 'WEEKLY_INSIGHT')
                """, (rs, rowNum) -> new AiFeatureTokenAverage(
                rs.getString("feature"), rs.getInt("average_tokens"), rs.getInt("sample_count")
        ));
    }

    public AiQuotaResponse requireAvailable(long userId) {
        AiQuotaResponse quota = status(userId);
        if (!quota.enabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI 기능 사용이 중지된 계정입니다.");
        }
        if (quota.remainingToday() <= 0 || (quota.remainingMonth() != null && quota.remainingMonth() <= 0)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "사용 가능한 AI 토큰을 모두 사용했습니다.");
        }
        return quota;
    }

    @Transactional
    public AiQuotaResponse recordUsage(long userId, long generationRunId, int inputTokens, int outputTokens,
                                       java.math.BigDecimal usageUnits) {
        int total = Math.max(inputTokens, 0) + Math.max(outputTokens, 0);
        if (total == 0) return status(userId);
        jdbcTemplate.update("""
                INSERT INTO ai_token_ledger (
                    user_id, generation_run_id, entry_type, token_delta,
                    input_tokens, output_tokens, provider_usage_units, provider_usage_unit,
                    description, created_at
                ) VALUES (?, ?, 'USAGE', ?, ?, ?, ?, 'NEURONS', 'Cloudflare Workers AI 사용', CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id = id
                """, userId, generationRunId, total, inputTokens, outputTokens, usageUnits);
        return status(userId);
    }

    @Transactional
    public AiQuotaResponse refundUsage(long userId, long generationRunId, String reason) {
        jdbcTemplate.update("""
                INSERT INTO ai_token_ledger (
                    user_id, generation_run_id, entry_type, token_delta,
                    input_tokens, output_tokens, provider_usage_units, provider_usage_unit,
                    description, created_at
                )
                SELECT user_id, generation_run_id, 'REFUND', -token_delta,
                       input_tokens, output_tokens, provider_usage_units, provider_usage_unit,
                       ?, CURRENT_TIMESTAMP(6)
                 FROM ai_token_ledger
                 WHERE user_id = ? AND generation_run_id = ? AND entry_type = 'USAGE'
                ON DUPLICATE KEY UPDATE description = VALUES(description)
                """, truncateReason(reason), userId, generationRunId);
        return status(userId);
    }

    @Transactional
    public AiQuotaResponse recordRefundedUsage(long userId, long generationRunId,
                                               int inputTokens, int outputTokens,
                                               java.math.BigDecimal usageUnits, String reason) {
        recordUsage(userId, generationRunId, inputTokens, outputTokens, usageUnits);
        return refundUsage(userId, generationRunId, reason);
    }

    private String truncateReason(String reason) {
        String value = reason == null || reason.isBlank() ? "AI 요청 처리 실패 환불" : reason.trim();
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    public record AiQuotaResponse(
            boolean enabled,
            int dailyLimit,
            int usedToday,
            int remainingToday,
            Integer monthlyLimit,
            int usedMonth,
            Integer remainingMonth,
            List<AiFeatureTokenAverage> featureAverages
    ) {}

    public record AiFeatureTokenAverage(String feature, int averageTokens, int sampleCount) {}
}
