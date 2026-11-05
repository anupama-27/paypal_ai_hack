package com.cashflowai.detection;

import com.cashflowai.domain.Enums.PaymentStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.Payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * All money math lives here, in plain Java with BigDecimal.
 * The AI never calculates amounts; it calls a tool that uses this class.
 */
public final class BalanceCalculator {

    private BalanceCalculator() {
    }

    public static BigDecimal totalPaid(List<Payment> matchedPayments) {
        return matchedPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal outstanding(Invoice invoice, List<Payment> matchedPayments) {
        BigDecimal remaining = invoice.getAmount().subtract(totalPaid(matchedPayments));
        return remaining.max(BigDecimal.ZERO);
    }

    /** Days past the due date; 0 if not yet due. */
    public static long daysOverdue(Invoice invoice, LocalDate today) {
        if (invoice.getDueDate() == null || !invoice.getDueDate().isBefore(today)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(invoice.getDueDate(), today);
    }
}
