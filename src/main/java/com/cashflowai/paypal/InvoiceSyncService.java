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
        if (props.demoBuyerEmail() == null || props.demoBuyerEmail().isBlank()) {
            throw new IllegalStateException("Set PAYPAL_DEMO_BUYER_EMAIL to your sandbox personal account email");
        }
        // All demo invoices go to the sandbox buyer so you can open and pay them.
        String paypalId = paypal.createDraftInvoice(invoice, props.demoBuyerEmail());
        paypal.sendInvoice(paypalId);
        invoice.setPaypalInvoiceId(paypalId);
        invoice.setPaypalPayerUrl(paypal.payerUrl(paypalId));
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            invoice.setStatus(InvoiceStatus.SENT);
        }
        return invoices.save(invoice);
    }
}
