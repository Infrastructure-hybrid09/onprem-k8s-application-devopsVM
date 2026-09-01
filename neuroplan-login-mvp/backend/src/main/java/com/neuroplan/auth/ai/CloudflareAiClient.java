package com.neuroplan.auth.ai;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

@Component
public class CloudflareAiClient {
    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CloudflareAiClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public AiProviderResponse generateJson(String systemPrompt, String userPrompt) {
        if (!properties.configured()) {
            throw new AiProviderException("NOT_CONFIGURED", null, "Cloudflare Workers AI 설정이 준비되지 않았습니다.");
        }
        Map<String, Object> body = Map.of(
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.1,
                "reasoning_effort", "low",
                "max_completion_tokens", properties.getMaxCompletionTokens(),
                "response_format", Map.of("type", "json_object"),
                "stream", false
        );
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint()))
                    .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String responseBody = response.body();
            if (status < 200 || status >= 300) {
                throw new AiProviderException(httpErrorCode(status), status, providerErrorMessage(responseBody));
            }
            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.path("success").asBoolean(false)) {
                String message = root.path("errors").path(0).path("message").asText("Workers AI 요청에 실패했습니다.");
                throw new AiProviderException("PROVIDER_ERROR", null, message);
            }
            JsonNode result = root.path("result");
            String content = result.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new AiProviderException("EMPTY_RESPONSE", 200, "Workers AI 응답 내용이 비어 있습니다.");
            }
            JsonNode usage = result.path("usage");
            return new AiProviderResponse(
                    content,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("neurons").isNumber() ? usage.path("neurons").decimalValue() : null,
                    response.headers().firstValue("cf-ray").orElse(null),
                    status
            );
        } catch (AiProviderException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new AiProviderException("TIMEOUT", null, "Workers AI 연결 시간이 초과되었습니다.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("INTERRUPTED", null, "Workers AI 요청이 중단되었습니다.");
        } catch (IOException exception) {
            throw new AiProviderException("NETWORK_ERROR", null, "Workers AI 네트워크 요청에 실패했습니다.");
        } catch (Exception exception) {
            throw new AiProviderException("INVALID_RESPONSE", null, "Workers AI 응답을 해석하지 못했습니다.");
        }
    }

    private String httpErrorCode(int status) {
        if (status == 401 || status == 403) return "AUTH_ERROR";
        if (status == 429) return "RATE_LIMIT";
        if (status >= 500) return "PROVIDER_UNAVAILABLE";
        return "HTTP_ERROR";
    }

    private String providerErrorMessage(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = root.path("errors").path(0).path("message").asText("");
            if (message.isBlank()) message = root.path("message").asText("");
            return sanitize(message);
        } catch (Exception ignored) {
            return sanitize(responseBody);
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return "Workers AI 요청에 실패했습니다.";
        return value.length() > 480 ? value.substring(0, 480) : value;
    }

    public record AiProviderResponse(
            String content,
            int inputTokens,
            int outputTokens,
            BigDecimal usageUnits,
            String providerRequestId,
            int httpStatus
    ) {
        public int totalTokens() { return inputTokens + outputTokens; }
    }

    public static class AiProviderException extends RuntimeException {
        private final String code;
        private final Integer httpStatus;

        public AiProviderException(String code, Integer httpStatus, String message) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
        }

        public String code() { return code; }
        public Integer httpStatus() { return httpStatus; }
    }
}
