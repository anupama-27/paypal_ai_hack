package com.cashflowai.web;

import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.PromiseStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.web.Views.TimelineEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Feeds the Bryntum timeline: invoice due dates, promised payments and agent follow-ups. */
@RestController
public class TimelineController {

    private static final EnumSet<InvoiceStatus> OPEN = EnumSet.of(InvoiceStatus.SENT, InvoiceStatus.OVERDUE,
            InvoiceStatus.PARTIALLY_PAID);

    private final InvoiceRepository invoices;
    private final PaymentPromiseRepository promises;
    private final ArCaseRepository cases;

    public TimelineController(InvoiceRepository invoices, PaymentPromiseRepository promises,
                              ArCaseRepository cases) {
        this.invoices = invoices;
        this.promises = promises;
        this.cases = cases;
    }

    @GetMapping("/api/timeline")
    public List<TimelineEvent> events() {
        List<Invoice> all = invoices.findAll();
        Map<Long, Invoice> byId = all.stream().collect(Collectors.toMap(Invoice::getId, Function.identity()));
        List<TimelineEvent> events = new ArrayList<>();

        for (Invoice i : all) {
            if (OPEN.contains(i.getStatus()) && i.getDueDate() != null) {
                events.add(new TimelineEvent("INVOICE_DUE", i.getDueDate(),
                        i.getInvoiceNumber() + " due (" + i.getCustomerName() + ")",
                        i.getInvoiceNumber(), null, i.getAmount(), i.getStatus().name()));
            }
        }

        promises.findByStatus(PromiseStatus.PENDING).forEach(p -> {
            Invoice i = byId.get(p.getInvoiceId());
            String number = i == null ? "#" + p.getInvoiceId() : i.getInvoiceNumber();
            events.add(new TimelineEvent("PROMISE_DUE", p.getDueDate(),
                    "Promised payment on " + number, number, null, p.getAmount(), p.getStatus().name()));
        });

        cases.findAll().stream().filter(c -> c.isOpen() && c.getNextCheckAt() != null).forEach(c -> {
            Invoice i = byId.get(c.getInvoiceId());
            events.add(new TimelineEvent("FOLLOW_UP", c.getNextCheckAt(),
                    "Agent follow-up on " + (i == null ? "#" + c.getInvoiceId() : i.getInvoiceNumber()),
                    i == null ? null : i.getInvoiceNumber(), c.getId(), null, c.getStatus().name()));
        });

        return events;
    }
}
