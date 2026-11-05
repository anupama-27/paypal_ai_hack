package com.cashflowai.web;

import com.cashflowai.action.ActionService;
import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.paypal.InvoiceSyncService;
import com.cashflowai.paypal.PaymentSyncService;
import com.cashflowai.time.DemoClock;
import com.cashflowai.web.Views.InvoiceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final InvoiceSyncService sync;
    private final PaymentSyncService paymentSync;
    private final ActionService actions;
    private final DemoClock clock;

    public InvoiceController(InvoiceRepository invoices, PaymentRepository payments, InvoiceSyncService sync,
                             PaymentSyncService paymentSync, ActionService actions, DemoClock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.sync = sync;
        this.paymentSync = paymentSync;
        this.actions = actions;
        this.clock = clock;
    }

    @GetMapping
    public List<InvoiceView> list() {
        return invoices.findAll().stream().map(this::view).toList();
    }

    /** Creates and sends this invoice in the PayPal sandbox. */
    @PostMapping("/{id}/paypal-sync")
    public InvoiceView pushToPayPal(@PathVariable Long id) {
        return view(sync.pushToPayPal(id));
    }

    /** Pulls payments PayPal recorded on this invoice (same path the webhook uses). */
    @PostMapping("/{id}/paypal-refresh")
    public Map<String, Object> refreshFromPayPal(@PathVariable Long id) {
        Invoice invoice = invoices.findById(id).orElseThrow();
        int added = paymentSync.syncFromPayPal(invoice);
        return Map.of("newPayments", added, "invoice", view(invoices.findById(id).orElseThrow()));
    }

    InvoiceView view(Invoice i) {
        List<Payment> matched = payments.findByInvoiceId(i.getId());
        return new InvoiceView(i.getId(), i.getInvoiceNumber(), i.getCustomerName(), i.getAmount(),
                BalanceCalculator.totalPaid(matched), BalanceCalculator.outstanding(i, matched), i.getCurrency(),
                i.getDueDate(), BalanceCalculator.daysOverdue(i, clock.today()), i.getStatus().name(),
                i.getReference(), i.getPaypalInvoiceId(), i.getPaypalPayerUrl(), actions.portalLink(i));
    }
}
