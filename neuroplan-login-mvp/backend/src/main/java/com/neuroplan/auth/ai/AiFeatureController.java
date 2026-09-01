package com.neuroplan.auth.ai;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuroplan.auth.ai.AiGenerationService.FeedbackGeneration;
import com.neuroplan.auth.ai.AiGenerationService.PlanGeneration;
import com.neuroplan.auth.ai.AiGenerationService.QuizCheckResult;
import com.neuroplan.auth.ai.AiGenerationService.QuizGeneration;
import com.neuroplan.auth.ai.AiGenerationService.RecommendationContext;
import com.neuroplan.auth.ai.AiGenerationService.RecommendationGeneration;
import com.neuroplan.auth.ai.AiGenerationService.WrongNoteContext;
import com.neuroplan.auth.ai.AiQuotaService.AiQuotaResponse;
import com.neuroplan.auth.auth.CurrentUserService;
import com.neuroplan.auth.error.ApiException;
import com.neuroplan.auth.user.UserRecord;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@ResponseBody
@RequestMapping("/api/ai")
public class AiFeatureController {
    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final AiQuotaService quotaService;
    private final AiPreferencesService preferencesService;
    private final AiGenerationService generationService;
    private final ObjectMapper objectMapper;

    public AiFeatureController(JdbcTemplate jdbcTemplate, CurrentUserService currentUserService,
                               AiQuotaService quotaService, AiPreferencesService preferencesService,
                               AiGenerationService generationService,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.quotaService = quotaService;
        this.preferencesService = preferencesService;
        this.generationService = generationService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/quota")
    public AiQuotaResponse quota(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        return quotaService.status(user.id());
    }

    @GetMapping("/preferences")
    public AiPreferencesService.AiPreferenceResponse preferences(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        return preferencesService.status(user.id());
    }

    @PutMapping("/preferences")
    public AiPreferencesService.AiPreferenceResponse updatePreferences(
            @RequestBody AiPreferenceRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        return preferencesService.update(user.id(), body.enabled(), body.consent(),
                body.explanationStyle(), body.availableMinutes());
    }

    @PostMapping("/plans")
    @Transactional
    public AiPlanResponse generatePlan(@RequestParam String subjectCode, HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        ProfileSubject focus = profileSubject(user.id(), subjectCode);
        PlanGeneration generated = generationService.generatePlan(
                user.id(), focus.subjectId(), focus.subjectName(), focus.levelLabel());
        long planId = savePlan(user.id(), focus, generated);
        return new AiPlanResponse(plan(planId, user.id()), generated.generationRunId(),
                generated.fallback(), generated.content().rationale(), generated.quota());
    }

    @PostMapping("/questions")
    @Transactional
    public AiQuizResponse generateQuestions(
            @RequestParam String subjectCode,
            @RequestParam(defaultValue = "5") int count,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        ProfileSubject focus = profileSubject(user.id(), subjectCode);
        QuizGeneration generated = generationService.generateQuiz(
                user.id(), focus.subjectId(), focus.subjectName(), focus.levelLabel(), count);
        List<AiQuizQuestionResponse> questions = generated.content().questions().stream()
                .map(question -> new AiQuizQuestionResponse(
                        question.questionNo(), question.subjectName(), question.difficulty(), question.text(),
                        question.options().stream()
                                .map(option -> new AiQuizOptionResponse(option.optionNo(), option.text()))
                                .toList()
                )).toList();
        return new AiQuizResponse(generated.generationRunId(), generated.fallback(), questions, generated.quota());
    }

    @PostMapping("/questions/{runId}/check")
    public QuizCheckResult checkQuestion(
            @PathVariable long runId,
            @RequestBody AiQuizCheckRequest body,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        return generationService.checkQuiz(user.id(), runId, body.questionNo(), body.selectedOptionNo());
    }

    @PostMapping("/wrong-notes/{questionId}/feedback")
    @Transactional
    public AiFeedbackResponse generateWrongFeedback(@PathVariable long questionId, HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        WrongNoteContext context = wrongNoteContext(user.id(), questionId);
        FeedbackGeneration generated = generationService.generateWrongFeedback(user.id(), context);
        long feedbackId = insertFeedback(user.id(), questionId, generated);
        return new AiFeedbackResponse(feedbackId, generated.generationRunId(), generated.fallback(),
                generated.content().feedback(), generated.content().recommendedActions(), generated.quota());
    }

    @GetMapping("/wrong-notes/feedback")
    public List<AiFeedbackSummary> feedback(HttpServletRequest request) {
        UserRecord user = currentUserService.require(request);
        return jdbcTemplate.query("""
                SELECT id, question_id, generation_run_id, feedback_text,
                       recommended_action_json, created_at
                  FROM wrong_note_ai_feedback
                 WHERE user_id = ? AND feedback_status = 'ACTIVE'
                 ORDER BY created_at DESC, id DESC
                """, (rs, rowNum) -> new AiFeedbackSummary(
                rs.getLong("id"), rs.getLong("question_id"), rs.getLong("generation_run_id"),
                rs.getString("feedback_text"), readStringList(rs.getString("recommended_action_json")),
                rs.getTimestamp("created_at").toInstant()
        ), user.id());
    }

    @PostMapping("/recommendations")
    @Transactional
    public AiRecommendationResponse generateRecommendation(
            @RequestParam String subjectCode,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        ProfileSubject focus = profileSubject(user.id(), subjectCode);
        String summary = learningSummary(user.id(), focus.subjectId());
        RecommendationGeneration generated = generationService.generateRecommendation(
                user.id(), new RecommendationContext(
                        focus.subjectId(), focus.subjectName(), focus.levelLabel(), summary));
        long queueId = insertRecommendation(user.id(), focus.subjectId(), generated);
        return new AiRecommendationResponse(queueId, generated.generationRunId(), generated.fallback(),
                generated.content().title(), generated.content().content(), generated.content().priority(),
                generated.quota());
    }

    @GetMapping("/recommendations")
    public List<AiRecommendationSummary> recommendations(
            @RequestParam(required = false) String subjectCode,
            HttpServletRequest request
    ) {
        UserRecord user = currentUserService.require(request);
        String code = subjectCode == null ? null : subjectCode.trim().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.query("""
                SELECT q.id, q.subject_id, s.code, s.name, q.title, q.content,
                       q.priority, q.created_at
                  FROM next_plan_queue q
                  JOIN subjects s ON s.id = q.subject_id
                 WHERE q.user_id = ? AND q.queue_status = 'PENDING'
                   AND (? IS NULL OR s.code = ?)
                 ORDER BY q.priority DESC, q.created_at DESC
                 LIMIT 20
                """, (rs, rowNum) -> new AiRecommendationSummary(
                rs.getLong("id"), rs.getLong("subject_id"), rs.getString("code"),
                rs.getString("name"), rs.getString("title"), rs.getString("content"),
                rs.getInt("priority"), rs.getTimestamp("created_at").toInstant()
        ), user.id(), code, code);
    }

    private ProfileSubject profileSubject(long userId, String subjectCode) {
        String code = subjectCode == null ? "" : subjectCode.trim().toUpperCase(java.util.Locale.ROOT);
        return jdbcTemplate.query("""
                SELECT us.subject_id, s.code, s.name, us.learning_level
                  FROM user_subjects us
                  JOIN subjects s ON s.id = us.subject_id
                 WHERE us.user_id = ? AND s.code = ? AND s.is_active = TRUE
                """, (rs, rowNum) -> new ProfileSubject(
                rs.getLong("subject_id"), rs.getString("code"), rs.getString("name"),
                levelLabel(rs.getString("learning_level"))
        ), userId, code).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "학습 프로필에 선택한 과목이 없습니다: " + code
        ));
    }

    private long savePlan(long userId, ProfileSubject focus, PlanGeneration generated) {
        Long planId = jdbcTemplate.query("""
                SELECT id FROM daily_plans
                 WHERE user_id = ? AND subject_id = ? AND plan_date = CURRENT_DATE
                 ORDER BY id DESC LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"), userId, focus.subjectId())
                .stream().findFirst().orElse(null);
        if (planId == null) {
            try {
                GeneratedKeyHolder keys = new GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO daily_plans (
                                user_id, subject_id, plan_date, title, plan_status, created_at, updated_at
                            ) VALUES (?, ?, CURRENT_DATE, ?, 'READY', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                            """, Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, userId);
                    statement.setLong(2, focus.subjectId());
                    statement.setString(3, generated.content().title());
                    return statement;
                }, keys);
                if (keys.getKey() == null) throw new IllegalStateException("플랜 번호를 생성하지 못했습니다.");
                planId = keys.getKey().longValue();
            } catch (DuplicateKeyException exception) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "과목별 플랜 UNIQUE 키가 user_id, subject_id, plan_date인지 확인해 주세요.");
            }
        } else {
            jdbcTemplate.update("""
                    UPDATE daily_plans
                       SET title = ?, plan_status = 'READY', updated_at = CURRENT_TIMESTAMP(6)
                     WHERE id = ? AND user_id = ?
                    """, generated.content().title(), planId, userId);
            jdbcTemplate.update("DELETE FROM plan_steps WHERE plan_id = ?", planId);
        }
        for (var step : generated.content().steps()) {
            jdbcTemplate.update("""
                    INSERT INTO plan_steps (
                        plan_id, step_no, title, content, step_status, completed_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'PENDING', NULL, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, planId, step.stepNo(), step.title(), step.content());
        }
        jdbcTemplate.update("DELETE FROM daily_plan_ai_meta WHERE plan_id = ?", planId);
        jdbcTemplate.update("""
                INSERT INTO daily_plan_ai_meta (
                    plan_id, generation_run_id, rationale, criteria_json, generated_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """, planId, generated.generationRunId(), generated.content().rationale(),
                criteriaJson(focus, generated.fallback()));
        return planId;
    }

    private PlanResponse plan(long planId, long userId) {
        PlanHeader header = jdbcTemplate.query("""
                SELECT dp.id, dp.title, dp.plan_status, dp.subject_id, s.code, s.name
                  FROM daily_plans dp JOIN subjects s ON s.id = dp.subject_id
                 WHERE dp.id = ? AND dp.user_id = ?
                """, (rs, rowNum) -> new PlanHeader(
                rs.getLong("id"), rs.getString("title"), rs.getString("plan_status"),
                rs.getLong("subject_id"), rs.getString("code"), rs.getString("name")
        ), planId, userId).stream().findFirst().orElseThrow();
        List<PlanStepResponse> steps = jdbcTemplate.query("""
                SELECT id, step_no, title, content, step_status
                  FROM plan_steps WHERE plan_id = ? ORDER BY step_no
                """, (rs, rowNum) -> new PlanStepResponse(
                rs.getLong("id"), rs.getInt("step_no"), rs.getString("title"),
                rs.getString("content"), rs.getString("step_status")
        ), planId);
        return new PlanResponse(header.id(), header.title(), header.status(), header.subjectId(),
                header.subjectCode(), header.subjectName(), steps);
    }

    private WrongNoteContext wrongNoteContext(long userId, long questionId) {
        return jdbcTemplate.query("""
                SELECT wn.question_id, q.subject_id, s.name AS subject_name, q.question_text, q.explanation,
                       COALESCE(selected.option_text, '기록 없음') AS selected_answer,
                       correct.option_text AS correct_answer
                  FROM wrong_notes wn
                  JOIN diagnosis_questions q ON q.id = wn.question_id
                  JOIN subjects s ON s.id = q.subject_id
             LEFT JOIN diagnosis_answers da
                    ON da.attempt_id = wn.last_attempt_id AND da.question_id = wn.question_id
             LEFT JOIN question_options selected ON selected.id = da.selected_option_id
                  JOIN question_options correct ON correct.question_id = q.id AND correct.is_correct = TRUE
                 WHERE wn.user_id = ? AND wn.question_id = ?
                """, (rs, rowNum) -> new WrongNoteContext(
                rs.getLong("question_id"), rs.getLong("subject_id"), rs.getString("subject_name"),
                rs.getString("question_text"), rs.getString("selected_answer"),
                rs.getString("correct_answer"), rs.getString("explanation")
        ), userId, questionId).stream().findFirst().orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "AI 해설을 생성할 오답 노트를 찾을 수 없습니다."
        ));
    }

    private long insertFeedback(long userId, long questionId, FeedbackGeneration generated) {
        jdbcTemplate.update("""
                UPDATE wrong_note_ai_feedback
                   SET feedback_status = 'SUPERSEDED'
                 WHERE user_id = ? AND question_id = ? AND feedback_status = 'ACTIVE'
                """, userId, questionId);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO wrong_note_ai_feedback (
                        user_id, question_id, generation_run_id, feedback_text,
                        recommended_action_json, feedback_status, created_at, applied_at
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(6), NULL)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, questionId);
            statement.setLong(3, generated.generationRunId());
            statement.setString(4, generated.content().feedback());
            statement.setString(5, writeJson(generated.content().recommendedActions()));
            return statement;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    private String learningSummary(long userId, long subjectId) {
        return jdbcTemplate.query("""
                SELECT COALESCE(SUM(da.total_questions), 0) AS solved,
                       COALESCE(SUM(da.correct_answers), 0) AS correct,
                       (SELECT COUNT(*) FROM wrong_notes wn
                         JOIN diagnosis_questions q ON q.id = wn.question_id
                        WHERE wn.user_id = ? AND q.subject_id = ? AND wn.is_relearned = FALSE) AS pending_wrong,
                       (SELECT COUNT(*) FROM plan_steps ps
                         JOIN daily_plans dp ON dp.id = ps.plan_id
                        WHERE dp.user_id = ? AND dp.subject_id = ? AND ps.step_status = 'COMPLETED') AS completed_steps
                  FROM diagnosis_attempts da
                 WHERE da.user_id = ? AND da.subject_id = ? AND da.attempt_status = 'COMPLETED'
                """, (rs, rowNum) -> "풀이 %d문제, 정답 %d문제, 미해결 오답 %d개, 완료 단계 %d개"
                .formatted(rs.getInt("solved"), rs.getInt("correct"),
                        rs.getInt("pending_wrong"), rs.getInt("completed_steps")),
                userId, subjectId, userId, subjectId, userId, subjectId)
                .stream().findFirst().orElse("학습 기록 없음");
    }

    private long insertRecommendation(long userId, long subjectId, RecommendationGeneration generated) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO next_plan_queue (
                        user_id, subject_id, source_type, source_id, title, content,
                        priority, queue_status, applied_plan_id, created_at, applied_at
                    ) VALUES (?, ?, 'AI_RECOMMENDATION', ?, ?, ?, ?, 'PENDING', NULL, CURRENT_TIMESTAMP(6), NULL)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, subjectId);
            statement.setLong(3, generated.generationRunId());
            statement.setString(4, generated.content().title());
            statement.setString(5, generated.content().content());
            statement.setInt(6, generated.content().priority());
            return statement;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    private String criteriaJson(ProfileSubject focus, boolean fallback) {
        return writeJson(java.util.Map.of(
                "subjectCode", focus.subjectCode(), "level", focus.levelLabel(), "fallback", fallback));
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("AI 결과 JSON을 저장하지 못했습니다.", exception); }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String levelLabel(String level) {
        return switch (level) {
            case "BEGINNER" -> "초급";
            case "INTERMEDIATE" -> "중급";
            case "ADVANCED" -> "고급";
            default -> level;
        };
    }

    private record ProfileSubject(long subjectId, String subjectCode, String subjectName, String levelLabel) {}
    private record PlanHeader(long id, String title, String status, long subjectId, String subjectCode, String subjectName) {}
    public record PlanStepResponse(long id, int stepNo, String title, String content, String status) {}
    public record PlanResponse(long id, String title, String status, long subjectId, String subjectCode,
                               String subjectName, List<PlanStepResponse> steps) {}
    public record AiPlanResponse(PlanResponse plan, long generationRunId, boolean fallback,
                                 String rationale, AiQuotaResponse quota) {}
    public record AiQuizOptionResponse(int optionNo, String text) {}
    public record AiQuizQuestionResponse(int questionNo, String subjectName, String difficulty,
                                         String text, List<AiQuizOptionResponse> options) {}
    public record AiQuizResponse(long generationRunId, boolean fallback,
                                 List<AiQuizQuestionResponse> questions, AiQuotaResponse quota) {}
    public record AiQuizCheckRequest(int questionNo, int selectedOptionNo) {}
    public record AiFeedbackResponse(long id, long generationRunId, boolean fallback, String feedback,
                                     List<String> recommendedActions, AiQuotaResponse quota) {}
    public record AiRecommendationResponse(long id, long generationRunId, boolean fallback, String title,
                                           String content, int priority, AiQuotaResponse quota) {}
    public record AiPreferenceRequest(boolean enabled, boolean consent, String explanationStyle,
                                      int availableMinutes) {}
    public record AiFeedbackSummary(long id, long questionId, long generationRunId, String feedback,
                                    List<String> recommendedActions, java.time.Instant createdAt) {}
    public record AiRecommendationSummary(long id, long subjectId, String subjectCode, String subjectName,
                                          String title, String content, int priority,
                                          java.time.Instant createdAt) {}
}
