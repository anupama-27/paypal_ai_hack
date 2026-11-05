package com.cashflowai.policy;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The AI cannot execute everything it wants. Every tool call passes through here first.
 *
 * | Action                       | Decision                                                        |
 * |------------------------------|-----------------------------------------------------------------|
 * | Read / check / calculate     | AUTO                                                            |
 * | Reminder / resend            | DENY if disputed, if no paid-check yet, or if a payment exists  |
 * |                              | AUTO up to 60 days late, then APPROVAL (unless autonomy granted)|
 * | Reconcile payment            | AUTO for exact match; underpayment AUTO only with autonomy      |
 * | Modify PO / reference        | APPROVAL, AUTO if the customer supplied it or autonomy granted  |
 * | Payment plan                 | AUTO (offers are always built inside the owner's limits)        |
 * | Discount                     | DENY                                                            |
 * | Change amount, refund,       | APPROVAL                                                        |
 * | write-off                    |                                                                 |
 */
@Component
public class PolicyEngine {

    public static final long ESCALATION_DAYS = 60;

    public enum Outcome { AUTO, NEEDS_APPROVAL, DENY }

    /** Result of find_potential_payment for this case, used by the paid-check guard. */
    public enum PaidCheck { NOT_RUN, NO_PAYMENT_FOUND, PAYMENT_FOUND }

    public record Decision(Outcome outcome, String reason) {
        static Decision auto(String reason) { return new Decision(Outcome.AUTO, reason); }
        static Decision approval(String reason) { return new Decision(Outcome.NEEDS_APPROVAL, reason); }
        static Decision deny(String reason) { return new Decision(Outcome.DENY, reason); }
    }

    /**
     * @param daysOverdue      days past due for the invoice
     * @param outstanding      outstanding balance (for reconcile checks), may be null
     * @param paymentAmount    payment amount being reconciled, may be null
     * @param paidCheck        whether the agent already looked for an existing payment
     * @param disputed         invoice is under dispute
     * @param autonomyGranted  owner promoted this action type to run without approval
     * @param customerSupplied the change comes from the customer through their verified link or agent key
     */
    public record Context(long daysOverdue, BigDecimal outstanding, BigDecimal paymentAmount,
                          PaidCheck paidCheck, boolean disputed, boolean autonomyGranted,
                          boolean customerSupplied) {

        public Context(long daysOverdue, BigDecimal outstanding, BigDecimal paymentAmount) {
            this(daysOverdue, outstanding, paymentAmount, PaidCheck.NO_PAYMENT_FOUND, false, false, false);
        }

        public static Context of(long daysOverdue) {
            return new Context(daysOverdue, null, null);
        }

        public Context withPaidCheck(PaidCheck check) {
            return new Context(daysOverdue, outstanding, paymentAmount, check, disputed, autonomyGranted, customerSupplied);
        }

        public Context withDisputed(boolean value) {
            return new Context(daysOverdue, outstanding, paymentAmount, paidCheck, value, autonomyGranted, customerSupplied);
        }

        public Context withAutonomy(boolean value) {
            return new Context(daysOverdue, outstanding, paymentAmount, paidCheck, disputed, value, customerSupplied);
        }

        public Context fromCustomer() {
            return new Context(daysOverdue, outstanding, paymentAmount, paidCheck, disputed, autonomyGranted, true);
        }
    }

    public Decision evaluate(ActionType action, Context ctx) {
        return switch (action) {
            case READ_INVOICE, CHECK_PAYMENT, CALCULATE_BALANCE, FIND_POTENTIAL_PAYMENT,
                 RECORD_DIAGNOSIS, ESCALATE_TO_HUMAN, CUSTOMER_MESSAGE, RAISE_DISPUTE ->
                    Decision.auto("Read-only or safe action");

            case RECORD_PROMISE -> Decision.auto("Recording what the customer committed to");

            case SEND_REMINDER, RESEND_INVOICE -> {
                Decision guard = contactGuard(ctx);
                if (guard != null) {
                    yield guard;
                }
                if (action == ActionType.RESEND_INVOICE) {
                    yield Decision.auto("Resending an unchanged invoice");
                }
                if (ctx.daysOverdue() > ESCALATION_DAYS) {
                    yield ctx.autonomyGranted()
                            ? Decision.auto("Owner granted autonomy for late reminders")
                            : Decision.approval("Invoice is more than " + ESCALATION_DAYS
                            + " days overdue; finance team must approve");
                }
                yield Decision.auto("Standard reminder");
            }

            case RECONCILE_PAYMENT -> {
                if (ctx.paymentAmount() == null || ctx.outstanding() == null) {
                    yield Decision.deny("Missing payment or balance information");
                }
                if (ctx.paymentAmount().signum() <= 0) {
                    yield Decision.deny("Payment amount must be positive");
                }
                int cmp = ctx.paymentAmount().compareTo(ctx.outstanding());
                if (cmp == 0) {
                    yield Decision.auto("Exact amount match");
                }
                if (cmp < 0 && ctx.autonomyGranted()) {
                    yield Decision.auto("Underpayment; owner granted autonomy for partial matches");
                }
                yield Decision.approval("Amount does not exactly match the outstanding balance");
            }

            case UPDATE_REFERENCE -> {
                if (ctx.customerSupplied()) {
                    yield Decision.auto("PO supplied by the customer through a verified channel; amount unchanged");
                }
                yield ctx.autonomyGranted()
                        ? Decision.auto("Owner granted autonomy for PO updates")
                        : Decision.approval("Changing invoice details needs human approval");
            }

            case PAYMENT_PLAN -> ctx.disputed()
                    ? Decision.deny("Invoice is disputed; resolve the dispute first")
                    : Decision.auto("Plan is inside the owner's payment-plan limits");

            case GIVE_DISCOUNT -> Decision.deny("The agent never offers discounts; escalate to the owner");

            case CHANGE_AMOUNT, ISSUE_REFUND, WRITE_OFF ->
                    Decision.approval("Financial change; a human must approve");
        };
    }

    /** Never contact a customer who is disputing, or who may already have paid. */
    private static Decision contactGuard(Context ctx) {
        if (ctx.disputed()) {
            return Decision.deny("Invoice is disputed; reminders are paused");
        }
        return switch (ctx.paidCheck()) {
            case NOT_RUN -> Decision.deny("Paid-check guard: call find_potential_payment before contacting the customer");
            case PAYMENT_FOUND -> Decision.deny("Paid-check guard: a matching payment exists; reconcile it instead of chasing");
            case NO_PAYMENT_FOUND -> null;
        };
    }
}
