package com.cashflowai.paypal;

import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.time.DemoClock;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Pulls the payments PayPal recorded on an invoice and links them locally.
 * PayPal is the source of truth for money; idempotent by PayPal transaction id.
 */
@Service
public class PaymentSyncService {

    private final PayPalClient paypal;
    private final PaymentRepository payments;
    private final CaseLifecycleService lifecycle;
    private final DemoClock clock;

    public PaymentSyncService(PayPalClient paypal, PaymentRepository payments,
                              CaseLifecycleService lifecycle, DemoClock clock) {
        this.paypal = paypal;
        this.payments = payments;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    /** @return number of new payments recorded */
    @Transactional
    public int syncFromPayPal(Invoice invoice) {
        if (!paypal.isEnabled() || invoice.getPaypalInvoiceId() == null) {
            return 0;
        }
        JsonNode remote = paypal.getInvoice(invoice.getPaypalInvoiceId());
        int added = 0;
        for (JsonNode txn : remote.path("payments").path("transactions")) {
            String txnId = txn.path("payment_id").asText(null);
            if (txnId == null || payments.findByPaypalPaymentId(txnId).isPresent()) {
                continue;
            }
            BigDecimal amount = new BigDecimal(txn.path("amount").path("value").asText("0"));
            // PayPal only gives a date; the time we observed it keeps ordering against case timestamps correct.
            Payment p = payments.save(new Payment(txnId, invoice.getId(), invoice.getInvoiceNumber(), amount,
                    clock.now()).withPayerEmail(invoice.getCustomerEmail()));
            lifecycle.applyPayment(invoice, p, Actor.PAYPAL);
            added++;
        }
        return added;
    }
}
