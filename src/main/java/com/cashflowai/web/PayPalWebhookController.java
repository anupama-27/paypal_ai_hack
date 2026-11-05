package com.cashflowai.web;

import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.domain.WebhookEvent;
import com.cashflowai.domain.WebhookEventRepository;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.paypal.PayPalClient;
import com.cashflowai.paypal.PaymentSyncService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Receives PayPal webhooks. Every event is signature-verified with PayPal and processed once.
 * Invoice events pull the authoritative payment list from PayPal, which links the payment,
 * keeps promises and resolves the open case. Disputes pause reminders and escalate.
 */
@RestController
public class PayPalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookController.class);

    private final PayPalClient paypal;
    private final ObjectMapper mapper;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final WebhookEventRepository events;
    private final PaymentSyncService sync;
    private final CaseLifecycleService lifecycle;

    public PayPalWebhookController(PayPalClient paypal, ObjectMapper mapper, InvoiceRepository invoices,
                                   PaymentRepository payments, WebhookEventRepository events,
                                   PaymentSyncService sync, CaseLifecycleService lifecycle) {
        this.paypal = paypal;
        this.mapper = mapper;
        this.invoices = invoices;
        this.payments = payments;
        this.events = events;
        this.sync = sync;
        this.lifecycle = lifecycle;
    }

    @PostMapping("/api/paypal/webhooks")
    public ResponseEntity<String> receive(@RequestHeader HttpHeaders headers, @RequestBody String rawBody) {
        if (!paypal.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("PayPal integration is disabled");
        }
        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid JSON");
        }
        if (!paypal.verifyWebhookSignature(headers, event)) {
            log.warn("Rejected PayPal webhook with invalid signature (or PAYPAL_WEBHOOK_ID not set)");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Signature verification failed");
        }

        String eventId = event.path("id").asText();
        String type = event.path("event_type").asText();
        JsonNode resource = event.path("resource");
        if (eventId.isBlank() || events.existsByPaypalEventId(eventId)) {
            return ResponseEntity.ok("duplicate");
        }
        events.save(new WebhookEvent(eventId, type, resource.path("id").asText(null)));
        log.info("PayPal webhook {} {}", type, eventId);

        switch (type) {
            case "INVOICING.INVOICE.PAID", "INVOICING.INVOICE.UPDATED" -> {
                JsonNode inv = resource.has("invoice") ? resource.path("invoice") : resource;
                invoices.findByPaypalInvoiceId(inv.path("id").asText())
                        .ifPresentOrElse(sync::syncFromPayPal,
                                () -> log.info("Webhook for an invoice not managed here: {}", inv.path("id").asText()));
            }
            case "CUSTOMER.DISPUTE.CREATED" -> onDispute(resource);
            default -> log.debug("Ignoring webhook type {}", type);
        }
        return ResponseEntity.ok("processed");
    }

    private void onDispute(JsonNode dispute) {
        for (JsonNode txn : dispute.path("disputed_transactions")) {
            Optional<Invoice> invoice = payments.findByPaypalPaymentId(txn.path("seller_transaction_id").asText())
                    .filter(p -> p.getInvoiceId() != null)
                    .flatMap(p -> invoices.findById(p.getInvoiceId()));
            invoice.ifPresent(i -> lifecycle.markDisputed(i,
                    "PayPal dispute " + dispute.path("dispute_id").asText() + " (" + dispute.path("reason").asText() + ")",
                    Actor.PAYPAL));
        }
    }
}
