package com.cashflowai.detection;

import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Finds unmatched payments that could belong to an invoice (see {@link PaymentMatching}). */
@Component
public class PaymentMatcher {

    public record Candidate(Payment payment, String reason) {
    }

    private final PaymentRepository payments;

    public PaymentMatcher(PaymentRepository payments) {
        this.payments = payments;
    }

    public List<Candidate> candidates(Invoice invoice) {
        BigDecimal outstanding = BalanceCalculator.outstanding(invoice, payments.findByInvoiceId(invoice.getId()));
        return payments.findByInvoiceIdIsNull().stream()
                .map(p -> PaymentMatching.match(invoice, outstanding, p).map(reason -> new Candidate(p, reason)))
                .flatMap(Optional::stream)
                .toList();
    }

    public Optional<Candidate> candidate(Invoice invoice, Long paymentId) {
        return candidates(invoice).stream().filter(c -> c.payment().getId().equals(paymentId)).findFirst();
    }
}
