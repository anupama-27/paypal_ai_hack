package com.cashflowai.policy;

import com.cashflowai.policy.PaymentPlanPolicy.Limits;
import com.cashflowai.policy.PaymentPlanPolicy.Offer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentPlanPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    @Test
    void requestOutsideLimitsGetsCounterOffer() {
        Offer offer = PaymentPlanPolicy.offer(new BigDecimal("4500.00"), 3, 90, TODAY, Limits.DEFAULT);
        assertTrue(offer.adjusted());
        assertEquals(2, offer.installments().size());
        assertEquals(new BigDecimal("2250.00"), offer.installments().get(0).amount());
        assertEquals(TODAY, offer.installments().get(0).dueDate());
        assertEquals(TODAY.plusDays(30), offer.installments().get(1).dueDate());
    }

    @Test
    void installmentsAlwaysAddUpToOutstanding() {
        Offer offer = PaymentPlanPolicy.offer(new BigDecimal("1000.01"), 2, 20, TODAY, Limits.DEFAULT);
        assertFalse(offer.adjusted());
        BigDecimal total = offer.installments().stream().map(PaymentPlanPolicy.Installment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("1000.01"), total);
        assertTrue(offer.firstPayment().compareTo(new BigDecimal("500.00")) >= 0);
    }

    @Test
    void nothingOutstandingMeansNoPlan() {
        assertTrue(PaymentPlanPolicy.offer(BigDecimal.ZERO, 2, 30, TODAY, Limits.DEFAULT).installments().isEmpty());
    }
}
