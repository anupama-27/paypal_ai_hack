package com.cashflowai.policy;

import com.cashflowai.policy.PolicyEngine.Context;
import com.cashflowai.policy.PolicyEngine.Outcome;
import com.cashflowai.policy.PolicyEngine.PaidCheck;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEngineTest {

    private final PolicyEngine policy = new PolicyEngine();

    @Test
    void readsAreAlwaysAllowed() {
        assertEquals(Outcome.AUTO, policy.evaluate(ActionType.READ_INVOICE, Context.of(100)).outcome());
    }

    @Test
    void reminderIsAutoUntilSixtyDays() {
        assertEquals(Outcome.AUTO, policy.evaluate(ActionType.SEND_REMINDER, Context.of(30)).outcome());
        assertEquals(Outcome.NEEDS_APPROVAL, policy.evaluate(ActionType.SEND_REMINDER, Context.of(61)).outcome());
    }

    @Test
    void reconcileIsAutoOnlyForExactMatch() {
        Context exact = new Context(5, new BigDecimal("800.00"), new BigDecimal("800"));
        Context partial = new Context(5, new BigDecimal("800.00"), new BigDecimal("500.00"));
        assertEquals(Outcome.AUTO, policy.evaluate(ActionType.RECONCILE_PAYMENT, exact).outcome());
        assertEquals(Outcome.NEEDS_APPROVAL, policy.evaluate(ActionType.RECONCILE_PAYMENT, partial).outcome());
    }

    @Test
    void financialChangesNeedApprovalAndDiscountsAreBlocked() {
        for (ActionType t : new ActionType[]{ActionType.UPDATE_REFERENCE, ActionType.CHANGE_AMOUNT,
                ActionType.ISSUE_REFUND, ActionType.WRITE_OFF}) {
            assertEquals(Outcome.NEEDS_APPROVAL, policy.evaluate(t, Context.of(1)).outcome(), t.name());
        }
        assertEquals(Outcome.DENY, policy.evaluate(ActionType.GIVE_DISCOUNT, Context.of(1)).outcome());
    }

    @Test
    void paidCheckGuardBlocksChasingUntilPaymentsWereChecked() {
        Context notChecked = Context.of(10).withPaidCheck(PaidCheck.NOT_RUN);
        Context paymentFound = Context.of(10).withPaidCheck(PaidCheck.PAYMENT_FOUND);
        assertEquals(Outcome.DENY, policy.evaluate(ActionType.SEND_REMINDER, notChecked).outcome());
        assertEquals(Outcome.DENY, policy.evaluate(ActionType.RESEND_INVOICE, paymentFound).outcome());
        assertEquals(Outcome.AUTO, policy.evaluate(ActionType.SEND_REMINDER,
                Context.of(10).withPaidCheck(PaidCheck.NO_PAYMENT_FOUND)).outcome());
    }

    @Test
    void disputedInvoicesAreNeverChased() {
        assertEquals(Outcome.DENY, policy.evaluate(ActionType.SEND_REMINDER, Context.of(10).withDisputed(true)).outcome());
        assertEquals(Outcome.DENY, policy.evaluate(ActionType.PAYMENT_PLAN, Context.of(10).withDisputed(true)).outcome());
    }

    @Test
    void earnedAutonomyAndCustomerSuppliedPo() {
        assertEquals(Outcome.AUTO, policy.evaluate(ActionType.UPDATE_REFERENCE, Context.of(5).withAutonomy(true)).outcome());
        assertEquals(Outcome.AUTO, policy.evaluate(ActionType.UPDATE_REFERENCE, Context.of(5).fromCustomer()).outcome());
        assertEquals(Outcome.AUTO, policy.evaluate(ActionType.SEND_REMINDER, Context.of(90).withAutonomy(true)).outcome());
        Context under = new Context(5, new BigDecimal("800.00"), new BigDecimal("500.00")).withAutonomy(true);
        Context over = new Context(5, new BigDecimal("800.00"), new BigDecimal("900.00")).withAutonomy(true);
        assertEquals(Outcome.AUTO, policy.evaluate(ActionType.RECONCILE_PAYMENT, under).outcome());
        assertEquals(Outcome.NEEDS_APPROVAL, policy.evaluate(ActionType.RECONCILE_PAYMENT, over).outcome());
    }
}
