package com.cashflowai.domain;

import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Enums.CaseType;
import com.cashflowai.domain.Enums.RootCause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** A problem CashFlow AI is working on for one invoice. */
@Entity
@Table(name = "ar_case")
public class ArCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long invoiceId;

    @Enumerated(EnumType.STRING)
    private CaseType caseType;

    @Enumerated(EnumType.STRING)
    private CaseStatus status = CaseStatus.DETECTED;

    @Enumerated(EnumType.STRING)
    private RootCause rootCause;

    private Double confidence;
    private String recommendedAction;

    @Column(length = 4000)
    private String explanation;

    private Instant createdAt;
    private Instant resolvedAt;

    protected ArCase() {
    }

    public ArCase(Long invoiceId, CaseType caseType) {
        this.invoiceId = invoiceId;
        this.caseType = caseType;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isOpen() {
        return status != CaseStatus.RESOLVED && status != CaseStatus.ESCALATED;
    }

    public Long getId() { return id; }
    public Long getInvoiceId() { return invoiceId; }
    public CaseType getCaseType() { return caseType; }
    public CaseStatus getStatus() { return status; }
    public void setStatus(CaseStatus status) { this.status = status; }
    public RootCause getRootCause() { return rootCause; }
    public void setRootCause(RootCause rootCause) { this.rootCause = rootCause; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
