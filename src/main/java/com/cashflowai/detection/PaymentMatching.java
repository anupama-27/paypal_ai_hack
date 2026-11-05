package com.cashflowai.detection;

import com.cashflowai.domain.Enums.PaymentStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.Payment;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides whether an unmatched PayPal payment could belong to an invoice.
 * Used by detection and by the paid-check guard so the agent never chases a customer who already paid.
 */
public final class PaymentMatching {

    private PaymentMatching() {
    }

    /** @return why this payment may belong to the invoice, or empty if it does not */
    public static Optional<String> match(Invoice invoice, BigDecimal outstanding, Payment payment) {
        if (payment.getInvoiceId() != null || payment.getStatus() != PaymentStatus.COMPLETED) {
            return Optional.empty();
        }
        String hint = payment.getInvoiceNumberHint();
        if (hint != null && hint.equalsIgnoreCase(invoice.getInvoiceNumber())) {
            return Optional.of("Payer referenced " + invoice.getInvoiceNumber());
        }
        if (hint != null && !hint.isBlank() && normalize(hint).equals(normalize(invoice.getInvoiceNumber()))) {
            return Optional.of("Payer reference '" + hint + "' looks like a typo of " + invoice.getInvoiceNumber());
        }
        boolean samePayer = payment.getPayerEmail() != null
                && payment.getPayerEmail().equalsIgnoreCase(invoice.getCustomerEmail());
        if (samePayer && outstanding != null && payment.getAmount().compareTo(outstanding) == 0) {
            return Optional.of("Same payer (" + payment.getPayerEmail() + ") and exact outstanding amount");
        }
        return Optional.empty();
    }

    /** Uppercase, alphanumerics only, and the usual O/0 and I/1 mix-ups folded together. */
    static String normalize(String reference) {
        return reference.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "")
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1');
    }
}
