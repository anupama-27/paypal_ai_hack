package com.cashflowai.web;

import com.cashflowai.action.ActionService;
import com.cashflowai.auth.AuthService;
import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.PromiseStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentPromise;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.web.Views.TimelineEvent;
import com.cashflowai.domain.Tenant;
import com.cashflowai.domain.TenantRepository;
import com.cashflowai.time.DemoClock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The signed-in customer's own view: every invoice billed to their (Google-verified) email,
 * across sellers, each with its Fix & Pay link. Customers see no other tenant data.
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    /** One billed invoice, who billed it, and where the customer can act on it. */
    public record CustomerInvoice(Long id, String invoiceNumber, String seller, BigDecimal amount, BigDecimal paid,
                                  BigDecimal outstanding, String currency, LocalDate dueDate, long daysOverdue,
                                  String status, String purchaseOrder, String payUrl, String fixAndPayLink) {
    }

    private static final EnumSet<InvoiceStatus> OPEN = EnumSet.of(InvoiceStatus.SENT, InvoiceStatus.OVERDUE,
            InvoiceStatus.PARTIALLY_PAID);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PaymentPromiseRepository promises;
    private final ActionService actions;
    private final TenantContext tenant;
    private final TenantRepository tenants;
    private final DemoClock clock;

    public CustomerController(InvoiceRepository invoices, PaymentRepository payments,
                              PaymentPromiseRepository promises, ActionService actions,
                              TenantContext tenant, TenantRepository tenants, DemoClock clock) {
        this.promises = promises;
        this.invoices = invoices;
        this.payments = payments;
        this.actions = actions;
        this.tenant = tenant;
        this.tenants = tenants;
        this.clock = clock;
    }

    /** Invoices billed to the signed-in customer's email, soonest-due first. */
    @GetMapping("/invoices")
    public List<CustomerInvoice> myInvoices() {
        Map<Long, String> sellers = sellers();
        return mine().stream().map(i -> {
            List<Payment> matched = payments.findByInvoiceId(i.getId());
            return new CustomerInvoice(i.getId(), i.getInvoiceNumber(), sellers.getOrDefault(i.getTenantId(), ""),
                    i.getAmount(), BalanceCalculator.totalPaid(matched), BalanceCalculator.outstanding(i, matched),
                    i.getCurrency(), i.getDueDate(), BalanceCalculator.daysOverdue(i, clock.today()),
                    i.getStatus().name(), i.getReference(), i.getPaypalPayerUrl(), actions.portalLink(i));
        }).toList();
    }

    /**
     * The customer's calendar: due dates of their open invoices and payments they promised.
     * Seller-internal items (agent follow-ups, the seller's own titles) are not shown.
     */
    @GetMapping("/timeline")
    public List<TimelineEvent> myTimeline() {
        Map<Long, String> sellers = sellers();
        List<TimelineEvent> events = new ArrayList<>();
        for (Invoice i : mine()) {
            String seller = sellers.getOrDefault(i.getTenantId(), "");
            if (OPEN.contains(i.getStatus()) && i.getDueDate() != null) {
                events.add(new TimelineEvent("INVOICE_DUE", i.getId(), i.getDueDate(),
                        i.getInvoiceNumber() + " due to " + seller, i.getInvoiceNumber(), null, i.getAmount(),
                        i.getStatus().name(), TimelineController.DEFAULT_TIME));
            }
            for (PaymentPromise p : promises.findByInvoiceIdAndStatusOrderByDueDateAsc(i.getId(), PromiseStatus.PENDING)) {
                events.add(new TimelineEvent("PROMISE_DUE", p.getId(), p.getDueDate(),
                        "You promised to pay " + i.getInvoiceNumber(), i.getInvoiceNumber(), null, p.getAmount(),
                        p.getStatus().name(), TimelineController.DEFAULT_TIME));
            }
        }
        return events;
    }

    private List<Invoice> mine() {
        AuthService.User u = tenant.user().filter(AuthService.User::customer).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in as a customer to see this"));
        List<Invoice> mine = new ArrayList<>(invoices.findByCustomerEmailIgnoreCase(u.email()));
        mine.sort(Comparator.comparing(Invoice::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return mine;
    }

    private Map<Long, String> sellers() {
        return tenants.findAll().stream().collect(Collectors.toMap(Tenant::getId, Tenant::getName));
    }
}
