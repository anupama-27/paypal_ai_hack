package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByPaypalEventId(String paypalEventId);
}
