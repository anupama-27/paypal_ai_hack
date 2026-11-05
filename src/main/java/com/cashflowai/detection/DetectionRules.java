package com.cashflowai.detection;

import com.cashflowai.domain.Enums.CaseType;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.Payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic exception detection. Java decides THAT something is wrong;
 * the AI agent later decides WHY and what to do about it.
 * Pure function, no Spring, so it is easy to unit test.
 */
public final class DetectionRules {

    private static final Set<InvoiceStatus> IGNORED =
            Set.of(InvoiceStatus.DRAFT, InvoiceStatus.PAID, InvoiceStatus.CANCELLED);

    private DetectionRules() {
    }

    /**
     * @param matchedPayments   payments already linked to this invoice
     * @param candidatePayments unmatched payments that reference this invoice number
     */
    public static Optional<CaseType> classify(Invoice invoice,
                                              List<Payment> matchedPayments,
                                              List<Payment> candidatePayments,
                                              LocalDate today) {
        if (IGNORED.contains(invoice.getStatus())) {
            return Optional.empty();
        }

        // 1. Money arrived in PayPal but our books still show the invoice as open.
        if (!candidatePayments.isEmpty()) {
            return Optional.of(CaseType.RECONCILIATION_MISMATCH);
        }

        BigDecimal paid = BalanceCalculator.totalPaid(matchedPayments);
        BigDecimal outstanding = BalanceCalculator.outstanding(invoice, matchedPayments);

        // 2. Some money received, but not all of it.
        if (paid.signum() > 0 && outstanding.signum() > 0) {
            return Optional.of(CaseType.PARTIAL_PAYMENT);
        }

        // 3. Past due with nothing paid.
        if (outstanding.signum() > 0 && BalanceCalculator.daysOverdue(invoice, today) > 0) {
            return Optional.of(CaseType.OVERDUE_INVOICE);
        }

        return Optional.empty();
    }
}
