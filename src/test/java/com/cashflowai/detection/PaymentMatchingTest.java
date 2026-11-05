package com.cashflowai.detection;

import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.Payment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentMatchingTest {

    private final Invoice invoice = new Invoice("INV-109", "Harbor Supply", "ap@harbor.example",
            new BigDecimal("1500.00"), LocalDate.of(2026, 10, 20), InvoiceStatus.SENT);

    private Payment unmatched(String hint, String payer, String amount) {
        return new Payment("PAY-1", null, hint, new BigDecimal(amount), Instant.now()).withPayerEmail(payer);
    }

    @Test
    void exactReferenceMatches() {
        assertTrue(PaymentMatching.match(invoice, invoice.getAmount(), unmatched("INV-109", "x@y.example", "10")).isPresent());
    }

    @Test
    void typoInReferenceMatches() {
        assertTrue(PaymentMatching.match(invoice, invoice.getAmount(), unmatched("inv 1O9", "x@y.example", "10")).isPresent());
    }

    @Test
    void samePayerAndExactAmountMatches() {
        assertTrue(PaymentMatching.match(invoice, invoice.getAmount(),
                unmatched("their-own-ref", "AP@harbor.example", "1500.00")).isPresent());
    }

    @Test
    void otherPayerOtherAmountDoesNotMatch() {
        assertTrue(PaymentMatching.match(invoice, invoice.getAmount(),
                unmatched("INV-200", "ap@harbor.example", "700.00")).isEmpty());
    }
}
