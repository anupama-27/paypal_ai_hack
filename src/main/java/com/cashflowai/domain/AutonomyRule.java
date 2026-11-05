package com.cashflowai.domain;

import com.cashflowai.domain.Enums.AutonomyMode;
import com.cashflowai.policy.ActionType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** Earned autonomy: after enough approvals in a row, the owner can let the agent do this action alone. */
@Entity
@Table(name = "autonomy_rule")
public class AutonomyRule {

    @Id
    @Enumerated(EnumType.STRING)
    private ActionType actionType;

    @Enumerated(EnumType.STRING)
    private AutonomyMode mode = AutonomyMode.APPROVAL;

    private int approvalStreak;
    private Instant updatedAt;

    protected AutonomyRule() {
    }

    public AutonomyRule(ActionType actionType) {
        this.actionType = actionType;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public ActionType getActionType() { return actionType; }
    public AutonomyMode getMode() { return mode; }
    public void setMode(AutonomyMode mode) { this.mode = mode; }
    public int getApprovalStreak() { return approvalStreak; }
    public void setApprovalStreak(int approvalStreak) { this.approvalStreak = approvalStreak; }
    public Instant getUpdatedAt() { return updatedAt; }
}
