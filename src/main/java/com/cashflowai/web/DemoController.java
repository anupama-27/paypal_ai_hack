package com.cashflowai.web;

import com.cashflowai.action.ActionService;
import com.cashflowai.agent.AgentOrchestrator;
import com.cashflowai.domain.CustomerAccount;
import com.cashflowai.domain.CustomerAccountRepository;
import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.paypal.InvoiceSyncService;
import com.cashflowai.paypal.PayPalClient;
import com.cashflowai.seed.DemoDataService;
import com.cashflowai.time.DemoClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo controls: reset data, move the business date forward, simulate a payment without PayPal,
 * and show the access keys / links needed to try the customer side. Disabled with CASHFLOW_DEMO_ENABLED=false.
 */
@RestController
@RequestMapping("/api/demo")
@ConditionalOnProperty(prefix = "cashflow.demo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoController {

    public record SimulatedPayment(String invoiceNumber, BigDecimal amount, String reference, String payerEmail,
                                   boolean paidOnInvoice) {
    }

    private final DemoDataService demo;
    private final DemoClock clock;
    private final CaseLifecycleService lifecycle;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final CustomerAccountRepository accounts;
    private final InvoiceSyncService sync;
    private final PayPalClient paypal;
    private final ActionService actions;
    private final AgentOrchestrator agent;
    private final TenantContext tenant;

    public DemoController(DemoDataService demo, DemoClock clock, CaseLifecycleService lifecycle,
                          InvoiceRepository invoices, PaymentRepository payments, CustomerAccountRepository accounts,
                          InvoiceSyncService sync, PayPalClient paypal, ActionService actions,
                          AgentOrchestrator agent, TenantContext tenant) {
        this.tenant = tenant;
        this.demo = demo;
        this.clock = clock;
        this.lifecycle = lifecycle;
        this.invoices = invoices;
        this.payments = payments;
        this.accounts = accounts;
        this.sync = sync;
        this.paypal = paypal;
        this.actions = actions;
        this.agent = agent;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("today", clock.today());
        s.put("offsetDays", clock.offsetDays());
        s.put("paypalEnabled", paypal.isEnabled());
        s.put("agentBrain", agent.brainName());
        List<Map<String, Object>> customers = new ArrayList<>();
        List<Invoice> mine = tenant.invoices();
        for (CustomerAccount a : accounts.findAll()) {
            List<Invoice> billed = mine.stream().filter(i -> a.getEmail().equalsIgnoreCase(i.getCustomerEmail()))
                    .toList();
            if (billed.isEmpty()) {
                continue;
            }
            customers.add(Map.of("name", a.getName(), "email", a.getEmail(), "accessKey", a.getAccessKey(),
                    "fixAndPayLinks", billed.stream()
                            .filter(i -> i.getStatus() != InvoiceStatus.PAID)
                            .map(i -> Map.of("invoice", i.getInvoiceNumber(), "link", actions.portalLink(i)))
                            .toList()));
        }
        s.put("customers", customers);
        return s;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        tenant.requireDefaultTenant();
        demo.reset();
        return state();
    }

    /** Moves the business date forward and runs the follow-up check, as if days had passed. */
    @PostMapping("/advance")
    public Map<String, Object> advance(@RequestParam(defaultValue = "3") int days) {
        if (days < 1 || days > 60) {
            throw new IllegalArgumentException("days must be between 1 and 60");
        }
        tenant.requireDefaultTenant();
        clock.advance(days);
        int reopened = lifecycle.runFollowUps();
        Map<String, Object> s = state();
        s.put("followUpsReopened", reopened);
        return s;
    }

    /** Publishes every open demo invoice to the PayPal sandbox (needs PAYPAL_ENABLED and a sandbox buyer email). */
    @PostMapping("/publish-to-paypal")
    public Map<String, Object> publish() {
        int published = 0;
        for (Invoice i : tenant.invoices()) {
            if (i.getPaypalInvoiceId() == null && i.getStatus() != InvoiceStatus.PAID) {
                sync.pushToPayPal(i.getId());
                published++;
            }
        }
        return Map.of("published", published);
    }

    /**
     * Stands in for PayPal when it is not configured. paidOnInvoice=true behaves like the
     * INVOICING.INVOICE.PAID webhook; false creates an unmatched payment for the agent to find.
     */
    @PostMapping("/payments")
    public Map<String, Object> simulatePayment(@RequestBody SimulatedPayment req) {
        Invoice invoice = tenant.invoiceByNumber(req.invoiceNumber());
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        String txnId = "SIM-" + System.currentTimeMillis();
        String payer = req.payerEmail() == null || req.payerEmail().isBlank() ? invoice.getCustomerEmail() : req.payerEmail();
        Payment p = new Payment(txnId, req.paidOnInvoice() ? invoice.getId() : null,
                req.reference() == null ? invoice.getInvoiceNumber() : req.reference(), req.amount(), clock.now())
                .withPayerEmail(payer);
        payments.save(p);
        if (req.paidOnInvoice()) {
            lifecycle.applyPayment(invoice, p, Actor.SYSTEM);
        }
        return Map.of("paymentId", txnId, "linked", req.paidOnInvoice());
    }
}
