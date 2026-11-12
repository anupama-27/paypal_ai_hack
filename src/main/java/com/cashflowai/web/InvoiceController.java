package com.cashflowai.web;

import com.cashflowai.action.ActionService;
import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.paypal.InvoiceSyncService;
import com.cashflowai.paypal.PaymentSyncService;
import com.cashflowai.time.DemoClock;
import com.cashflowai.web.Views.InvoiceView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final CaseLifecycleService lifecycle;
    private final TenantContext tenant;
    private final DemoClock clock;

    public InvoiceController(InvoiceRepository invoices, PaymentRepository payments, InvoiceSyncService sync,
                             PaymentSyncService paymentSync, ActionService actions,
                             CaseLifecycleService lifecycle, TenantContext tenant, DemoClock clock) {
        this.tenant = tenant;
        this.invoices = invoices;
        this.payments = payments;
        this.sync = sync;
        this.paymentSync = paymentSync;
        this.actions = actions;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    @GetMapping
    public List<InvoiceView> list() {
        return tenant.invoices().stream().map(this::view).toList();
    }

    public record NewInvoice(String invoiceNumber, String customerName, String customerEmail,
                             BigDecimal amount, BigDecimal paidAmount, LocalDate dueDate, String purchaseOrder) {
    }

    /** Create an invoice in the books (status SENT). Push it to PayPal afterwards with paypal-sync. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody NewInvoice body) {
        if (body.invoiceNumber() == null || body.invoiceNumber().isBlank()
                || body.customerName() == null || body.customerName().isBlank()
                || body.customerEmail() == null || body.customerEmail().isBlank()
                || body.amount() == null || body.amount().signum() <= 0
                || body.dueDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "invoiceNumber, customerName, customerEmail, a positive amount and dueDate are required"));
        }
        if (!body.customerEmail().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "customerEmail is not a valid email address"));
        }
        if (body.paidAmount() != null && body.paidAmount().signum() > 0
                && body.paidAmount().compareTo(body.amount()) > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "paidAmount cannot exceed the invoice amount"));
        }
        Invoice invoice = new Invoice(body.invoiceNumber().trim(), body.customerName(), body.customerEmail(),
                body.amount(), body.dueDate(), com.cashflowai.domain.Enums.InvoiceStatus.SENT)
                .inTenant(tenant.tenantId());
        if (body.purchaseOrder() != null && !body.purchaseOrder().isBlank()) {
            invoice.setReference(body.purchaseOrder().trim());
        }
        try {
            Invoice saved = invoices.save(invoice);
            if (body.paidAmount() != null && body.paidAmount().signum() > 0) {
                Payment p = payments.save(new Payment("MANUAL-" + saved.getInvoiceNumber(), saved.getId(),
                        saved.getInvoiceNumber(), body.paidAmount(), clock.now())
                        .withPayerEmail(saved.getCustomerEmail()));
                lifecycle.applyPayment(saved, p, Actor.OWNER);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(view(invoices.findById(saved.getId()).orElseThrow()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "An invoice with this number already exists"));
        }
    }

    /** Creates and sends this invoice in the PayPal sandbox. */
    @PostMapping("/{id}/paypal-sync")
    public InvoiceView pushToPayPal(@PathVariable Long id) {
        tenant.invoice(id);
        return view(sync.pushToPayPal(id));
    }

    /** Pulls payments PayPal recorded on this invoice (same path the webhook uses). */
    @PostMapping("/{id}/paypal-refresh")
    public Map<String, Object> refreshFromPayPal(@PathVariable Long id) {
        Invoice invoice = tenant.invoice(id);
        int added = paymentSync.syncFromPayPal(invoice);
        return Map.of("newPayments", added, "invoice", view(invoices.findById(id).orElseThrow()));
    }

    InvoiceView view(Invoice i) {
        List<Payment> matched = payments.findByInvoiceId(i.getId());
        return new InvoiceView(i.getId(), i.getInvoiceNumber(), i.getCustomerName(), i.getCustomerEmail(), i.getAmount(),
                BalanceCalculator.totalPaid(matched), BalanceCalculator.outstanding(i, matched), i.getCurrency(),
                i.getDueDate(), BalanceCalculator.daysOverdue(i, clock.today()), i.getStatus().name(),
                i.getReference(), i.getPaypalInvoiceId(), i.getPaypalPayerUrl(), actions.portalLink(i));
    }
}
