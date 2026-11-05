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

        JsonNode created = http.post()
                .uri("/v2/invoicing/invoices")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .header("Prefer", "return=representation")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return created.path("id").asText();
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
                .body(JsonNode.class);
    }

    /**
     * Sets the PO number (detail.reference) and re-sends the invoice to the customer.
     * PayPal's update is a full replace, so we read the invoice, change one field and PUT it back.
     * TODO (Day 2): verify in the sandbox which read-only fields PayPal rejects on PUT.
     */
    public void updateReferenceAndResend(String paypalInvoiceId, String reference) {
        JsonNode current = getInvoice(paypalInvoiceId);
        ObjectNode updated = ((ObjectNode) current).deepCopy();
        updated.remove("links");
        ((ObjectNode) updated.path("detail")).put("reference", reference);

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
