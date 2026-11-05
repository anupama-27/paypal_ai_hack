package com.cashflowai.detection;

import com.cashflowai.domain.Enums.CaseType;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentPromise;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DetectionRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    private Invoice invoice(String amount, int daysOverdue) {
        return new Invoice("INV-1", "Test", "t@test.example", new BigDecimal(amount),
                TODAY.minusDays(daysOverdue), InvoiceStatus.SENT);
    }

    private Payment payment(String amount) {
        return new Payment("PAY-1", 1L, "INV-1", new BigDecimal(amount), Instant.now());
    }

    @Test
    void overdueWithNothingPaid() {
        assertEquals(Optional.of(CaseType.OVERDUE_INVOICE),
                DetectionRules.classify(invoice("1200", 8), List.of(), List.of(), TODAY));
    }

    @Test
    void unmatchedPaymentMeansReconciliationMismatch() {
        assertEquals(Optional.of(CaseType.RECONCILIATION_MISMATCH),
                DetectionRules.classify(invoice("800", 5), List.of(), List.of(payment("800")), TODAY));
    }

    @Test
    void partialPayment() {
        assertEquals(Optional.of(CaseType.PARTIAL_PAYMENT),
                DetectionRules.classify(invoice("2500", 12), List.of(payment("2000")), List.of(), TODAY));
    }

    @Test
    void notYetDueIsFine() {
        assertEquals(Optional.empty(),
                DetectionRules.classify(invoice("950", -10), List.of(), List.of(), TODAY));
    }

    @Test
    void missedPromiseIsABrokenPromise() {
        PaymentPromise missed = new PaymentPromise(1L, new BigDecimal("600"), TODAY.minusDays(1), "PORTAL");
        assertEquals(Optional.of(CaseType.BROKEN_PROMISE),
                DetectionRules.classify(invoice("1200", 8), List.of(), List.of(), List.of(missed), TODAY));
    }

    @Test
    void promiseNotYetDueIsRespected() {
        PaymentPromise upcoming = new PaymentPromise(1L, new BigDecimal("600"), TODAY.plusDays(3), "PORTAL");
        assertEquals(Optional.empty(),
                DetectionRules.classify(invoice("1200", 8), List.of(), List.of(), List.of(upcoming), TODAY));
    }

    @Test
    void disputedInvoicesAreLeftToHumans() {
        Invoice disputed = invoice("1200", 30);
        disputed.setStatus(InvoiceStatus.DISPUTED);
        assertEquals(Optional.empty(), DetectionRules.classify(disputed, List.of(), List.of(), TODAY));
    }

    @Test
    void outstandingNeverNegative() {
        assertEquals(BigDecimal.ZERO.compareTo(
                BalanceCalculator.outstanding(invoice("100", 1), List.of(payment("150")))), 0);
    }
}
