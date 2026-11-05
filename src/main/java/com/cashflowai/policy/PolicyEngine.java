package com.cashflowai.policy;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The AI cannot execute everything it wants. Every tool call passes through here first.
 *
 * | Action                       | Decision                                   |
 * |------------------------------|--------------------------------------------|
 * | Read / check / calculate     | AUTO                                       |
 * | Send standard reminder       | AUTO (needs approval when > 60 days late)  |
 * | Reconcile payment            | AUTO only for an exact amount match        |
 * | Resend unchanged invoice     | AUTO                                       |
 * | Modify PO / reference        | APPROVAL                                   |
 * | Change amount, discount,     | APPROVAL                                   |
 * | refund, write-off            |                                            |
 */
@Component
public class PolicyEngine {

    public static final long ESCALATION_DAYS = 60;

    public enum Outcome { AUTO, NEEDS_APPROVAL, DENY }

    public record Decision(Outcome outcome, String reason) {
        static Decision auto(String reason) { return new Decision(Outcome.AUTO, reason); }
        static Decision approval(String reason) { return new Decision(Outcome.NEEDS_APPROVAL, reason); }
        static Decision deny(String reason) { return new Decision(Outcome.DENY, reason); }
    }

    /**
     * @param daysOverdue        days past due for the invoice
     * @param outstanding        outstanding balance (for reconcile checks), may be null
     * @param paymentAmount      payment amount being reconciled, may be null
     */
    public record Context(long daysOverdue, BigDecimal outstanding, BigDecimal paymentAmount) {
        public static Context of(long daysOverdue) {
            return new Context(daysOverdue, null, null);
        }
    }

    public Decision evaluate(ActionType action, Context ctx) {
        return switch (action) {
            case READ_INVOICE, CHECK_PAYMENT, CALCULATE_BALANCE, FIND_POTENTIAL_PAYMENT,
                 RECORD_DIAGNOSIS, ESCALATE_TO_HUMAN -> Decision.auto("Read-only or safe action");

            case SEND_REMINDER -> ctx.daysOverdue() > ESCALATION_DAYS
                    ? Decision.approval("Invoice is more than " + ESCALATION_DAYS + " days overdue; finance team must approve")
                    : Decision.auto("Standard reminder");

            case RESEND_INVOICE -> Decision.auto("Resending an unchanged invoice");

            case RECONCILE_PAYMENT -> {
                if (ctx.paymentAmount() == null || ctx.outstanding() == null) {
                    yield Decision.deny("Missing payment or balance information");
                }
                if (ctx.paymentAmount().signum() <= 0) {
                    yield Decision.deny("Payment amount must be positive");
                }
                yield ctx.paymentAmount().compareTo(ctx.outstanding()) == 0
                        ? Decision.auto("Exact amount match")
                        : Decision.approval("Amount does not exactly match the outstanding balance");
            }

            case UPDATE_REFERENCE -> Decision.approval("Changing invoice details needs human approval");

            case CHANGE_AMOUNT, GIVE_DISCOUNT, ISSUE_REFUND, WRITE_OFF ->
                    Decision.approval("Financial change; a human must approve");
        };
    }
}
