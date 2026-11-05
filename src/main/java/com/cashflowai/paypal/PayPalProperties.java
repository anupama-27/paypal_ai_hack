package com.cashflowai.paypal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paypal")
public record PayPalProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        boolean enabled,
        String demoBuyerEmail,
        String webhookId) {
}
