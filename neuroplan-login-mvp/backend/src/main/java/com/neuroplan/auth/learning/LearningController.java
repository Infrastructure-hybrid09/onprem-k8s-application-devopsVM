package com.neuroplan.auth.learning;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.neuroplan.auth.auth.CurrentUserService;
import com.neuroplan.auth.error.ApiException;
import com.neuroplan.auth.user.UserRecord;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
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
@RequestMapping("/api/learning")
public class LearningController {
    private static final int PLAN_STEP_COUNT = 3;
    private static final int DIAGNOSIS_QUESTION_COUNT = 5;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public LearningController(JdbcTemplate jdbcTemplate, CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/subjects")
    public List<SubjectResponse> subjects() {
        return jdbcTemplate.query("""
                SELECT id, code, name
                  FROM subjects
                 WHERE is_active = TRUE
                 ORDER BY id
                """, (rs, rowNum) -> new SubjectResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name")
        ));
    }

    @GetMapping("/state")
    public LearningStateResponse state(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        List<ProfileSubjectResponse> profile = profileFor(user.id());
        List<PlanResponse> plans = todayPlans(user.id(), profile);
        PlanResponse plan = plans.isEmpty() ? null : plans.getFirst();
        TodayStatsResponse stats = todayStats(user.id());
        DiagnosisSummaryResponse diagnosis = profile.isEmpty()
                ? null
                : latestDiagnosis(user.id());
        return new LearningStateResponse(profile, plan, plans, stats, diagnosis);
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(
            @RequestParam(defaultValue = "28") int days,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        if (days < 7 || days > 90) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "대시보드 조회 기간은 7일 이상 90일 이하여야 합니다.");
        }
        return new DashboardResponse(
                weeklySummary(user.id()),
                studyDailyStats(user.id(), days),
                subjectAccuracy(user.id()),
                learningStreak(user.id()),
                unresolvedWrongNoteCount(user.id())
        );
    }

    @GetMapping("/wrong-notes")
    public List<WrongNoteResponse> wrongNotes(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        return wrongNotesFor(user.id());
    }

    @PatchMapping("/wrong-notes/{questionId}/relearn")
    @Transactional
    public WrongNoteResponse markWrongNoteRelearned(
            @PathVariable long questionId,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        int changed = jdbcTemplate.update("""
                UPDATE wrong_notes
                   SET is_relearned = TRUE,
                       relearned_at = CURRENT_TIMESTAMP(6)
                 WHERE user_id = ? AND question_id = ?
                """, user.id(), questionId);
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "오답 노트를 찾을 수 없습니다.");
        }
        return wrongNotesFor(user.id()).stream()
                .filter(note -> note.questionId() == questionId)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "오답 노트를 찾을 수 없습니다."));
    }

    @PutMapping("/profile")
    @Transactional
    public List<ProfileSubjectResponse> updateProfile(
            @Valid @RequestBody ProfileUpdateRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        if (body.subjects() == null || body.subjects().isEmpty() || body.subjects().size() > 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "학습 과목은 1개 이상 3개 이하로 선택해 주세요.");
        }

        Set<String> uniqueCodes = new HashSet<>();
        List<ResolvedSelection> selections = new ArrayList<>();
        for (SubjectSelectionRequest selection : body.subjects()) {
            String code = selection.code().trim().toUpperCase(Locale.ROOT);
            if (!uniqueCodes.add(code)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "같은 과목을 중복해서 선택할 수 없습니다.");
            }
            LearningLevel level = parseLevel(selection.learningLevel());
            SubjectResponse subject = jdbcTemplate.query("""
                    SELECT id, code, name
                      FROM subjects
                     WHERE code = ?
                       AND is_active = TRUE
                    """, (rs, rowNum) -> new SubjectResponse(
                    rs.getLong("id"), rs.getString("code"), rs.getString("name")
            ), code).stream().findFirst().orElseThrow(() -> new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "사용할 수 없는 과목입니다: " + code
            ));
            selections.add(new ResolvedSelection(subject, level));
        }

        jdbcTemplate.update("DELETE FROM user_subjects WHERE user_id = ?", user.id());
        for (int index = 0; index < selections.size(); index++) {
            ResolvedSelection selection = selections.get(index);
            jdbcTemplate.update("""
                    INSERT INTO user_subjects (
                        user_id, slot_no, subject_id, learning_level, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """,
                    user.id(),
                    index + 1,
                    selection.subject().id(),
                    selection.level().name()
            );
        }
        return profileFor(user.id());
    }

    @PostMapping("/plans")
    @Transactional
    public PlanResponse createOrReplacePlan(
            @RequestParam(required = false) String subjectCode,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        List<ProfileSubjectResponse> profile = profileFor(user.id());
        if (profile.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "과목과 수준을 먼저 설정해 주세요.");
        }
        ProfileSubjectResponse focus = subjectCode == null || subjectCode.isBlank()
                ? profile.getFirst()
                : profile.stream()
                    .filter(item -> item.subjectCode().equalsIgnoreCase(subjectCode.trim()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.BAD_REQUEST,
                            "학습 프로필에 선택한 과목이 없습니다: " + subjectCode
                    ));
        String title = focus.subjectName() + " " + focus.levelLabel() + " 오늘의 학습 플랜";
        Long planId = findTodayPlanId(user.id(), focus.subjectId());
        if (planId == null) {
            try {
                planId = insertAndReturnId("""
                        INSERT INTO daily_plans (
                            user_id, subject_id, plan_date, title, plan_status, created_at, updated_at
                        ) VALUES (?, ?, CURRENT_DATE, ?, 'READY', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                        """, user.id(), focus.subjectId(), title);
            } catch (DuplicateKeyException exception) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "과목별 플랜을 저장할 수 없습니다. daily_plans UNIQUE 키에 user_id, subject_id, plan_date가 포함됐는지 확인해 주세요."
                );
            }
        } else {
            jdbcTemplate.update("""
                    UPDATE daily_plans
                       SET subject_id = ?, title = ?, plan_status = 'READY',
                           updated_at = CURRENT_TIMESTAMP(6)
                     WHERE id = ? AND user_id = ?
                    """, focus.subjectId(), title, planId, user.id());
            jdbcTemplate.update("DELETE FROM plan_steps WHERE plan_id = ?", planId);
        }
        jdbcTemplate.update("DELETE FROM daily_plan_ai_meta WHERE plan_id = ?", planId);

        insertPlanStep(planId, 1, focus.subjectName() + " 핵심 개념 익히기",
                focus.levelLabel() + " 수준의 필수 개념과 용어를 정리합니다.");
        insertPlanStep(planId, 2, focus.subjectName() + " 미니 실습 따라하기",
                "핵심 명령과 동작 흐름을 작은 실습으로 확인합니다.");
        insertPlanStep(planId, 3, focus.subjectName() + " 핵심 내용 복습",
                "오늘 학습한 내용을 요약하고 진단 문제를 준비합니다.");
        syncCompletedStepStats(user.id());
        return todayPlan(user.id(), focus.subjectId());
    }

    @GetMapping("/plans/history")
    public List<PlanHistoryResponse> planHistory(
            @RequestParam(defaultValue = "30") int days,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        if (days < 1 || days > 365) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "플랜 조회 기간은 1일 이상 365일 이하여야 합니다.");
        }
        return jdbcTemplate.query("""
                SELECT dp.id, dp.plan_date, dp.title, dp.plan_status,
                       s.code AS subject_code, s.name AS subject_name,
                       SUM(ps.step_status = 'COMPLETED') AS completed_steps,
                       COUNT(ps.id) AS total_steps
                  FROM daily_plans dp
                  JOIN subjects s ON s.id = dp.subject_id
             LEFT JOIN plan_steps ps ON ps.plan_id = dp.id
                 WHERE dp.user_id = ?
                   AND dp.plan_date >= DATE_SUB(CURRENT_DATE, INTERVAL ? DAY)
                 GROUP BY dp.id, dp.plan_date, dp.title, dp.plan_status, s.code, s.name
                 ORDER BY dp.plan_date DESC, dp.id DESC
                """, (rs, rowNum) -> new PlanHistoryResponse(
                rs.getLong("id"),
                rs.getDate("plan_date").toLocalDate(),
                rs.getString("title"),
                rs.getString("plan_status"),
                rs.getString("subject_code"),
                rs.getString("subject_name"),
                rs.getInt("completed_steps"),
                rs.getInt("total_steps")
        ), user.id(), days - 1);
    }

    @PatchMapping("/plans/{planId}/steps/{stepNo}")
    @Transactional
    public PlanResponse updateStep(
            @PathVariable long planId,
            @PathVariable int stepNo,
            @Valid @RequestBody StepUpdateRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        if (stepNo < 1 || stepNo > PLAN_STEP_COUNT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "학습 단계 번호가 올바르지 않습니다.");
        }
        PlanOwner owner = planOwner(planId, user.id());
        int updated = jdbcTemplate.update("""
                UPDATE plan_steps ps
                JOIN daily_plans dp ON dp.id = ps.plan_id
                   SET ps.step_status = ?,
                       ps.completed_at = ?,
                       ps.updated_at = CURRENT_TIMESTAMP(6)
                 WHERE ps.plan_id = ?
                   AND ps.step_no = ?
                   AND dp.user_id = ?
                """,
                body.completed() ? "COMPLETED" : "PENDING",
                body.completed() ? Timestamp.valueOf(LocalDateTime.now()) : null,
                planId,
                stepNo,
                user.id()
        );
        if (updated != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "학습 단계를 찾을 수 없습니다.");
        }

        Integer completed = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM plan_steps
                 WHERE plan_id = ? AND step_status = 'COMPLETED'
                """, Integer.class, planId);
        String status = completed != null && completed == PLAN_STEP_COUNT
                ? "COMPLETED"
                : completed != null && completed > 0 ? "IN_PROGRESS" : "READY";
        jdbcTemplate.update("""
                UPDATE daily_plans
                   SET plan_status = ?, updated_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ? AND user_id = ?
                """, status, planId, user.id());
        syncCompletedStepStats(user.id());
        return todayPlan(user.id(), owner.subjectId());
    }

    @GetMapping("/diagnosis/questions")
    public List<QuestionResponse> questions(
            @RequestParam String subjectCode,
            HttpServletRequest request
    ) {
        currentUserService.require(request);
        String code = subjectCode.trim().toUpperCase(Locale.ROOT);
        List<QuestionResponse> result = jdbcTemplate.query("""
                SELECT q.id, q.question_no, q.difficulty, q.question_text, s.code, s.name
                  FROM diagnosis_questions q
                  JOIN subjects s ON s.id = q.subject_id
                  JOIN (
                    SELECT question_id,
                           COUNT(*) AS option_count,
                           SUM(CASE WHEN is_correct = TRUE THEN 1 ELSE 0 END) AS correct_count
                      FROM question_options
                     GROUP BY question_id
                  ) option_stats ON option_stats.question_id = q.id
                 WHERE s.code = ?
                   AND s.is_active = TRUE
                   AND q.is_active = TRUE
                   AND option_stats.option_count >= 2
                   AND option_stats.correct_count = 1
                 ORDER BY q.question_no
                 LIMIT ?
                """, (rs, rowNum) -> {
            long questionId = rs.getLong("id");
            List<OptionResponse> options = jdbcTemplate.query("""
                    SELECT id, option_no, option_text
                      FROM question_options
                     WHERE question_id = ?
                     ORDER BY option_no
                    """, (optionRs, optionRow) -> new OptionResponse(
                    optionRs.getLong("id"),
                    optionRs.getInt("option_no"),
                    optionRs.getString("option_text")
            ), questionId);
            return new QuestionResponse(
                    questionId,
                    rs.getInt("question_no"),
                    rs.getString("difficulty"),
                    rs.getString("question_text"),
                    rs.getString("code"),
                    rs.getString("name"),
                    options
            );
        }, code, DIAGNOSIS_QUESTION_COUNT);
        if (result.size() < DIAGNOSIS_QUESTION_COUNT) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    code + " 과목에 활성 진단 문제가 5개 이상 필요합니다. 현재 " + result.size() + "개입니다."
            );
        }
        return result;
    }

    @PostMapping("/diagnosis/check")
    public AnswerCheckResponse checkAnswer(
            @Valid @RequestBody AnswerCheckRequest body,
            HttpServletRequest request
    ) {
        currentUserService.require(request);
        return answerCheck(body.questionId(), body.selectedOptionId());
    }

    @PostMapping("/diagnosis/attempts")
    @Transactional
    public AttemptResponse saveAttempt(
            @Valid @RequestBody AttemptRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        if (body.answers() == null || body.answers().size() < 5 || body.answers().size() > 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "진단 답안은 5개 이상 10개 이하로 제출해 주세요.");
        }
        String code = body.subjectCode().trim().toUpperCase(Locale.ROOT);
        SubjectResponse subject = findActiveSubject(code);
        Set<Long> questionIds = new HashSet<>();
        List<ResolvedAnswer> resolved = new ArrayList<>();
        int correctCount = 0;
        for (AttemptAnswerRequest answer : body.answers()) {
            if (!questionIds.add(answer.questionId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "같은 문제의 답안을 중복 제출할 수 없습니다.");
            }
            AnswerCheckResponse checked = answerCheckForSubject(
                    answer.questionId(), answer.selectedOptionId(), subject.id()
            );
            if (checked.correct()) correctCount++;
            resolved.add(new ResolvedAnswer(answer, checked));
        }

        LocalDateTime completedAt = LocalDateTime.now();
        long attemptId = insertAndReturnId("""
                INSERT INTO diagnosis_attempts (
                    user_id, subject_id, attempt_type, attempt_status,
                    total_questions, correct_answers, started_at, completed_at
                ) VALUES (?, ?, 'DIAGNOSTIC', 'COMPLETED', ?, ?, ?, ?)
                """,
                user.id(), subject.id(), resolved.size(), correctCount,
                Timestamp.valueOf(completedAt.minusSeconds(1)),
                Timestamp.valueOf(completedAt)
        );

        for (ResolvedAnswer answer : resolved) {
            jdbcTemplate.update("""
                    INSERT INTO diagnosis_answers (
                        attempt_id, question_id, selected_option_id, is_correct, answered_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    attemptId,
                    answer.request().questionId(),
                    answer.request().selectedOptionId(),
                    answer.checked().correct(),
                    Timestamp.valueOf(completedAt)
            );
            if (!answer.checked().correct()) {
                jdbcTemplate.update("""
                        INSERT INTO wrong_notes (
                            user_id, question_id, last_attempt_id, wrong_count,
                            is_relearned, first_wrong_at, last_wrong_at, relearned_at
                        ) VALUES (?, ?, ?, 1, FALSE, ?, ?, NULL)
                        ON DUPLICATE KEY UPDATE
                            last_attempt_id = VALUES(last_attempt_id),
                            wrong_count = wrong_count + 1,
                            is_relearned = FALSE,
                            last_wrong_at = VALUES(last_wrong_at),
                            relearned_at = NULL
                        """,
                        user.id(),
                        answer.request().questionId(),
                        attemptId,
                        Timestamp.valueOf(completedAt),
                        Timestamp.valueOf(completedAt)
                );
            }
        }
        addDiagnosisStats(user.id(), resolved.size(), correctCount);
        List<AttemptResultResponse> results = resolved.stream()
                .map(item -> new AttemptResultResponse(
                        item.request().questionId(),
                        item.request().selectedOptionId(),
                        item.checked().correct(),
                        item.checked().correctOptionId(),
                        item.checked().explanation()
                ))
                .toList();
        return new AttemptResponse(attemptId, resolved.size(), correctCount, results);
    }

    private List<ProfileSubjectResponse> profileFor(long userId) {
        return jdbcTemplate.query("""
                SELECT us.slot_no, us.subject_id, us.learning_level, s.code, s.name
                  FROM user_subjects us
                  JOIN subjects s ON s.id = us.subject_id
                 WHERE us.user_id = ?
                 ORDER BY us.slot_no
                """, (rs, rowNum) -> {
            LearningLevel level = parseLevel(rs.getString("learning_level"));
            return new ProfileSubjectResponse(
                    rs.getInt("slot_no"),
                    rs.getLong("subject_id"),
                    rs.getString("code"),
                    rs.getString("name"),
                    level.name(),
                    level.label
            );
        }, userId);
    }

    private PlanResponse todayPlan(long userId, long subjectId) {
        List<PlanRow> rows = jdbcTemplate.query("""
                SELECT dp.id, dp.title, dp.plan_status, dp.subject_id, s.code, s.name
                  FROM daily_plans dp
                  JOIN subjects s ON s.id = dp.subject_id
                 WHERE dp.user_id = ?
                   AND dp.subject_id = ?
                   AND dp.plan_date = CURRENT_DATE
                 ORDER BY dp.id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new PlanRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("plan_status"),
                rs.getLong("subject_id"),
                rs.getString("code"),
                rs.getString("name")
        ), userId, subjectId);
        if (rows.isEmpty()) return null;
        PlanRow plan = rows.getFirst();
        List<PlanStepResponse> steps = jdbcTemplate.query("""
                SELECT id, step_no, title, content, step_status, completed_at
                  FROM plan_steps
                 WHERE plan_id = ?
                 ORDER BY step_no
                """, (rs, rowNum) -> new PlanStepResponse(
                rs.getLong("id"),
                rs.getInt("step_no"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("step_status"),
                rs.getTimestamp("completed_at") == null
                        ? null : rs.getTimestamp("completed_at").toLocalDateTime()
        ), plan.id());
        return new PlanResponse(
                plan.id(), plan.title(), plan.status(), plan.subjectId(),
                plan.subjectCode(), plan.subjectName(), steps
        );
    }

    private List<PlanResponse> todayPlans(long userId, List<ProfileSubjectResponse> profile) {
        return profile.stream()
                .map(item -> todayPlan(userId, item.subjectId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private TodayStatsResponse todayStats(long userId) {
        return jdbcTemplate.query("""
                SELECT solved_count, correct_count, completed_step_count
                  FROM study_daily_stats
                 WHERE user_id = ? AND study_date = CURRENT_DATE
                """, (rs, rowNum) -> new TodayStatsResponse(
                rs.getInt("solved_count"),
                rs.getInt("correct_count"),
                rs.getInt("completed_step_count")
        ), userId).stream().findFirst().orElse(new TodayStatsResponse(0, 0, 0));
    }

    private WeeklySummaryResponse weeklySummary(long userId) {
        return jdbcTemplate.query("""
                SELECT COALESCE(SUM(solved_count), 0) AS solved_count,
                       COALESCE(SUM(correct_count), 0) AS correct_count,
                       COALESCE(SUM(completed_step_count), 0) AS completed_step_count
                  FROM study_daily_stats
                 WHERE user_id = ?
                   AND study_date BETWEEN DATE_SUB(CURRENT_DATE, INTERVAL 6 DAY) AND CURRENT_DATE
                """, (rs, rowNum) -> new WeeklySummaryResponse(
                rs.getInt("solved_count"),
                rs.getInt("correct_count"),
                rs.getInt("completed_step_count")
        ), userId).stream().findFirst().orElse(new WeeklySummaryResponse(0, 0, 0));
    }

    private List<DailyStatResponse> studyDailyStats(long userId, int days) {
        LocalDate startDate = LocalDate.now().minusDays(days - 1L);
        return jdbcTemplate.query("""
                SELECT study_date, solved_count, correct_count, completed_step_count
                  FROM study_daily_stats
                 WHERE user_id = ? AND study_date >= ?
                 ORDER BY study_date
                """, (rs, rowNum) -> new DailyStatResponse(
                rs.getDate("study_date").toLocalDate(),
                rs.getInt("solved_count"),
                rs.getInt("correct_count"),
                rs.getInt("completed_step_count")
        ), userId, startDate);
    }

    private List<SubjectAccuracyResponse> subjectAccuracy(long userId) {
        return jdbcTemplate.query("""
                SELECT s.code, s.name, us.learning_level,
                       COALESCE(diag.attempt_count, 0) AS attempt_count,
                       COALESCE(diag.solved_count, 0) AS solved_count,
                       COALESCE(diag.correct_count, 0) AS correct_count,
                       COALESCE(plan.completed_steps, 0) AS completed_steps,
                       COALESCE(plan.total_steps, 0) AS total_steps
                  FROM user_subjects us
                  JOIN subjects s ON s.id = us.subject_id
             LEFT JOIN (
                       SELECT subject_id, COUNT(*) AS attempt_count,
                              SUM(total_questions) AS solved_count,
                              SUM(correct_answers) AS correct_count
                         FROM diagnosis_attempts
                        WHERE user_id = ? AND attempt_status = 'COMPLETED'
                        GROUP BY subject_id
                       ) diag ON diag.subject_id = s.id
             LEFT JOIN (
                       SELECT dp.subject_id,
                              SUM(ps.step_status = 'COMPLETED') AS completed_steps,
                              COUNT(ps.id) AS total_steps
                         FROM daily_plans dp
                    LEFT JOIN plan_steps ps ON ps.plan_id = dp.id
                        WHERE dp.user_id = ?
                          AND dp.plan_date BETWEEN DATE_SUB(CURRENT_DATE, INTERVAL 6 DAY) AND CURRENT_DATE
                        GROUP BY dp.subject_id
                       ) plan ON plan.subject_id = s.id
                 WHERE us.user_id = ?
                 ORDER BY us.slot_no
                """, (rs, rowNum) -> new SubjectAccuracyResponse(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("learning_level"),
                rs.getInt("attempt_count"),
                rs.getInt("solved_count"),
                rs.getInt("correct_count"),
                rs.getInt("completed_steps"),
                rs.getInt("total_steps")
        ), userId, userId, userId);
    }

    private int learningStreak(long userId) {
        List<LocalDate> activeDates = jdbcTemplate.query("""
                SELECT study_date
                  FROM study_daily_stats
                 WHERE user_id = ?
                   AND (solved_count > 0 OR completed_step_count > 0)
                   AND study_date <= CURRENT_DATE
                 ORDER BY study_date DESC
                """, (rs, rowNum) -> rs.getDate("study_date").toLocalDate(), userId);
        int streak = 0;
        LocalDate expected = LocalDate.now();
        for (LocalDate date : activeDates) {
            if (!date.equals(expected)) break;
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    private int unresolvedWrongNoteCount(long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wrong_notes
                 WHERE user_id = ? AND is_relearned = FALSE
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    private List<WrongNoteResponse> wrongNotesFor(long userId) {
        return jdbcTemplate.query("""
                SELECT wn.question_id, s.code AS subject_code, s.name AS subject_name,
                       q.question_text, q.explanation,
                       wn.wrong_count, wn.is_relearned, wn.last_wrong_at, wn.relearned_at,
                       selected.option_text AS selected_option_text,
                       correct.option_text AS correct_option_text
                  FROM wrong_notes wn
                  JOIN diagnosis_questions q ON q.id = wn.question_id
                  JOIN subjects s ON s.id = q.subject_id
             LEFT JOIN diagnosis_answers da
                    ON da.attempt_id = wn.last_attempt_id AND da.question_id = wn.question_id
             LEFT JOIN question_options selected ON selected.id = da.selected_option_id
                  JOIN question_options correct
                    ON correct.question_id = q.id AND correct.is_correct = TRUE
                 WHERE wn.user_id = ?
                 ORDER BY wn.is_relearned ASC, wn.last_wrong_at DESC, wn.question_id
                """, (rs, rowNum) -> new WrongNoteResponse(
                rs.getLong("question_id"),
                rs.getString("subject_code"),
                rs.getString("subject_name"),
                rs.getString("question_text"),
                rs.getString("explanation"),
                rs.getString("selected_option_text"),
                rs.getString("correct_option_text"),
                rs.getInt("wrong_count"),
                rs.getBoolean("is_relearned"),
                rs.getTimestamp("last_wrong_at").toLocalDateTime(),
                rs.getTimestamp("relearned_at") == null ? null : rs.getTimestamp("relearned_at").toLocalDateTime()
        ), userId);
    }

    private DiagnosisSummaryResponse latestDiagnosis(long userId) {
        return jdbcTemplate.query("""
                SELECT id, total_questions, correct_answers, completed_at
                 FROM diagnosis_attempts
                 WHERE user_id = ?
                   AND attempt_status = 'COMPLETED'
                   AND DATE(completed_at) = CURRENT_DATE
                 ORDER BY completed_at DESC, id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new DiagnosisSummaryResponse(
                rs.getLong("id"),
                rs.getInt("total_questions"),
                rs.getInt("correct_answers"),
                rs.getTimestamp("completed_at").toLocalDateTime()
        ), userId).stream().findFirst().orElse(null);
    }

    private SubjectResponse findActiveSubject(String code) {
        return jdbcTemplate.query("""
                SELECT id, code, name
                  FROM subjects
                 WHERE code = ? AND is_active = TRUE
                """, (rs, rowNum) -> new SubjectResponse(
                rs.getLong("id"), rs.getString("code"), rs.getString("name")
        ), code).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "사용할 수 없는 과목입니다: " + code
        ));
    }

    private Long findTodayPlanId(long userId, long subjectId) {
        return jdbcTemplate.query("""
                SELECT id
                  FROM daily_plans
                 WHERE user_id = ? AND subject_id = ? AND plan_date = CURRENT_DATE
                 ORDER BY id DESC
                 LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"), userId, subjectId)
                .stream().findFirst().orElse(null);
    }

    private PlanOwner planOwner(long planId, long userId) {
        return jdbcTemplate.query("""
                SELECT id, subject_id
                  FROM daily_plans
                 WHERE id = ? AND user_id = ?
                """, (rs, rowNum) -> new PlanOwner(
                rs.getLong("id"), rs.getLong("subject_id")
        ), planId, userId).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "오늘의 학습 플랜을 찾을 수 없습니다."
        ));
    }

    private void insertPlanStep(long planId, int stepNo, String title, String content) {
        jdbcTemplate.update("""
                INSERT INTO plan_steps (
                    plan_id, step_no, title, content, step_status,
                    completed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, planId, stepNo, title, content);
    }

    private AnswerCheckResponse answerCheck(long questionId, long selectedOptionId) {
        return jdbcTemplate.query("""
                SELECT selected.is_correct,
                       q.explanation,
                       correct_option.id AS correct_option_id
                  FROM diagnosis_questions q
                  JOIN question_options selected
                    ON selected.question_id = q.id AND selected.id = ?
                  JOIN question_options correct_option
                    ON correct_option.question_id = q.id AND correct_option.is_correct = TRUE
                 WHERE q.id = ? AND q.is_active = TRUE
                """, (rs, rowNum) -> new AnswerCheckResponse(
                rs.getBoolean("is_correct"),
                rs.getLong("correct_option_id"),
                rs.getString("explanation")
        ), selectedOptionId, questionId).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "문제 또는 선택한 보기를 확인해 주세요."
        ));
    }

    private AnswerCheckResponse answerCheckForSubject(
            long questionId,
            long selectedOptionId,
            long subjectId
    ) {
        return jdbcTemplate.query("""
                SELECT selected.is_correct,
                       q.explanation,
                       correct_option.id AS correct_option_id
                  FROM diagnosis_questions q
                  JOIN question_options selected
                    ON selected.question_id = q.id AND selected.id = ?
                  JOIN question_options correct_option
                    ON correct_option.question_id = q.id AND correct_option.is_correct = TRUE
                 WHERE q.id = ?
                   AND q.subject_id = ?
                   AND q.is_active = TRUE
                """, (rs, rowNum) -> new AnswerCheckResponse(
                rs.getBoolean("is_correct"),
                rs.getLong("correct_option_id"),
                rs.getString("explanation")
        ), selectedOptionId, questionId, subjectId).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST,
                "선택한 과목의 문제와 보기가 일치하지 않습니다."
        ));
    }

    private void syncCompletedStepStats(long userId) {
        Integer completed = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM plan_steps ps
                  JOIN daily_plans dp ON dp.id = ps.plan_id
                 WHERE dp.user_id = ?
                   AND dp.plan_date = CURRENT_DATE
                   AND ps.step_status = 'COMPLETED'
                """, Integer.class, userId);
        jdbcTemplate.update("""
                INSERT INTO study_daily_stats (
                    user_id, study_date, solved_count, correct_count,
                    completed_step_count, updated_at
                ) VALUES (?, CURRENT_DATE, 0, 0, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    completed_step_count = VALUES(completed_step_count),
                    updated_at = CURRENT_TIMESTAMP(6)
                """, userId, completed == null ? 0 : completed);
    }

    private void addDiagnosisStats(long userId, int solvedCount, int correctCount) {
        jdbcTemplate.update("""
                INSERT INTO study_daily_stats (
                    user_id, study_date, solved_count, correct_count,
                    completed_step_count, updated_at
                ) VALUES (?, CURRENT_DATE, ?, ?, 0, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    solved_count = solved_count + VALUES(solved_count),
                    correct_count = correct_count + VALUES(correct_count),
                    updated_at = CURRENT_TIMESTAMP(6)
                """, userId, solvedCount, correctCount);
    }

    private long insertAndReturnId(String sql, Object... parameters) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement;
        }, keyHolder);
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("DB에서 생성 번호를 반환하지 않았습니다.");
        }
        return keys.values().stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("생성 번호를 확인할 수 없습니다."))
                .longValue();
    }

    private LearningLevel parseLevel(String value) {
        try {
            return LearningLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "학습 수준은 BEGINNER, INTERMEDIATE, ADVANCED 중 하나여야 합니다."
            );
        }
    }

    private enum LearningLevel {
        BEGINNER("초급"),
        INTERMEDIATE("중급"),
        ADVANCED("고급");

        private final String label;

        LearningLevel(String label) {
            this.label = label;
        }
    }

    public record SubjectResponse(long id, String code, String name) {}

    public record ProfileSubjectResponse(
            int slotNo,
            long subjectId,
            String subjectCode,
            String subjectName,
            String learningLevel,
            String levelLabel
    ) {}

    public record ProfileUpdateRequest(
            @NotNull @Size(min = 1, max = 3) List<@Valid SubjectSelectionRequest> subjects
    ) {}

    public record SubjectSelectionRequest(
            @NotBlank String code,
            @NotBlank String learningLevel
    ) {}

    public record LearningStateResponse(
            List<ProfileSubjectResponse> profile,
            PlanResponse plan,
            List<PlanResponse> plans,
            TodayStatsResponse stats,
            DiagnosisSummaryResponse diagnosis
    ) {}

    public record PlanResponse(
            long id,
            String title,
            String status,
            long subjectId,
            String subjectCode,
            String subjectName,
            List<PlanStepResponse> steps
    ) {}

    public record PlanStepResponse(
            long id,
            int stepNo,
            String title,
            String content,
            String status,
            LocalDateTime completedAt
    ) {}

    public record PlanHistoryResponse(
            long id,
            LocalDate planDate,
            String title,
            String status,
            String subjectCode,
            String subjectName,
            int completedSteps,
            int totalSteps
    ) {}

    public record StepUpdateRequest(boolean completed) {}

    public record TodayStatsResponse(int solvedCount, int correctCount, int completedStepCount) {}

    public record DashboardResponse(
            WeeklySummaryResponse weekly,
            List<DailyStatResponse> dailyStats,
            List<SubjectAccuracyResponse> subjectStats,
            int streakDays,
            int unresolvedWrongNotes
    ) {}

    public record WeeklySummaryResponse(int solvedCount, int correctCount, int completedStepCount) {}

    public record DailyStatResponse(
            LocalDate studyDate,
            int solvedCount,
            int correctCount,
            int completedStepCount
    ) {}

    public record SubjectAccuracyResponse(
            String subjectCode,
            String subjectName,
            String learningLevel,
            int attemptCount,
            int solvedCount,
            int correctCount,
            int completedSteps,
            int totalSteps
    ) {}

    public record WrongNoteResponse(
            long questionId,
            String subjectCode,
            String subjectName,
            String questionText,
            String explanation,
            String selectedOptionText,
            String correctOptionText,
            int wrongCount,
            boolean relearned,
            LocalDateTime lastWrongAt,
            LocalDateTime relearnedAt
    ) {}

    public record DiagnosisSummaryResponse(
            long attemptId,
            int totalQuestions,
            int correctAnswers,
            LocalDateTime completedAt
    ) {}

    public record QuestionResponse(
            long id,
            int questionNo,
            String difficulty,
            String text,
            String subjectCode,
            String subjectName,
            List<OptionResponse> options
    ) {}

    public record OptionResponse(long id, int optionNo, String text) {}

    public record AnswerCheckRequest(long questionId, long selectedOptionId) {}

    public record AnswerCheckResponse(boolean correct, long correctOptionId, String explanation) {}

    public record AttemptRequest(
            @NotBlank String subjectCode,
            @NotNull @Size(min = 5, max = 10) List<@Valid AttemptAnswerRequest> answers
    ) {}

    public record AttemptAnswerRequest(long questionId, long selectedOptionId) {}

    public record AttemptResponse(
            long attemptId,
            int totalQuestions,
            int correctAnswers,
            List<AttemptResultResponse> results
    ) {}

    public record AttemptResultResponse(
            long questionId,
            long selectedOptionId,
            boolean correct,
            long correctOptionId,
            String explanation
    ) {}

    private record ResolvedSelection(SubjectResponse subject, LearningLevel level) {}
    private record PlanOwner(long planId, long subjectId) {}
    private record PlanRow(
            long id,
            String title,
            String status,
            long subjectId,
            String subjectCode,
            String subjectName
    ) {}
    private record ResolvedAnswer(AttemptAnswerRequest request, AnswerCheckResponse checked) {}
}
