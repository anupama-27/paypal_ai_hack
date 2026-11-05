package com.cashflowai.policy;

import com.cashflowai.policy.PolicyEngine.Context;
import com.cashflowai.policy.PolicyEngine.Outcome;
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
    void financialChangesAlwaysNeedApproval() {
        for (ActionType t : new ActionType[]{ActionType.UPDATE_REFERENCE, ActionType.CHANGE_AMOUNT,
                ActionType.GIVE_DISCOUNT, ActionType.ISSUE_REFUND, ActionType.WRITE_OFF}) {
            assertEquals(Outcome.NEEDS_APPROVAL, policy.evaluate(t, Context.of(1)).outcome(), t.name());
        }
    }
}
