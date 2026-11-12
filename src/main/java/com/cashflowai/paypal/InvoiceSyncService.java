package com.cashflowai.paypal;

import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pushes a local invoice to the PayPal sandbox (create draft + send). */
@Service
public class InvoiceSyncService {

    private final PayPalClient paypal;
    private final PayPalProperties props;
    private final InvoiceRepository invoices;

    public InvoiceSyncService(PayPalClient paypal, PayPalProperties props, InvoiceRepository invoices) {
        this.paypal = paypal;
        this.props = props;
        this.invoices = invoices;
    }

    @Transactional
    public Invoice pushToPayPal(Long invoiceId) {
        Invoice invoice = invoices.findById(invoiceId).orElseThrow();
        if (invoice.getPaypalInvoiceId() != null) {
            return invoice;
        }
        // PayPal emails the invoice to the customer billed on it; the demo buyer is only a fallback.
        String recipient = firstNonBlank(invoice.getCustomerEmail(), props.demoBuyerEmail());
        if (recipient == null) {
            throw new IllegalStateException("Invoice " + invoice.getInvoiceNumber()
                    + " has no customer email (or set PAYPAL_DEMO_BUYER_EMAIL as a fallback)");
        }
        String paypalId = paypal.createDraftInvoice(invoice, recipient);
        paypal.sendInvoice(paypalId);
        invoice.setPaypalInvoiceId(paypalId);
        invoice.setPaypalPayerUrl(paypal.payerUrl(paypalId));
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            invoice.setStatus(InvoiceStatus.SENT);
        }
        return invoices.save(invoice);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
