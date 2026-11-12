package com.cashflowai.web;

import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.PaymentPromise;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.PromiseStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.domain.TimelineOverride;
import com.cashflowai.domain.TimelineOverrideRepository;
import com.cashflowai.time.DemoClock;
import com.cashflowai.web.Views.TimelineEvent;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Feeds the Bryntum timeline (invoice due dates, promised payments, agent follow-ups) and saves edits made on it. */
@RestController
public class TimelineController {

    static final String DEFAULT_TIME = "09:00";
    private static final List<String> KINDS = List.of("INVOICE_DUE", "PROMISE_DUE", "FOLLOW_UP");

    private static final EnumSet<InvoiceStatus> OPEN = EnumSet.of(InvoiceStatus.SENT, InvoiceStatus.OVERDUE,
            InvoiceStatus.PARTIALLY_PAID);

    private final InvoiceRepository invoices;
    private final PaymentPromiseRepository promises;
    private final ArCaseRepository cases;
    private final TimelineOverrideRepository overrides;
    private final TenantContext tenant;
    private final DemoClock clock;

    public TimelineController(InvoiceRepository invoices, PaymentPromiseRepository promises,
                              ArCaseRepository cases, TimelineOverrideRepository overrides, TenantContext tenant,
                              DemoClock clock) {
        this.tenant = tenant;
        this.invoices = invoices;
        this.promises = promises;
        this.cases = cases;
        this.overrides = overrides;
        this.clock = clock;
    }

    @GetMapping("/api/timeline")
    public List<TimelineEvent> events() {
        List<Invoice> all = tenant.invoices();
        Map<Long, Invoice> byId = all.stream().collect(Collectors.toMap(Invoice::getId, Function.identity()));
        Map<String, TimelineOverride> edited = overrides.findAll().stream()
                .collect(Collectors.toMap(o -> o.getKind() + ":" + o.getSourceId(), Function.identity()));
        List<TimelineEvent> events = new ArrayList<>();

        for (Invoice i : all) {
            if (OPEN.contains(i.getStatus()) && i.getDueDate() != null) {
                events.add(event(edited, "INVOICE_DUE", i.getId(), i.getDueDate(),
                        i.getInvoiceNumber() + " due (" + i.getCustomerName() + ")",
                        i.getInvoiceNumber(), null, i.getAmount(), i.getStatus().name()));
            }
        }

        promises.findByStatus(PromiseStatus.PENDING).stream().filter(p -> byId.containsKey(p.getInvoiceId()))
                .forEach(p -> {
                    String number = byId.get(p.getInvoiceId()).getInvoiceNumber();
                    events.add(event(edited, "PROMISE_DUE", p.getId(), p.getDueDate(),
                            "Promised payment on " + number, number, null, p.getAmount(), p.getStatus().name()));
                });

        cases.findAll().stream()
                .filter(c -> c.isOpen() && c.getNextCheckAt() != null && byId.containsKey(c.getInvoiceId()))
                .forEach(c -> {
                    String number = byId.get(c.getInvoiceId()).getInvoiceNumber();
                    events.add(event(edited, "FOLLOW_UP", c.getId(), c.getNextCheckAt(),
                            "Agent follow-up on " + number, number, c.getId(), null, c.getStatus().name()));
                });

        return events;
    }

    /** Builds an event, using the owner's edited title and time when there is one. */
    private static TimelineEvent event(Map<String, TimelineOverride> edited, String kind, Long sourceId,
                                       LocalDate date, String label, String invoiceNumber, Long caseId,
                                       BigDecimal amount, String status) {
        TimelineOverride o = edited.get(kind + ":" + sourceId);
        String title = o != null && o.getLabel() != null && !o.getLabel().isBlank() ? o.getLabel() : label;
        String time = o != null && o.getEventTime() != null ? o.getEventTime().toString() : DEFAULT_TIME;
        return new TimelineEvent(kind, sourceId, date, title, invoiceNumber, caseId, amount, status, time);
    }

    /** Any field may be omitted; only what is sent is changed. time is HH:mm. */
    public record UpdateRequest(LocalDate date, String time, String label) {
    }

    /**
     * Saves an edit made on the calendar.
     * - Date goes to the source record (invoice due date, promise due date, or the case's next
     *   check), so the Cases and Invoices pages show the same date.
     * - Title and time of day are stored on the timeline itself.
     */
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PutMapping("/api/timeline/{kind}/{id}")
    public void update(@PathVariable String kind, @PathVariable Long id, @RequestBody UpdateRequest body) {
        if (!KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown event kind " + kind);
        }
        LocalTime time = parseTime(body.time());
        String label = body.label() == null ? null : body.label().trim();
        if (label != null && (label.isEmpty() || label.length() > 255)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required (max 255 characters)");
        }

        // Ownership is checked before anything is written, including title/time overrides.
        switch (kind) {
            case "INVOICE_DUE" -> {
                Invoice invoice = tenant.invoice(id);
                if (body.date() != null) {
                    invoice.setDueDate(body.date());
                    // A due date moved into the future clears a stale OVERDUE flag; detection re-flags it if needed.
                    if (invoice.getStatus() == InvoiceStatus.OVERDUE && !body.date().isBefore(clock.today())) {
                        invoice.setStatus(InvoiceStatus.SENT);
                    }
                }
            }
            case "PROMISE_DUE" -> {
                PaymentPromise promise = promises.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Promise not found"));
                tenant.invoice(promise.getInvoiceId());
                if (body.date() != null) {
                    promise.setDueDate(body.date());
                }
            }
            default -> {
                ArCase c = tenant.arCase(id);
                if (body.date() != null) {
                    c.setNextCheckAt(body.date());
                }
            }
        }

        if (label != null || time != null) {
            TimelineOverride o = overrides.findByKindAndSourceId(kind, id)
                    .orElseGet(() -> new TimelineOverride(kind, id));
            if (label != null) {
                o.setLabel(label);
            }
            if (time != null) {
                o.setEventTime(time);
            }
            overrides.save(o);
        }
    }

    private static LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "time must look like 14:30");
        }
    }
}
