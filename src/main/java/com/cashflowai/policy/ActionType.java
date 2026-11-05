package com.cashflowai.policy;

import java.util.EnumSet;
import java.util.Set;

/** Every action the agent (or a customer's agent) can attempt, in the order of the policy table. */
public enum ActionType {
    // Always allowed (read-only or safe)
    READ_INVOICE,
    CHECK_PAYMENT,
    CALCULATE_BALANCE,
    FIND_POTENTIAL_PAYMENT,
    RECORD_DIAGNOSIS,
    ESCALATE_TO_HUMAN,
    CUSTOMER_MESSAGE,
    RECORD_PROMISE,
    RAISE_DISPUTE,

    // Allowed automatically, with conditions
    SEND_REMINDER,
    RECONCILE_PAYMENT,
    RESEND_INVOICE,
    PAYMENT_PLAN,

    // Needs a human (unless the owner granted autonomy) or never allowed
    UPDATE_REFERENCE,
    CHANGE_AMOUNT,
    GIVE_DISCOUNT,
    ISSUE_REFUND,
    WRITE_OFF;

    /** Actions the owner may hand over to the agent after a streak of approvals. Money-moving ones never qualify. */
    public static final Set<ActionType> PROMOTABLE = EnumSet.of(UPDATE_REFERENCE, SEND_REMINDER, RECONCILE_PAYMENT);
}
