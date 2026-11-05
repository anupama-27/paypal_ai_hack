package com.cashflowai.policy;

/** Every action the agent can attempt, in the order of the policy table. */
public enum ActionType {
    // Always allowed (read-only or safe)
    READ_INVOICE,
    CHECK_PAYMENT,
    CALCULATE_BALANCE,
    FIND_POTENTIAL_PAYMENT,
    RECORD_DIAGNOSIS,
    ESCALATE_TO_HUMAN,

    // Allowed automatically, with conditions
    SEND_REMINDER,
    RECONCILE_PAYMENT,
    RESEND_INVOICE,

    // Always needs a human
    UPDATE_REFERENCE,
    CHANGE_AMOUNT,
    GIVE_DISCOUNT,
    ISSUE_REFUND,
    WRITE_OFF
}
