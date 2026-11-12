package com.cashflowai.paypal;

import com.cashflowai.domain.Invoice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Thin wrapper over the PayPal Invoicing v2 REST API (sandbox).
 * When paypal.enabled=false every call is skipped and logged, so the rest of
 * the app can be developed without credentials.
 */
@Component
public class PayPalClient {

    private static final Logger log = LoggerFactory.getLogger(PayPalClient.class);

    private final PayPalProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    private String cachedToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public PayPalClient(PayPalProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = RestClient.builder().baseUrl(props.baseUrl()).build();
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    /** Creates a draft invoice in PayPal and returns PayPal's invoice id (INV2-...). */
    public String createDraftInvoice(Invoice invoice, String recipientEmail) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode detail = body.putObject("detail");
        detail.put("currency_code", invoice.getCurrency());
        detail.put("invoice_number", invoice.getInvoiceNumber());
        if (invoice.getReference() != null) {
            detail.put("reference", invoice.getReference());
        }
        detail.put("note", "Sent by CashFlow AI");
        if (invoice.getDueDate() != null) {
            detail.putObject("payment_term").put("due_date", invoice.getDueDate().toString());
        }

        ArrayNode recipients = body.putArray("primary_recipients");
        recipients.addObject().putObject("billing_info").put("email_address", recipientEmail);

        ObjectNode item = body.putArray("items").addObject();
        item.put("name", "Services for " + invoice.getCustomerName());
        item.put("quantity", "1");
        ObjectNode unit = item.putObject("unit_amount");
        unit.put("currency_code", invoice.getCurrency());
        unit.put("value", invoice.getAmount().toPlainString());

        JsonNode created;
        try {
            created = postInvoice(body);
        } catch (org.springframework.web.client.HttpClientErrorException.UnprocessableEntity e) {
            if (!e.getResponseBodyAsString().contains("DUPLICATE_INVOICE_NUMBER")) {
                throw e;
            }
            // The number already exists in this PayPal account (e.g. from an earlier demo run).
            // PayPal's number is cosmetic: we match by PayPal invoice id, so retry with a suffix.
            String suffix = Long.toString(System.currentTimeMillis(), 36);
            detail.put("invoice_number", invoice.getInvoiceNumber() + "-" + suffix);
            created = postInvoice(body);
        }
        return created.path("id").asText();
    }

    private JsonNode postInvoice(ObjectNode body) {
        return http.post()
                .uri("/v2/invoicing/invoices")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header("Prefer", "return=representation")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    public void sendInvoice(String paypalInvoiceId) {
        http.post()
                .uri("/v2/invoicing/invoices/{id}/send", paypalInvoiceId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.createObjectNode().put("send_to_invoicer", true))
                .retrieve()
                .toBodilessEntity();
    }

    public void sendReminder(String paypalInvoiceId, String subject, String note) {
        ObjectNode body = mapper.createObjectNode();
        body.put("subject", subject);
        body.put("note", note);
        body.put("send_to_invoicer", false);
        http.post()
                .uri("/v2/invoicing/invoices/{id}/remind", paypalInvoiceId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    public JsonNode getInvoice(String paypalInvoiceId) {
        return http.get()
                .uri("/v2/invoicing/invoices/{id}", paypalInvoiceId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve()
                .body(ObjectNode.class);
    }

    /**
     * Sets the PO number (detail.reference) and re-sends the invoice to the customer.
     * TODO (Day 2): verify in the sandbox which read-only fields PayPal rejects on PUT.
     */
    public void updateReferenceAndResend(String paypalInvoiceId, String reference) {
        updateInvoice(paypalInvoiceId, invoice -> ((ObjectNode) invoice.path("detail")).put("reference", reference));
    }

    /** Lets the customer pay in parts on the PayPal invoice page, with a minimum first payment. */
    public void enablePartialPayment(String paypalInvoiceId, String currency, String minimumAmountDue) {
        updateInvoice(paypalInvoiceId, invoice -> {
            ObjectNode partial = child(child(invoice, "configuration"), "partial_payment");
            partial.put("allow_partial_payment", true);
            partial.putObject("minimum_amount_due").put("currency_code", currency).put("value", minimumAmountDue);
        });
    }

    private static ObjectNode child(ObjectNode parent, String name) {
        return parent.get(name) instanceof ObjectNode existing ? existing : parent.putObject(name);
    }

    /** Page where the customer views and pays the invoice. */
    public String payerUrl(String paypalInvoiceId) {
        return getInvoice(paypalInvoiceId).path("detail").path("metadata").path("recipient_view_url").asText(null);
    }

    /**
     * Asks PayPal whether a webhook really came from PayPal for our webhook id.
     * https://developer.paypal.com/docs/api/webhooks/v1/#verify-webhook-signature_post
     */
    public boolean verifyWebhookSignature(HttpHeaders headers, JsonNode event) {
        if (props.webhookId() == null || props.webhookId().isBlank()) {
            return false;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("auth_algo", headers.getFirst("PAYPAL-AUTH-ALGO"));
        body.put("cert_url", headers.getFirst("PAYPAL-CERT-URL"));
        body.put("transmission_id", headers.getFirst("PAYPAL-TRANSMISSION-ID"));
        body.put("transmission_sig", headers.getFirst("PAYPAL-TRANSMISSION-SIG"));
        body.put("transmission_time", headers.getFirst("PAYPAL-TRANSMISSION-TIME"));
        body.put("webhook_id", props.webhookId());
        body.set("webhook_event", event);
        JsonNode result = http.post()
                .uri("/v1/notifications/verify-webhook-signature")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return result != null && "SUCCESS".equals(result.path("verification_status").asText());
    }

    /** PayPal's update is a full replace, so read the invoice, change it, and PUT it back (notifying the customer). */
    private void updateInvoice(String paypalInvoiceId, Consumer<ObjectNode> change) {
        ObjectNode updated = getInvoice(paypalInvoiceId).deepCopy();
        updated.remove("links");
        change.accept(updated);

        http.put()
                .uri("/v2/invoicing/invoices/{id}?send_to_recipient=true", paypalInvoiceId)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(updated)
                .retrieve()
                .toBodilessEntity();
    }

    private synchronized String bearer() {
        if (!props.enabled()) {
            throw new IllegalStateException("PayPal is disabled (set PAYPAL_ENABLED=true)");
        }
        if (cachedToken == null || Instant.now().isAfter(tokenExpiresAt.minusSeconds(60))) {
            String basic = Base64.getEncoder().encodeToString(
                    (props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));
            JsonNode token = http.post()
                    .uri("/v1/oauth2/token")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .body(JsonNode.class);
            cachedToken = token.path("access_token").asText();
            tokenExpiresAt = Instant.now().plusSeconds(token.path("expires_in").asLong(300));
            log.info("Obtained PayPal sandbox access token");
        }
        return "Bearer " + cachedToken;
    }
}
