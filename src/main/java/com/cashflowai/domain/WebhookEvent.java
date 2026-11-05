package com.cashflowai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** One row per PayPal event id, so a retried webhook is processed only once. */
@Entity
@Table(name = "webhook_event")
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String paypalEventId;

    private String eventType;
    private String resourceId;
    private Instant receivedAt;

    protected WebhookEvent() {
    }

    public WebhookEvent(String paypalEventId, String eventType, String resourceId) {
        this.paypalEventId = paypalEventId;
        this.eventType = eventType;
        this.resourceId = resourceId;
    }

    @PrePersist
    void onCreate() {
        receivedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getPaypalEventId() { return paypalEventId; }
    public String getEventType() { return eventType; }
    public String getResourceId() { return resourceId; }
    public Instant getReceivedAt() { return receivedAt; }
}
