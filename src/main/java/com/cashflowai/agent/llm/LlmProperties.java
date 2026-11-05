package com.cashflowai.agent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cashflow.llm")
public record LlmProperties(String provider, String apiKey, String model, String baseUrl) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean isAnthropic() {
        return "anthropic".equalsIgnoreCase(provider);
    }
}
