package com.cashflowai.action;

import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.paypal.PayPalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic side effects. These run only after the policy engine allowed them
 * (or a human approved them). The AI never calls this class directly.
 */
@Service
public class ActionService {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PayPalClient paypal;
    private final CaseLifecycleService lifecycle;
    private final String appBaseUrl;

    public ActionService(InvoiceRepository invoices, PaymentRepository payments, PayPalClient paypal,
                         CaseLifecycleService lifecycle, @Value("${cashflow.app-base-url}") String appBaseUrl) {
        this.invoices = invoices;
        this.payments = payments;
        this.paypal = paypal;
        this.lifecycle = lifecycle;
        this.appBaseUrl = appBaseUrl;
    }

    public String portalLink(Invoice invoice) {
        return appBaseUrl + "/pay/" + invoice.getPortalToken();
    }

    public Map<String, Object> sendReminder(Invoice invoice, String note) {
        String subject = "Reminder: invoice " + invoice.getInvoiceNumber();
        String fullNote = note + "\n\nNeed to fix something on this invoice or pay in parts? " + portalLink(invoice);
        String channel = callPayPal(invoice, () -> paypal.sendReminder(invoice.getPaypalInvoiceId(), subject, fullNote));
        return result("reminder_sent", channel, "note", note, "fix_and_pay_link", portalLink(invoice));
    }

    /** PayPal only allows "send" on drafts, so a resend of a sent invoice goes out as a reminder with the invoice link. */
    public Map<String, Object> resendInvoice(Invoice invoice) {
        String channel = callPayPal(invoice, () -> paypal.sendReminder(invoice.getPaypalInvoiceId(),
                "Invoice " + invoice.getInvoiceNumber(), "Here is your invoice again for convenience. "
                        + "Fix details or pay in parts: " + portalLink(invoice)));
        return result("invoice_link_resent", channel);
    }

    @Transactional
    public Map<String, Object> updatePurchaseOrder(Invoice invoice, String purchaseOrder) {
        invoice.setReference(purchaseOrder);
        invoices.save(invoice);
        String channel = callPayPal(invoice,
                () -> paypal.updateReferenceAndResend(invoice.getPaypalInvoiceId(), purchaseOrder));
        return result("purchase_order_updated_and_resent", channel, "purchase_order", purchaseOrder);
    }

    /** Links a PayPal payment to the invoice; the lifecycle service recomputes status and closes cases. */
    @Transactional
    public Map<String, Object> reconcilePayment(Invoice invoice, Payment payment, Actor actor) {
        payment.setInvoiceId(invoice.getId());
        payments.save(payment);
        lifecycle.applyPayment(invoice, payment, actor);

        BigDecimal outstanding = BalanceCalculator.outstanding(invoice, payments.findByInvoiceId(invoice.getId()));
        return result("payment_reconciled", "internal_ledger",
                "payment_id", payment.getId(),
                "outstanding_after", outstanding.toPlainString(),
                "invoice_status", invoice.getStatus().name());
    }

    /**
     * Turns on PayPal partial payments so the customer can pay the first installment on the PayPal invoice page.
     * Best effort: PayPal does not offer partial payments in every country, and the agreed plan stands either way.
     */
    public String enablePartialPayment(Invoice invoice, BigDecimal minimumFirstPayment) {
        try {
            return callPayPal(invoice, () -> paypal.enablePartialPayment(invoice.getPaypalInvoiceId(),
                    invoice.getCurrency(), minimumFirstPayment.toPlainString()));
        } catch (HttpClientErrorException e) {
            log.warn("PayPal refused partial payments for {}: {}", invoice.getInvoiceNumber(),
                    e.getResponseBodyAsString());
            return "unavailable_on_paypal";
        }
    }

    /** Returns "paypal_sandbox" if the real API was called, otherwise "simulated". */
    private String callPayPal(Invoice invoice, Runnable call) {
        if (paypal.isEnabled() && invoice.getPaypalInvoiceId() != null) {
            call.run();
            return "paypal_sandbox";
        }
        log.info("PayPal call simulated for {} (PayPal disabled or invoice not synced)", invoice.getInvoiceNumber());
        return "simulated";
    }

    private static Map<String, Object> result(String status, String channel, Object... extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("channel", channel);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            out.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return out;
    }
}
