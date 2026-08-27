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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/users")
    public AdminUserPageResponse users(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "페이지는 0 이상, 크기는 1 이상 100 이하여야 합니다.");
        }
        String normalizedQuery = query.trim();
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isEmpty() && !ACCOUNT_STATUSES.contains(normalizedStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "계정 상태 필터가 올바르지 않습니다.");
        }
        String like = "%" + normalizedQuery + "%";
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM users
                 WHERE (? = '' OR email LIKE ? OR nickname LIKE ?)
                   AND (? = '' OR account_status = ?)
                """, Long.class, normalizedQuery, like, like, normalizedStatus, normalizedStatus);
        List<AdminUserResponse> content = jdbcTemplate.query("""
                SELECT u.id, u.email, u.nickname, u.account_status, u.created_at,
                       COUNT(DISTINCT us.subject_id) AS subject_count,
                       MAX(js.issued_at) AS last_session_at
                  FROM users u
             LEFT JOIN user_subjects us ON us.user_id = u.id
             LEFT JOIN jwt_sessions js ON js.user_id = u.id
                 WHERE (? = '' OR u.email LIKE ? OR u.nickname LIKE ?)
                   AND (? = '' OR u.account_status = ?)
                 GROUP BY u.id, u.email, u.nickname, u.account_status, u.created_at
                 ORDER BY u.created_at DESC, u.id DESC
                 LIMIT ? OFFSET ?
                """, (rs, rowNum) -> new AdminUserResponse(
                rs.getLong("id"), rs.getString("email"), rs.getString("nickname"),
                rs.getString("account_status"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getInt("subject_count"), timestampToLocalDateTime(rs.getTimestamp("last_session_at"))
        ), normalizedQuery, like, like, normalizedStatus, normalizedStatus, size, page * size);
        return new AdminUserPageResponse(content, total == null ? 0 : total, page, size);
    }

    @GetMapping("/statistics/subjects")
    public List<AdminSubjectStatisticsResponse> subjectStatistics(HttpServletRequest request) {
        adminAccessService.require(request);
        return jdbcTemplate.query("""
                SELECT s.id, s.code, s.name,
                       COALESCE(learners.learner_count, 0) AS learner_count,
                       COALESCE(attempts.attempt_count, 0) AS attempt_count,
                       COALESCE(attempts.solved_count, 0) AS solved_count,
                       COALESCE(attempts.correct_count, 0) AS correct_count
                  FROM subjects s
             LEFT JOIN (
                       SELECT subject_id, COUNT(DISTINCT user_id) AS learner_count
                         FROM user_subjects GROUP BY subject_id
                       ) learners ON learners.subject_id = s.id
             LEFT JOIN (
                       SELECT subject_id, COUNT(*) AS attempt_count,
                              SUM(total_questions) AS solved_count,
                              SUM(correct_answers) AS correct_count
                         FROM diagnosis_attempts
                        WHERE attempt_status = 'COMPLETED'
                        GROUP BY subject_id
                       ) attempts ON attempts.subject_id = s.id
                 ORDER BY learner_count DESC, s.name
                """, (rs, rowNum) -> new AdminSubjectStatisticsResponse(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getLong("learner_count"), rs.getLong("attempt_count"),
                rs.getLong("solved_count"), rs.getLong("correct_count")
        ));
    }

    @GetMapping("/users/{userId}/learning")
    public AdminUserLearningResponse userLearning(
            @PathVariable long userId,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        UserRecord user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));
        List<String> subjects = jdbcTemplate.query("""
                SELECT CONCAT(s.name, ' · ', us.learning_level)
                  FROM user_subjects us JOIN subjects s ON s.id = us.subject_id
                 WHERE us.user_id = ? ORDER BY us.slot_no
                """, (rs, rowNum) -> rs.getString(1), userId);
        long planCount = countForUser("SELECT COUNT(*) FROM daily_plans WHERE user_id = ?", userId);
        long completedPlanCount = countForUser("SELECT COUNT(*) FROM daily_plans WHERE user_id = ? AND plan_status = 'COMPLETED'", userId);
        long solvedCount = countForUser("SELECT COALESCE(SUM(total_questions), 0) FROM diagnosis_attempts WHERE user_id = ? AND attempt_status = 'COMPLETED'", userId);
        long correctCount = countForUser("SELECT COALESCE(SUM(correct_answers), 0) FROM diagnosis_attempts WHERE user_id = ? AND attempt_status = 'COMPLETED'", userId);
        long unresolvedWrongNotes = countForUser("SELECT COUNT(*) FROM wrong_notes WHERE user_id = ? AND is_relearned = FALSE", userId);
        return new AdminUserLearningResponse(
                user.id(), user.email(), user.nickname(), subjects,
                planCount, completedPlanCount, solvedCount, correctCount, unresolvedWrongNotes
        );
    }

    @GetMapping("/subjects")
    public List<AdminSubjectResponse> subjects(HttpServletRequest request) {
        adminAccessService.require(request);
        return jdbcTemplate.query("""
                SELECT id, code, name, is_active FROM subjects ORDER BY id
                """, (rs, rowNum) -> new AdminSubjectResponse(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("is_active")
        ));
    }

    @PostMapping("/subjects")
    @Transactional
    public AdminSubjectResponse createSubject(
            @Valid @RequestBody AdminSubjectRequest body,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        String code = body.code().trim().toUpperCase(Locale.ROOT);
        jdbcTemplate.update("""
                INSERT INTO subjects (code, name, is_active, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))
                """, code, body.name().trim(), body.active());
        return jdbcTemplate.query("""
                SELECT id, code, name, is_active FROM subjects WHERE code = ?
                """, (rs, rowNum) -> new AdminSubjectResponse(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("is_active")
        ), code).stream().findFirst().orElseThrow();
    }

    @PutMapping("/subjects/{subjectId}")
    @Transactional
    public AdminSubjectResponse updateSubject(
            @PathVariable long subjectId,
            @Valid @RequestBody AdminSubjectRequest body,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        String code = body.code().trim().toUpperCase(Locale.ROOT);
        int updated = jdbcTemplate.update("""
                UPDATE subjects SET code = ?, name = ?, is_active = ? WHERE id = ?
                """, code, body.name().trim(), body.active(), subjectId);
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "과목을 찾을 수 없습니다.");
        return new AdminSubjectResponse(subjectId, code, body.name().trim(), body.active());
    }

    @PostMapping("/questions")
    @Transactional
    public AdminQuestionResponse createQuestion(
            @Valid @RequestBody AdminQuestionRequest body,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        if (body.options().stream().filter(AdminOptionRequest::correct).count() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "문제에는 정답 보기가 정확히 하나 필요합니다.");
        }
        List<Long> existing = jdbcTemplate.query("""
                SELECT id FROM diagnosis_questions
                 WHERE subject_id = ? AND question_no = ?
                """, (rs, rowNum) -> rs.getLong("id"), body.subjectId(), body.questionNo());
        if (!existing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "해당 과목의 문제 번호가 이미 존재합니다.");
        }
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO diagnosis_questions (
                        subject_id, question_no, difficulty, question_text, explanation,
                        is_active, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, body.subjectId());
            statement.setInt(2, body.questionNo());
            statement.setString(3, body.difficulty().trim().toUpperCase(Locale.ROOT));
            statement.setString(4, body.questionText().trim());
            statement.setString(5, body.explanation().trim());
            statement.setBoolean(6, body.active());
            return statement;
        }, keyHolder);
        Number generated = keyHolder.getKey();
        if (generated == null) throw new IllegalStateException("문제 번호를 생성하지 못했습니다.");
        long id = generated.longValue();
        for (int index = 0; index < body.options().size(); index++) {
            AdminOptionRequest option = body.options().get(index);
            jdbcTemplate.update("""
                    INSERT INTO question_options (question_id, option_no, option_text, is_correct)
                    VALUES (?, ?, ?, ?)
                    """, id, index + 1, option.text().trim(), option.correct());
        }
        return new AdminQuestionResponse(id, body.subjectId(), body.questionNo(), body.difficulty(), body.questionText(), body.active());
    }

    @GetMapping("/questions")
    public List<AdminQuestionResponse> questions(
            @RequestParam(defaultValue = "0") long subjectId,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        return jdbcTemplate.query("""
                SELECT id, subject_id, question_no, difficulty, question_text, is_active
                  FROM diagnosis_questions
                 WHERE (? = 0 OR subject_id = ?)
                 ORDER BY subject_id, question_no
                 LIMIT 200
                """, (rs, rowNum) -> new AdminQuestionResponse(
                rs.getLong("id"), rs.getLong("subject_id"), rs.getInt("question_no"),
                rs.getString("difficulty"), rs.getString("question_text"), rs.getBoolean("is_active")
        ), subjectId, subjectId);
    }

    @GetMapping("/questions/{questionId}")
    public AdminQuestionDetailResponse question(
            @PathVariable long questionId,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        return questionDetail(questionId);
    }

    @PutMapping("/questions/{questionId}")
    @Transactional
    public AdminQuestionDetailResponse updateQuestion(
            @PathVariable long questionId,
            @Valid @RequestBody AdminQuestionRequest body,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        if (body.options().stream().filter(AdminOptionRequest::correct).count() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "문제에는 정답 보기가 정확히 하나 필요합니다.");
        }
        AdminQuestionDetailResponse current = questionDetail(questionId);
        List<AdminOptionRequest> requestedOptions = body.options().stream()
                .map(option -> new AdminOptionRequest(option.text().trim(), option.correct()))
                .toList();
        Long answerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM diagnosis_answers WHERE question_id = ?",
                Long.class,
                questionId
        );
        boolean hasAnswerHistory = answerCount != null && answerCount > 0;
        if (hasAnswerHistory && (
                current.subjectId() != body.subjectId()
                        || !current.options().equals(requestedOptions)
        )) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "풀이 이력이 있는 문제는 과목과 보기를 변경할 수 없습니다. 새 문제로 등록해 주세요."
            );
        }
        int updated = jdbcTemplate.update("""
                UPDATE diagnosis_questions
                   SET subject_id = ?, question_no = ?, difficulty = ?, question_text = ?,
                       explanation = ?, is_active = ?, updated_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ?
                """, body.subjectId(), body.questionNo(), body.difficulty().trim().toUpperCase(Locale.ROOT),
                body.questionText().trim(), body.explanation().trim(), body.active(), questionId);
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "문제를 찾을 수 없습니다.");
        if (!hasAnswerHistory) {
            jdbcTemplate.update("DELETE FROM question_options WHERE question_id = ?", questionId);
            for (int index = 0; index < requestedOptions.size(); index++) {
                AdminOptionRequest option = requestedOptions.get(index);
                jdbcTemplate.update("""
                        INSERT INTO question_options (question_id, option_no, option_text, is_correct)
                        VALUES (?, ?, ?, ?)
                        """, questionId, index + 1, option.text(), option.correct());
            }
        }
        return questionDetail(questionId);
    }

    @PatchMapping("/questions/{questionId}/active")
    @Transactional
    public AdminQuestionResponse updateQuestionActive(
            @PathVariable long questionId,
            @Valid @RequestBody ActiveUpdateRequest body,
            HttpServletRequest request
    ) {
        adminAccessService.require(request);
        int updated = jdbcTemplate.update("""
                UPDATE diagnosis_questions
                   SET is_active = ?, updated_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ?
                """, body.active(), questionId);
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "문제를 찾을 수 없습니다.");
        return jdbcTemplate.query("""
                SELECT id, subject_id, question_no, difficulty, question_text, is_active
                  FROM diagnosis_questions WHERE id = ?
                """, (rs, rowNum) -> new AdminQuestionResponse(
                rs.getLong("id"), rs.getLong("subject_id"), rs.getInt("question_no"),
                rs.getString("difficulty"), rs.getString("question_text"), rs.getBoolean("is_active")
        ), questionId).stream().findFirst().orElseThrow();
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private AdminQuestionDetailResponse questionDetail(long questionId) {
        return jdbcTemplate.query("""
                SELECT id, subject_id, question_no, difficulty, question_text,
                       explanation, is_active
                  FROM diagnosis_questions WHERE id = ?
                """, (rs, rowNum) -> new AdminQuestionDetailResponse(
                rs.getLong("id"), rs.getLong("subject_id"), rs.getInt("question_no"),
                rs.getString("difficulty"), rs.getString("question_text"),
                rs.getString("explanation"), rs.getBoolean("is_active"),
                jdbcTemplate.query("""
                        SELECT option_text, is_correct FROM question_options
                         WHERE question_id = ? ORDER BY option_no
                        """, (optionRs, optionRow) -> new AdminOptionRequest(
                        optionRs.getString("option_text"), optionRs.getBoolean("is_correct")
                ), questionId)
        ), questionId).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "문제를 찾을 수 없습니다."
        ));
    }

    private long countForUser(String sql, long userId) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, userId);
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

    public record AdminUserPageResponse(List<AdminUserResponse> content, long totalElements, int page, int size) {}

    public record AdminSubjectStatisticsResponse(
            long subjectId, String subjectCode, String subjectName,
            long learnerCount, long attemptCount, long solvedCount, long correctCount
    ) {}

    public record AdminUserLearningResponse(
            long userId, String email, String nickname, List<String> subjects,
            long planCount, long completedPlanCount, long solvedCount,
            long correctCount, long unresolvedWrongNotes
    ) {}

    public record AdminSubjectResponse(long id, String code, String name, boolean active) {}

    public record AdminSubjectRequest(@NotBlank String code, @NotBlank String name, boolean active) {}

    public record AdminOptionRequest(@NotBlank String text, boolean correct) {}

    public record AdminQuestionRequest(
            @Min(1) long subjectId,
            @Min(1) int questionNo,
            @NotBlank String difficulty,
            @NotBlank String questionText,
            @NotBlank String explanation,
            boolean active,
            @NotNull @Size(min = 2, max = 5) List<@Valid AdminOptionRequest> options
    ) {}

    public record AdminQuestionResponse(
            long id, long subjectId, int questionNo, String difficulty, String questionText, boolean active
    ) {}

    public record AdminQuestionDetailResponse(
            long id, long subjectId, int questionNo, String difficulty,
            String questionText, String explanation, boolean active,
            List<AdminOptionRequest> options
    ) {}

    public record ActiveUpdateRequest(boolean active) {}
}
