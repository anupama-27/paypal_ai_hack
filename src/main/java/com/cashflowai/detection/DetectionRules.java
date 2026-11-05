package com.cashflowai.detection;

import com.cashflowai.domain.Enums.CaseType;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentPromise;

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
            Set.of(InvoiceStatus.DRAFT, InvoiceStatus.PAID, InvoiceStatus.CANCELLED, InvoiceStatus.DISPUTED);

    private DetectionRules() {
    }

    public static Optional<CaseType> classify(Invoice invoice,
                                              List<Payment> matchedPayments,
                                              List<Payment> candidatePayments,
                                              LocalDate today) {
        return classify(invoice, matchedPayments, candidatePayments, List.of(), today);
    }

    /**
     * @param matchedPayments   payments already linked to this invoice
     * @param candidatePayments unmatched payments that may belong to this invoice
     * @param pendingPromises   promises to pay (or plan installments) not yet kept
     */
    public static Optional<CaseType> classify(Invoice invoice,
                                              List<Payment> matchedPayments,
                                              List<Payment> candidatePayments,
                                              List<PaymentPromise> pendingPromises,
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
        if (outstanding.signum() == 0) {
            return Optional.empty();
        }

        // 2. The customer promised (or agreed a plan) and the date passed without the money.
        if (pendingPromises.stream().anyMatch(p -> p.getDueDate().isBefore(today))) {
            return Optional.of(CaseType.BROKEN_PROMISE);
        }
        // A promise that is not due yet is respected: no chasing.
        if (!pendingPromises.isEmpty()) {
            return Optional.empty();
        }

        // 3. Some money received, but not all of it.
        if (paid.signum() > 0) {
            return Optional.of(CaseType.PARTIAL_PAYMENT);
        }

        // 4. Past due with nothing paid.
        if (BalanceCalculator.daysOverdue(invoice, today) > 0) {
            return Optional.of(CaseType.OVERDUE_INVOICE);
        }

        return Optional.empty();
    }
}
