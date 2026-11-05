package com.cashflowai.detection;

import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.CaseType;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.PromiseStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentPromise;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.time.DemoClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Scans all invoices and opens an AR case for each new exception. */
@Service
public class ExceptionDetectionService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ArCaseRepository cases;
    private final PaymentMatcher matcher;
    private final PaymentPromiseRepository promises;
    private final DemoClock clock;

    public ExceptionDetectionService(InvoiceRepository invoices, PaymentRepository payments,
                                     ArCaseRepository cases, PaymentMatcher matcher,
                                     PaymentPromiseRepository promises, DemoClock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.cases = cases;
        this.matcher = matcher;
        this.promises = promises;
        this.clock = clock;
    }

    @Transactional
    public List<ArCase> detect() {
        LocalDate today = clock.today();
        List<ArCase> opened = new ArrayList<>();

        for (Invoice invoice : invoices.findAll()) {
            // Escalated cases stay with the human until resolved, so they are not re-detected.
            boolean hasActiveCase = cases.findByInvoiceId(invoice.getId()).stream().anyMatch(ArCase::isActive);
            if (hasActiveCase) {
                continue;
            }

            List<Payment> matched = payments.findByInvoiceId(invoice.getId());
            List<Payment> candidates = matcher.candidates(invoice).stream().map(PaymentMatcher.Candidate::payment).toList();
            List<PaymentPromise> pending = promises.findByInvoiceIdAndStatusOrderByDueDateAsc(
                    invoice.getId(), PromiseStatus.PENDING);

            Optional<CaseType> type = DetectionRules.classify(invoice, matched, candidates, pending, today);
            if (type.isPresent()) {
                if (type.get() == CaseType.OVERDUE_INVOICE && invoice.getStatus() == InvoiceStatus.SENT) {
                    invoice.setStatus(InvoiceStatus.OVERDUE);
                }
                if (type.get() == CaseType.BROKEN_PROMISE) {
                    pending.stream().filter(p -> p.getDueDate().isBefore(today))
                            .forEach(p -> p.setStatus(PromiseStatus.BROKEN));
                }
                opened.add(cases.save(new ArCase(invoice.getId(), type.get())));
            }
        }
        return opened;
    }
}
