package com.cashflowai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalTime;

/**
 * What the owner changed on a Timeline event: its title and time of day. The date is not
 * stored here; it lives on the invoice, promise or case, so every page agrees on it.
 */
@Entity
@Table(name = "timeline_override", uniqueConstraints = @UniqueConstraint(columnNames = {"kind", "sourceId"}))
public class TimelineOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private Long sourceId;

    private String label;
    private LocalTime eventTime;

    protected TimelineOverride() {
    }

    public TimelineOverride(String kind, Long sourceId) {
        this.kind = kind;
        this.sourceId = sourceId;
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public Long getSourceId() { return sourceId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public LocalTime getEventTime() { return eventTime; }
    public void setEventTime(LocalTime eventTime) { this.eventTime = eventTime; }
}
