package com.cashflowai.action;

import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.paypal.PayPalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public ActionService(InvoiceRepository invoices, PaymentRepository payments, PayPalClient paypal) {
        this.invoices = invoices;
        this.payments = payments;
        this.paypal = paypal;
    }

    public Map<String, Object> sendReminder(Invoice invoice, String note) {
        String subject = "Reminder: invoice " + invoice.getInvoiceNumber();
        String channel = callPayPal(invoice, () -> paypal.sendReminder(invoice.getPaypalInvoiceId(), subject, note));
        return result("reminder_sent", channel, "note", note);
    }

    public Map<String, Object> resendInvoice(Invoice invoice) {
        String channel = callPayPal(invoice, () -> paypal.sendReminder(invoice.getPaypalInvoiceId(),
                "Invoice " + invoice.getInvoiceNumber(), "Resending your invoice for convenience."));
        return result("invoice_resent", channel);
    }

    @Transactional
    public Map<String, Object> updatePurchaseOrder(Invoice invoice, String purchaseOrder) {
        invoice.setReference(purchaseOrder);
        invoices.save(invoice);
        String channel = callPayPal(invoice,
                () -> paypal.updateReferenceAndResend(invoice.getPaypalInvoiceId(), purchaseOrder));
        return result("purchase_order_updated_and_resent", channel, "purchase_order", purchaseOrder);
    }

    /** Links a PayPal payment to the invoice and recomputes the status in Java. */
    @Transactional
    public Map<String, Object> reconcilePayment(Invoice invoice, Payment payment) {
        payment.setInvoiceId(invoice.getId());
        payments.save(payment);

        BigDecimal outstanding = BalanceCalculator.outstanding(invoice, payments.findByInvoiceId(invoice.getId()));
        invoice.setStatus(outstanding.signum() == 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID);
        invoices.save(invoice);

        return result("payment_reconciled", "internal_ledger",
                "payment_id", payment.getId(),
                "outstanding_after", outstanding.toPlainString(),
                "invoice_status", invoice.getStatus().name());
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
