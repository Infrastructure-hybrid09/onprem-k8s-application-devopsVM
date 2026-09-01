package com.neuroplan.auth.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {
    private boolean enabled = true;
    private String provider = "CLOUDFLARE";
    private String baseUrl = "https://api.cloudflare.com/client/v4";
    private String accountId = "";
    private String apiKey = "";
    private String model = "@cf/qwen/qwen3.8-27b";
    private String promptVersion = "neuroplan-0.8.0-v1";
    private int maxCompletionTokens = 1200;
    private int dailyTokenLimit = 5000;
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 90;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public int getMaxCompletionTokens() { return maxCompletionTokens; }
    public void setMaxCompletionTokens(int maxCompletionTokens) { this.maxCompletionTokens = maxCompletionTokens; }
    public int getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(int dailyTokenLimit) { this.dailyTokenLimit = dailyTokenLimit; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }

    public String endpoint() {
        return baseUrl.replaceAll("/+$", "") + "/accounts/" + accountId + "/ai/run/" + model;
    }

    public boolean configured() {
        return enabled && accountId != null && !accountId.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }
}
