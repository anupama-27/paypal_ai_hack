package com.cashflowai.domain;

import com.cashflowai.domain.Enums.ActionStatus;
import com.cashflowai.policy.ActionType;
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

/** Audit trail: every tool call the agent makes, and what the policy engine decided. */
@Entity
@Table(name = "agent_action")
public class AgentAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long caseId;

    private String toolName;

    @Enumerated(EnumType.STRING)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    private ActionStatus status;

    @Column(length = 4000)
    private String toolInput;

    @Column(length = 4000)
    private String toolOutput;

    private boolean requiresApproval;
    private String policyReason;
    private String approvedBy;

    private Instant createdAt;
    private Instant executedAt;

    protected AgentAction() {
    }

    public AgentAction(Long caseId, String toolName, ActionType actionType, String toolInput) {
        this.caseId = caseId;
        this.toolName = toolName;
        this.actionType = actionType;
        this.toolInput = toolInput;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public String getToolName() { return toolName; }
    public ActionType getActionType() { return actionType; }
    public ActionStatus getStatus() { return status; }
    public void setStatus(ActionStatus status) { this.status = status; }
    public String getToolInput() { return toolInput; }
    public String getToolOutput() { return toolOutput; }
    public void setToolOutput(String toolOutput) { this.toolOutput = toolOutput; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; }
    public String getPolicyReason() { return policyReason; }
    public void setPolicyReason(String policyReason) { this.policyReason = policyReason; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
}
