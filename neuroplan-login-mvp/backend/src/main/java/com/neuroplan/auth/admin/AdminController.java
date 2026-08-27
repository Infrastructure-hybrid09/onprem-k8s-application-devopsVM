package com.neuroplan.auth.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.neuroplan.auth.error.ApiException;
import com.neuroplan.auth.session.JwtSessionRepository;
import com.neuroplan.auth.user.UserRecord;
import com.neuroplan.auth.user.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final Set<String> ACCOUNT_STATUSES = Set.of("ACTIVE", "LOCKED", "WITHDRAWN");

    private final JdbcTemplate jdbcTemplate;
    private final AdminAccessService adminAccessService;
    private final UserRepository userRepository;
    private final JwtSessionRepository sessionRepository;

    public AdminController(
            JdbcTemplate jdbcTemplate,
            AdminAccessService adminAccessService,
            UserRepository userRepository,
            JwtSessionRepository sessionRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminAccessService = adminAccessService;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
    }

    @GetMapping("/overview")
    public AdminOverviewResponse overview(HttpServletRequest request) {
        UserRecord admin = adminAccessService.require(request);
        Counts counts = jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) AS total_users,
                    SUM(account_status = 'ACTIVE') AS active_users,
                    SUM(account_status = 'LOCKED') AS locked_users,
                    SUM(account_status = 'WITHDRAWN') AS withdrawn_users
                  FROM users
                """, (rs, rowNum) -> new Counts(
                rs.getLong("total_users"),
                rs.getLong("active_users"),
                rs.getLong("locked_users"),
                rs.getLong("withdrawn_users")
        ));
        long activeSubjects = count("SELECT COUNT(*) FROM subjects WHERE is_active = TRUE");
        long todayPlans = count("SELECT COUNT(*) FROM daily_plans WHERE plan_date = CURRENT_DATE");
        long todayAttempts = count("""
                SELECT COUNT(*) FROM diagnosis_attempts
                 WHERE completed_at >= CURRENT_DATE
                   AND completed_at < CURRENT_DATE + INTERVAL 1 DAY
                """);
        List<AdminUserResponse> users = jdbcTemplate.query("""
                SELECT u.id, u.email, u.nickname, u.account_status, u.created_at,
                       COUNT(DISTINCT us.subject_id) AS subject_count,
                       MAX(js.issued_at) AS last_session_at
                  FROM users u
                  LEFT JOIN user_subjects us ON us.user_id = u.id
                  LEFT JOIN jwt_sessions js ON js.user_id = u.id
                 GROUP BY u.id, u.email, u.nickname, u.account_status, u.created_at
                 ORDER BY u.created_at DESC, u.id DESC
                 LIMIT 50
                """, (rs, rowNum) -> new AdminUserResponse(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("nickname"),
                rs.getString("account_status"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getInt("subject_count"),
                timestampToLocalDateTime(rs.getTimestamp("last_session_at"))
        ));
        return new AdminOverviewResponse(
                admin.id(), admin.email(), counts, activeSubjects, todayPlans, todayAttempts, users
        );
    }

    @PatchMapping("/users/{userId}/status")
    @Transactional
    public AdminUserResponse updateUserStatus(
            @PathVariable long userId,
            @Valid @RequestBody AccountStatusUpdateRequest body,
            HttpServletRequest request
    ) {
        UserRecord admin = adminAccessService.require(request);
        if (admin.id() == userId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "현재 로그인한 관리자 자신의 상태는 변경할 수 없습니다.");
        }
        String status = body.status().trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNT_STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "계정 상태는 ACTIVE, LOCKED, WITHDRAWN 중 하나여야 합니다.");
        }
        UserRecord target = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));
        userRepository.updateAccountStatus(target.id(), status);
        if (!"ACTIVE".equals(status)) {
            sessionRepository.revokeAllForUser(target.id(), Instant.now());
        }
        return jdbcTemplate.query("""
                SELECT u.id, u.email, u.nickname, u.account_status, u.created_at,
                       COUNT(DISTINCT us.subject_id) AS subject_count,
                       MAX(js.issued_at) AS last_session_at
                  FROM users u
                  LEFT JOIN user_subjects us ON us.user_id = u.id
                  LEFT JOIN jwt_sessions js ON js.user_id = u.id
                 WHERE u.id = ?
                 GROUP BY u.id, u.email, u.nickname, u.account_status, u.created_at
                """, (rs, rowNum) -> new AdminUserResponse(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("nickname"),
                rs.getString("account_status"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getInt("subject_count"),
                timestampToLocalDateTime(rs.getTimestamp("last_session_at"))
        ), userId).stream().findFirst().orElseThrow();
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private LocalDateTime timestampToLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    public record AdminOverviewResponse(
            long adminUserId,
            String adminEmail,
            Counts users,
            long activeSubjects,
            long todayPlans,
            long todayAttempts,
            List<AdminUserResponse> recentUsers
    ) {}

    public record Counts(long total, long active, long locked, long withdrawn) {}

    public record AdminUserResponse(
            long id,
            String email,
            String nickname,
            String accountStatus,
            LocalDateTime createdAt,
            int subjectCount,
            LocalDateTime lastSessionAt
    ) {}

    public record AccountStatusUpdateRequest(@NotBlank String status) {}
}
