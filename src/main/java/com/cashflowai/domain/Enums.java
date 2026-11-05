package com.cashflowai.domain;

/** Holder for the small enums used across the domain. */
public final class Enums {

    private Enums() {
    }

    public enum InvoiceStatus { DRAFT, SENT, OVERDUE, PARTIALLY_PAID, PAID, DISPUTED, CANCELLED }

    public enum PaymentStatus { COMPLETED, FAILED, REFUNDED }

    /** What deterministic Java detection found. The AI then works out WHY. */
    public enum CaseType { OVERDUE_INVOICE, RECONCILIATION_MISMATCH, PARTIAL_PAYMENT }

    /** Root cause the AI agent diagnoses. */
    public enum RootCause { MISSING_PO, NO_RESPONSE, PAYMENT_NOT_RECORDED, PARTIAL_PAYMENT, LONG_OVERDUE, UNKNOWN }

    public enum CaseStatus {
        DETECTED, INVESTIGATING, WAITING_APPROVAL, ACTION_EXECUTED, MONITORING, RESOLVED, ESCALATED
    }

    public enum ActionStatus { PENDING_APPROVAL, EXECUTED, REJECTED, BLOCKED, FAILED }
}
