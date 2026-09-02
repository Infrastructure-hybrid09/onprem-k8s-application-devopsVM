package com.neuroplan.auth.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neuroplan.auth.ai.AiQuotaService.AiQuotaResponse;
import com.neuroplan.auth.ai.CloudflareAiClient.AiProviderException;
import com.neuroplan.auth.ai.CloudflareAiClient.AiProviderResponse;
import com.neuroplan.auth.error.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

@Service
public class AiGenerationService {
    private static final String JSON_ONLY = "설명과 마크다운 코드 블록 없이 유효한 JSON 객체만 반환하세요. ";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CloudflareAiClient client;
    private final AiQuotaService quotaService;
    private final AiPreferencesService preferencesService;
    private final AiProperties properties;

    public AiGenerationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                               CloudflareAiClient client, AiQuotaService quotaService,
                               AiPreferencesService preferencesService, AiProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.client = client;
        this.quotaService = quotaService;
        this.preferencesService = preferencesService;
        this.properties = properties;
    }

    public PlanGeneration generatePlan(long userId, long subjectId, String subjectName, String levelLabel) {
        var preference = preferencesService.requireEnabled(userId);
        quotaService.requireAvailable(userId);
        String input = subjectName + "|" + levelLabel + "|PLAN";
        long runId = startRun(userId, subjectId, "PLAN", input);
        Instant startedAt = Instant.now();
        String systemPrompt = JSON_ONLY + "당신은 IT 교육용 3단계 학습 플랜 생성기입니다. "
                + "출력 키는 title, rationale, steps이며 steps는 정확히 3개입니다. "
                + "각 단계는 stepNo, title, content를 포함하고 stepNo는 1, 2, 3입니다. "
                + "content는 3개의 구체적인 실습 행동을 줄바꿈으로 구분해 작성하고, "
                + "각 줄 앞에 번호나 기호를 붙이지 마세요.";
        String userPrompt = "%s %s 학습자를 위한 오늘의 3단계 학습 플랜을 한국어로 작성하세요. "
                + "총 학습 시간은 약 %d분이고 설명 방식은 %s입니다. 각 단계는 오늘 수행 가능한 구체적인 실습이어야 합니다.";
        userPrompt = userPrompt.formatted(subjectName, levelLabel, preference.availableMinutes(),
                styleLabel(preference.explanationStyle()));
        try {
            AiProviderResponse response = client.generateJson(systemPrompt, userPrompt);
            try {
                requireCompleteResponse(response);
                PlanContent content = parsePlan(response.content(), subjectName, levelLabel);
                String outputJson = objectMapper.writeValueAsString(content);
                completeRun(runId, "SUCCEEDED", response, startedAt, outputJson, null, null);
                AiQuotaResponse quota = quotaService.recordUsage(
                        userId, runId, response.inputTokens(), response.outputTokens(), response.usageUnits());
                return new PlanGeneration(runId, false, content, quota);
            } catch (RuntimeException | JsonProcessingException invalid) {
                String errorCode = invalidOutputCode(response);
                PlanContent fallback = fallbackPlan(subjectName, levelLabel);
                String outputJson = writeJson(fallback);
                completeRun(runId, "FALLBACK", response, startedAt, outputJson,
                        errorCode, invalidOutputMessage(response, "플랜"));
                AiQuotaResponse quota = quotaService.recordRefundedUsage(
                        userId, runId, response.inputTokens(), response.outputTokens(), response.usageUnits(),
                        errorCode + " 플랜 응답 환불");
                return new PlanGeneration(runId, true, fallback, quota);
            }
        } catch (AiProviderException exception) {
            PlanContent fallback = fallbackPlan(subjectName, levelLabel);
            completeRun(runId, "FALLBACK", null, startedAt, writeJson(fallback),
                    exception.code(), exception.getMessage(), exception.httpStatus());
            return new PlanGeneration(runId, true, fallback, quotaService.status(userId));
        }
    }

    public FeedbackGeneration generateWrongFeedback(long userId, WrongNoteContext context) {
        var preference = preferencesService.requireEnabled(userId);
        quotaService.requireAvailable(userId);
        String input = context.questionId() + "|" + context.selectedAnswer() + "|" + context.correctAnswer();
        long runId = startRun(userId, context.subjectId(), "WRONG_FEEDBACK", input);
        Instant startedAt = Instant.now();
        String systemPrompt = JSON_ONLY + "당신은 IT 학습자의 오답을 교정하는 튜터입니다. "
                + "출력 키는 feedback과 recommendedActions이며 recommendedActions는 2~3개의 문자열 배열입니다.";
        String userPrompt = "과목: %s\n문제: %s\n내 답: %s\n정답: %s\n기존 해설: %s\n"
                .formatted(context.subjectName(), context.questionText(), context.selectedAnswer(),
                        context.correctAnswer(), context.explanation())
                + "틀린 이유를 비난 없이 %s 방식으로 설명하고 바로 실행할 수 있는 재학습 행동을 추천하세요."
                .formatted(styleLabel(preference.explanationStyle()));
        try {
            AiProviderResponse response = client.generateJson(systemPrompt, userPrompt);
            try {
                requireCompleteResponse(response);
                FeedbackContent content = parseFeedback(response.content());
                String outputJson = objectMapper.writeValueAsString(content);
                completeRun(runId, "SUCCEEDED", response, startedAt, outputJson, null, null);
                AiQuotaResponse quota = quotaService.recordUsage(
                        userId, runId, response.inputTokens(), response.outputTokens(), response.usageUnits());
                return new FeedbackGeneration(runId, false, content, quota);
            } catch (RuntimeException | JsonProcessingException invalid) {
                return fallbackFeedback(userId, runId, context, response, startedAt,
                        invalidOutputCode(response));
            }
        } catch (AiProviderException exception) {
            FeedbackContent content = basicFeedback(context);
            completeRun(runId, "FALLBACK", null, startedAt, writeJson(content),
                    exception.code(), exception.getMessage(), exception.httpStatus());
            return new FeedbackGeneration(runId, true, content, quotaService.status(userId));
        }
    }

    public RecommendationGeneration generateRecommendation(long userId, RecommendationContext context) {
        var preference = preferencesService.requireEnabled(userId);
        quotaService.requireAvailable(userId);
        String input = context.subjectId() + "|" + context.learningLevel() + "|" + context.summary();
        long runId = startRun(userId, context.subjectId(), "WEEKLY_INSIGHT", input);
        Instant startedAt = Instant.now();
        String systemPrompt = JSON_ONLY + "당신은 IT 학습 기록을 분석해 다음 학습을 추천하는 코치입니다. "
                + "출력 키는 title, content, priority이며 priority는 1에서 5 사이의 정수입니다.";
        String userPrompt = "과목: %s\n수준: %s\n학습 기록: %s\n"
                .formatted(context.subjectName(), context.learningLevel(), context.summary())
                + "약 %d분 안에 수행할 수 있도록 취약점을 보완할 다음 학습 한 가지를 %s 방식의 구체적인 실행 방법과 함께 추천하세요."
                .formatted(preference.availableMinutes(), styleLabel(preference.explanationStyle()));
        try {
            AiProviderResponse response = client.generateJson(systemPrompt, userPrompt);
            try {
                requireCompleteResponse(response);
                RecommendationContent content = parseRecommendation(response.content(), context.subjectName());
                String outputJson = objectMapper.writeValueAsString(content);
                completeRun(runId, "SUCCEEDED", response, startedAt, outputJson, null, null);
                AiQuotaResponse quota = quotaService.recordUsage(
                        userId, runId, response.inputTokens(), response.outputTokens(), response.usageUnits());
                return new RecommendationGeneration(runId, false, content, quota);
            } catch (RuntimeException | JsonProcessingException invalid) {
                return fallbackRecommendation(userId, runId, context, response, startedAt,
                        invalidOutputCode(response));
            }
        } catch (AiProviderException exception) {
            RecommendationContent content = basicRecommendation(context);
            completeRun(runId, "FALLBACK", null, startedAt, writeJson(content),
                    exception.code(), exception.getMessage(), exception.httpStatus());
            return new RecommendationGeneration(runId, true, content, quotaService.status(userId));
        }
    }

    public QuizGeneration generateQuiz(long userId, long subjectId, String subjectName,
                                       String levelLabel, int requestedCount) {
        preferencesService.requireEnabled(userId);
        quotaService.requireAvailable(userId);
        int count = Math.min(Math.max(requestedCount, 3), 5);
        String input = subjectName + "|" + levelLabel + "|QUIZ|" + count;
        long runId = startRun(userId, subjectId, "QUESTION_DRAFT", input);
        Instant startedAt = Instant.now();
        String systemPrompt = JSON_ONLY + "당신은 IT 교육용 객관식 확인 문제 생성기입니다. "
                + "출력 키는 questions이며 정확히 " + count + "개입니다. "
                + "각 문제는 questionNo, text, difficulty, explanation, options를 포함합니다. "
                + "options는 optionNo, text, correct를 가진 정확히 4개 보기이며 정답은 하나뿐입니다. "
                + "문제는 100자, 보기는 60자, 해설은 180자 이내로 간결하게 작성하세요.";
        String userPrompt = ("%s %s 학습자를 위한 서로 중복되지 않는 확인 문제 %d개를 한국어로 작성하세요. "
                + "암기만 묻지 말고 실제 상황 판단과 개념 이해를 고르게 확인하세요.")
                .formatted(subjectName, levelLabel, count);
        try {
            AiProviderResponse response = client.generateJson(
                    systemPrompt, userPrompt, properties.getQuizMaxCompletionTokens());
            try {
                requireCompleteResponse(response);
                QuizContent content = parseQuiz(response.content(), subjectName, levelLabel, count);
                String outputJson = objectMapper.writeValueAsString(content);
                completeRun(runId, "SUCCEEDED", response, startedAt, outputJson, null, null);
                AiQuotaResponse quota = quotaService.recordUsage(
                        userId, runId, response.inputTokens(), response.outputTokens(), response.usageUnits());
                return new QuizGeneration(runId, false, content, quota);
            } catch (RuntimeException | JsonProcessingException invalid) {
                String errorCode = invalidOutputCode(response);
                QuizContent fallback = fallbackQuiz(subjectName, levelLabel, count);
                completeRun(runId, "FALLBACK", response, startedAt, writeJson(fallback),
                        errorCode, invalidOutputMessage(response, "문제"));
                AiQuotaResponse quota = quotaService.recordRefundedUsage(
                        userId, runId, response.inputTokens(), response.outputTokens(), response.usageUnits(),
                        errorCode + " 문제 응답 환불");
                return new QuizGeneration(runId, true, fallback, quota);
            }
        } catch (AiProviderException exception) {
            QuizContent fallback = fallbackQuiz(subjectName, levelLabel, count);
            completeRun(runId, "FALLBACK", null, startedAt, writeJson(fallback),
                    exception.code(), exception.getMessage(), exception.httpStatus());
            return new QuizGeneration(runId, true, fallback, quotaService.status(userId));
        }
    }

    public QuizCheckResult checkQuiz(long userId, long runId, int questionNo, int selectedOptionNo) {
        String outputJson = jdbcTemplate.query("""
                SELECT output_json
                  FROM ai_generation_runs
                 WHERE id = ? AND user_id = ?
                   AND generation_status IN ('SUCCEEDED', 'FALLBACK')
                """, (rs, rowNum) -> rs.getString("output_json"), runId, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "AI 문제 세트를 찾을 수 없습니다."
                ));
        try {
            QuizContent content = objectMapper.readValue(outputJson, QuizContent.class);
            if (content.questions() == null || content.questions().isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "선택한 AI 실행 기록은 문제 세트가 아닙니다.");
            }
            QuizQuestionContent question = content.questions().stream()
                    .filter(item -> item.questionNo() == questionNo)
                    .findFirst().orElseThrow(() -> new ApiException(
                            HttpStatus.BAD_REQUEST, "AI 문제 번호가 올바르지 않습니다."
                    ));
            QuizOptionContent selected = question.options().stream()
                    .filter(item -> item.optionNo() == selectedOptionNo)
                    .findFirst().orElseThrow(() -> new ApiException(
                            HttpStatus.BAD_REQUEST, "선택한 보기가 올바르지 않습니다."
                    ));
            int correctOptionNo = question.options().stream()
                    .filter(QuizOptionContent::correct)
                    .findFirst().orElseThrow().optionNo();
            return new QuizCheckResult(selected.correct(), correctOptionNo, question.explanation());
        } catch (ApiException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "저장된 AI 문제 결과를 읽을 수 없습니다.");
        }
    }

    public void markPersistenceFailure(long runId, String message) {
        jdbcTemplate.update("""
                UPDATE ai_generation_runs
                   SET generation_status = 'FAILED',
                       error_code = 'PERSISTENCE_FAILED',
                       error_message = ?,
                       completed_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ?
                """, truncate(message, 500), runId);
    }

    private FeedbackGeneration fallbackFeedback(long userId, long runId, WrongNoteContext context,
                                                  AiProviderResponse response, Instant startedAt, String code) {
        FeedbackContent content = basicFeedback(context);
        completeRun(runId, "FALLBACK", response, startedAt, writeJson(content), code,
                invalidOutputMessage(response, "오답 해설"));
        AiQuotaResponse quota = quotaService.recordRefundedUsage(
                userId, runId, response.inputTokens(), response.outputTokens(), response.usageUnits(),
                code + " 오답 해설 응답 환불");
        return new FeedbackGeneration(runId, true, content, quota);
    }

    private RecommendationGeneration fallbackRecommendation(long userId, long runId, RecommendationContext context,
                                                              AiProviderResponse response, Instant startedAt, String code) {
        RecommendationContent content = basicRecommendation(context);
        completeRun(runId, "FALLBACK", response, startedAt, writeJson(content), code,
                invalidOutputMessage(response, "재학습 추천"));
        AiQuotaResponse quota = quotaService.recordRefundedUsage(
                userId, runId, response.inputTokens(), response.outputTokens(), response.usageUnits(),
                code + " 재학습 추천 응답 환불");
        return new RecommendationGeneration(runId, true, content, quota);
    }

    private void requireCompleteResponse(AiProviderResponse response) {
        if (!response.completedNormally()) {
            throw new IllegalArgumentException("Workers AI completion ended with finish_reason="
                    + response.finishReason());
        }
    }

    private String invalidOutputCode(AiProviderResponse response) {
        if (response.outputLimitReached()) return "OUTPUT_LIMIT";
        return response.completedNormally() ? "INVALID_JSON" : "PROVIDER_FINISH";
    }

    private String invalidOutputMessage(AiProviderResponse response, String featureName) {
        if (response.outputLimitReached()) {
            return "AI " + featureName + " 응답이 출력 토큰 한도에서 종료되어 검증된 기본 결과를 사용했습니다.";
        }
        if (!response.completedNormally()) {
            return "AI " + featureName + " 응답이 finish_reason=" + response.finishReason()
                    + " 상태로 종료되어 검증된 기본 결과를 사용했습니다.";
        }
        return "AI " + featureName + " 응답 형식이 맞지 않아 검증된 기본 결과를 사용했습니다.";
    }

    private long startRun(long userId, Long subjectId, String requestType, String input) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO ai_generation_runs (
                        user_id, subject_id, request_type, provider, model_name, prompt_version,
                        generation_status, provider_request_id, input_hash, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', NULL, ?, CURRENT_TIMESTAMP(6))
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            if (subjectId == null) statement.setNull(2, java.sql.Types.BIGINT); else statement.setLong(2, subjectId);
            statement.setString(3, requestType);
            statement.setString(4, properties.getProvider());
            statement.setString(5, properties.getModel());
            statement.setString(6, properties.getPromptVersion());
            statement.setString(7, sha256(input));
            return statement;
        }, keys);
        Number id = keys.getKey();
        if (id == null) throw new IllegalStateException("AI 요청 번호를 생성하지 못했습니다.");
        return id.longValue();
    }

    private void completeRun(long runId, String status, AiProviderResponse response, Instant startedAt,
                             String outputJson, String errorCode, String errorMessage) {
        completeRun(runId, status, response, startedAt, outputJson, errorCode, errorMessage,
                response == null ? null : response.httpStatus());
    }

    private void completeRun(long runId, String status, AiProviderResponse response, Instant startedAt,
                             String outputJson, String errorCode, String errorMessage, Integer httpStatus) {
        long latency = Duration.between(startedAt, Instant.now()).toMillis();
        jdbcTemplate.update("""
                UPDATE ai_generation_runs
                   SET generation_status = ?, provider_request_id = ?, input_tokens = ?, output_tokens = ?,
                       latency_ms = ?, http_status = ?, output_json = ?, error_code = ?, error_message = ?,
                       provider_usage_units = ?, provider_usage_unit = ?, completed_at = CURRENT_TIMESTAMP(6)
                 WHERE id = ?
                """,
                status,
                response == null ? null : response.providerRequestId(),
                response == null ? null : response.inputTokens(),
                response == null ? null : response.outputTokens(),
                latency,
                httpStatus,
                outputJson,
                errorCode,
                truncate(errorMessage, 500),
                response == null ? null : response.usageUnits(),
                response == null || response.usageUnits() == null ? null : "NEURONS",
                runId
        );
    }

    private PlanContent parsePlan(String value, String subjectName, String levelLabel) throws JsonProcessingException {
        JsonNode root = parseObject(value);
        String title = requiredText(root, "title", 200);
        String rationale = optionalText(root, "rationale", subjectName + " · " + levelLabel + " 맞춤 기준", 1000);
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.size() != 3) throw new IllegalArgumentException("steps must have 3 items");
        List<PlanStepContent> steps = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            JsonNode step = stepsNode.get(index);
            int stepNo = step.path("stepNo").asInt(step.path("step").asInt(index + 1));
            if (stepNo != index + 1) throw new IllegalArgumentException("invalid step number");
            steps.add(new PlanStepContent(stepNo, requiredText(step, "title", 200), requiredText(step, "content", 4000)));
        }
        return new PlanContent(title, rationale, steps);
    }

    private FeedbackContent parseFeedback(String value) throws JsonProcessingException {
        JsonNode root = parseObject(value);
        String feedback = requiredText(root, "feedback", 6000);
        JsonNode actions = root.path("recommendedActions");
        if (!actions.isArray() || actions.isEmpty()) throw new IllegalArgumentException("recommendedActions required");
        List<String> result = new ArrayList<>();
        actions.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank() && result.size() < 3) result.add(truncate(item.asText(), 500));
        });
        if (result.isEmpty()) throw new IllegalArgumentException("recommendedActions required");
        return new FeedbackContent(feedback, result);
    }

    private RecommendationContent parseRecommendation(String value, String subjectName) throws JsonProcessingException {
        JsonNode root = parseObject(value);
        int priority = Math.min(Math.max(root.path("priority").asInt(3), 1), 5);
        return new RecommendationContent(requiredText(root, "title", 200), requiredText(root, "content", 4000), priority);
    }

    private QuizContent parseQuiz(String value, String subjectName, String levelLabel, int count)
            throws JsonProcessingException {
        JsonNode root = parseObject(value);
        JsonNode questionsNode = root.path("questions");
        if (!questionsNode.isArray() || questionsNode.size() != count) {
            throw new IllegalArgumentException("questions must have " + count + " items");
        }
        List<QuizQuestionContent> questions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            JsonNode question = questionsNode.get(index);
            int questionNo = index + 1;
            JsonNode optionsNode = question.path("options");
            if (!optionsNode.isArray() || optionsNode.size() != 4) {
                throw new IllegalArgumentException("each question must have 4 options");
            }
            List<QuizOptionContent> options = new ArrayList<>();
            int correctCount = 0;
            int declaredCorrectOptionNo = intValue(
                    question, -1, "correctOptionNo", "correct_option_no", "correctAnswer", "correct_answer");
            for (int optionIndex = 0; optionIndex < 4; optionIndex++) {
                JsonNode option = optionsNode.get(optionIndex);
                int optionNo = optionIndex + 1;
                boolean correct = booleanValue(option, "correct", "isCorrect", "is_correct")
                        || declaredCorrectOptionNo == optionNo;
                if (correct) correctCount++;
                options.add(new QuizOptionContent(optionNo,
                        requiredTextAny(option, 1000, "text", "optionText", "option_text"), correct));
            }
            if (correctCount != 1) throw new IllegalArgumentException("exactly one correct option is required");
            questions.add(new QuizQuestionContent(
                    questionNo,
                    subjectName,
                    optionalText(question, "difficulty", levelLabel, 30),
                    requiredTextAny(question, 1000, "text", "questionText", "question_text", "question"),
                    requiredText(question, "explanation", 4000),
                    options
            ));
        }
        return new QuizContent(questions);
    }

    private ObjectNode parseObject(String value) throws JsonProcessingException {
        String normalized = value.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        JsonNode node = objectMapper.readTree(normalized);
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("JSON object required");
        return object;
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) throw new IllegalArgumentException(field + " required");
        return truncate(value, maxLength);
    }

    private String requiredTextAny(JsonNode node, int maxLength, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("").trim();
            if (!value.isBlank()) return truncate(value, maxLength);
        }
        throw new IllegalArgumentException(String.join("/", fields) + " required");
    }

    private boolean booleanValue(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isBoolean()) return value.asBoolean();
            if (value.isInt()) return value.asInt() == 1;
            if (value.isTextual()) {
                String text = value.asText().trim();
                if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
                if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
            }
        }
        return false;
    }

    private int intValue(JsonNode node, int fallback, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.canConvertToInt()) return value.asInt();
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 다음 호환 필드를 확인합니다.
                }
            }
        }
        return fallback;
    }

    private String optionalText(JsonNode node, String field, String fallback, int maxLength) {
        String value = node.path(field).asText("").trim();
        return truncate(value.isBlank() ? fallback : value, maxLength);
    }

    private PlanContent fallbackPlan(String subjectName, String levelLabel) {
        return new PlanContent(
                subjectName + " " + levelLabel + " 오늘의 학습 플랜",
                "외부 AI 응답을 사용할 수 없어 검증된 기본 플랜을 제공합니다.",
                List.of(
                        new PlanStepContent(1, subjectName + " 핵심 개념 익히기", levelLabel + " 수준의 필수 개념과 용어를 정리합니다."),
                        new PlanStepContent(2, subjectName + " 미니 실습 따라하기", "핵심 명령과 동작 흐름을 작은 실습으로 확인합니다."),
                        new PlanStepContent(3, subjectName + " 핵심 내용 복습", "오늘 학습한 내용을 요약하고 확인 문제를 준비합니다.")
                )
        );
    }

    private FeedbackContent basicFeedback(WrongNoteContext context) {
        return new FeedbackContent(
                "선택한 답과 정답의 차이를 기존 해설에서 다시 확인해 보세요. " + context.explanation(),
                List.of("핵심 용어를 한 문장으로 다시 정리하기", "정답을 가리고 같은 문제를 다시 풀기")
        );
    }

    private RecommendationContent basicRecommendation(RecommendationContext context) {
        return new RecommendationContent(
                context.subjectName() + " 취약 개념 다시 확인하기",
                "최근 오답과 학습 기록을 기준으로 핵심 개념을 복습하고 짧은 실습으로 결과를 확인하세요.",
                3
        );
    }

    private QuizContent fallbackQuiz(String subjectName, String levelLabel, int count) {
        List<String> stems = List.of(
                "%s 학습을 시작할 때 가장 먼저 확인할 내용은 무엇인가요?",
                "%s 개념을 실제 역량으로 연결하는 가장 좋은 방법은 무엇인가요?",
                "%s 학습 효율을 높이는 방법으로 가장 적절한 것은 무엇인가요?",
                "%s 작업 결과를 검증해야 하는 가장 중요한 이유는 무엇인가요?",
                "%s 문제를 해결하지 못했을 때 다음 행동으로 가장 적절한 것은 무엇인가요?"
        );
        List<QuizQuestionContent> questions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            questions.add(new QuizQuestionContent(
                    index + 1, subjectName, levelLabel,
                    stems.get(index).formatted(subjectName),
                    "직접 실행하고 결과를 확인하는 습관이 개념을 실제 문제 해결 능력으로 연결합니다.",
                    List.of(
                            new QuizOptionContent(1, "학습 목표와 현재 이해 수준을 확인한다", index == 0),
                            new QuizOptionContent(2, "직접 실행하고 결과를 검증한다", index != 0),
                            new QuizOptionContent(3, "정답만 외우고 과정을 생략한다", false),
                            new QuizOptionContent(4, "오류 메시지를 확인하지 않고 반복 실행한다", false)
                    )
            ));
        }
        return new QuizContent(questions);
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { return "{}"; }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("입력 해시를 생성하지 못했습니다.", exception);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private String styleLabel(String style) {
        return switch (style) {
            case "DETAILED" -> "자세한 설명";
            case "PRACTICAL" -> "실습 중심";
            default -> "간결한 설명";
        };
    }

    public record PlanStepContent(int stepNo, String title, String content) {}
    public record PlanContent(String title, String rationale, List<PlanStepContent> steps) {}
    public record PlanGeneration(long generationRunId, boolean fallback, PlanContent content, AiQuotaResponse quota) {}
    public record FeedbackContent(String feedback, List<String> recommendedActions) {}
    public record FeedbackGeneration(long generationRunId, boolean fallback, FeedbackContent content, AiQuotaResponse quota) {}
    public record RecommendationContent(String title, String content, int priority) {}
    public record RecommendationGeneration(long generationRunId, boolean fallback, RecommendationContent content, AiQuotaResponse quota) {}
    public record QuizOptionContent(int optionNo, String text, boolean correct) {}
    public record QuizQuestionContent(int questionNo, String subjectName, String difficulty, String text,
                                      String explanation, List<QuizOptionContent> options) {}
    public record QuizContent(List<QuizQuestionContent> questions) {}
    public record QuizGeneration(long generationRunId, boolean fallback, QuizContent content, AiQuotaResponse quota) {}
    public record QuizCheckResult(boolean correct, int correctOptionNo, String explanation) {}
    public record WrongNoteContext(long questionId, long subjectId, String subjectName, String questionText,
                                   String selectedAnswer, String correctAnswer, String explanation) {}
    public record RecommendationContext(long subjectId, String subjectName, String learningLevel, String summary) {}
}
