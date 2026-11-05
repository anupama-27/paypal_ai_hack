package com.cashflowai.domain;

/** Holder for the small enums used across the domain. */
public final class Enums {

    private Enums() {
    }

    public enum InvoiceStatus { DRAFT, SENT, OVERDUE, PARTIALLY_PAID, PAID, DISPUTED, CANCELLED }

    public enum PaymentStatus { COMPLETED, FAILED, REFUNDED }

    /** What deterministic Java detection found. The AI then works out WHY. */
    public enum CaseType {
        OVERDUE_INVOICE, RECONCILIATION_MISMATCH, PARTIAL_PAYMENT, BROKEN_PROMISE, CUSTOMER_REQUEST, DISPUTE
    }

    /** Root cause the AI agent diagnoses. */
    public enum RootCause {
        MISSING_PO, NO_RESPONSE, PAYMENT_NOT_RECORDED, PARTIAL_PAYMENT, LONG_OVERDUE,
        BROKEN_PROMISE, CUSTOMER_CASH_FLOW, DISPUTED, DISCOUNT_REQUEST, UNKNOWN
    }

    public enum CaseStatus {
        DETECTED, INVESTIGATING, WAITING_APPROVAL, ACTION_EXECUTED, MONITORING, RESOLVED, ESCALATED
    }

    public enum ActionStatus { PENDING_APPROVAL, EXECUTED, REJECTED, BLOCKED, FAILED }

    /** Who triggered an audited action. */
    public enum Actor { AGENT, OWNER, CUSTOMER_PORTAL, CUSTOMER_AGENT, PAYPAL, SYSTEM }

    public enum MessageAuthor { CUSTOMER, CUSTOMER_AGENT, AGENT, OWNER }

    public enum PromiseStatus { PENDING, KEPT, BROKEN }

    public enum AutonomyMode { AUTO, APPROVAL }
}
