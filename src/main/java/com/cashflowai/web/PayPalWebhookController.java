package com.cashflowai.web;

import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Receives PayPal webhooks. On INVOICING.INVOICE.PAID we record the payment as an
 * UNMATCHED payment; detection + the agent then reconcile it (the self-healing loop).
 *
 * TODO (before demo): verify the webhook signature with PayPal's
 * /v1/notifications/verify-webhook-signature endpoint, and confirm the payload shape
 * against a real sandbox event (log it first).
 */
@RestController
public class PayPalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookController.class);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;

    public PayPalWebhookController(InvoiceRepository invoices, PaymentRepository payments) {
        this.invoices = invoices;
        this.payments = payments;
    }

    @PostMapping("/api/paypal/webhooks")
    public ResponseEntity<Void> receive(@RequestBody JsonNode event) {
        String type = event.path("event_type").asText();
        log.info("PayPal webhook {}: {}", type, event);

        if ("INVOICING.INVOICE.PAID".equals(type)) {
            JsonNode inv = event.path("resource").has("invoice")
                    ? event.path("resource").path("invoice") : event.path("resource");
            String paypalId = inv.path("id").asText();
            Optional<Invoice> local = invoices.findByPaypalInvoiceId(paypalId);
            String amount = inv.path("payments").path("paid_amount").path("value").asText(
                    inv.path("amount").path("value").asText("0"));
            String txnId = inv.path("payments").path("transactions").path(0).path("payment_id").asText(paypalId);

            payments.save(new Payment(txnId, null,
                    local.map(Invoice::getInvoiceNumber).orElse(inv.path("detail").path("invoice_number").asText()),
                    new BigDecimal(amount), Instant.now()));
        }
        return ResponseEntity.ok().build();
    }
}
