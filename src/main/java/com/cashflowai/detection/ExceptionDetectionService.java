package com.cashflowai.detection;

import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.CaseType;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
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

    public ExceptionDetectionService(InvoiceRepository invoices, PaymentRepository payments,
                                     ArCaseRepository cases) {
        this.invoices = invoices;
        this.payments = payments;
        this.cases = cases;
    }

    @Transactional
    public List<ArCase> detect() {
        LocalDate today = LocalDate.now();
        List<ArCase> opened = new ArrayList<>();

        for (Invoice invoice : invoices.findAll()) {
            boolean hasOpenCase = cases.findByInvoiceId(invoice.getId()).stream().anyMatch(ArCase::isOpen);
            if (hasOpenCase) {
                continue;
            }

            List<Payment> matched = payments.findByInvoiceId(invoice.getId());
            List<Payment> candidates = payments.findByInvoiceIdIsNullAndInvoiceNumberHint(invoice.getInvoiceNumber());

            Optional<CaseType> type = DetectionRules.classify(invoice, matched, candidates, today);
            if (type.isPresent()) {
                if (type.get() == CaseType.OVERDUE_INVOICE && invoice.getStatus() == InvoiceStatus.SENT) {
                    invoice.setStatus(InvoiceStatus.OVERDUE);
                }
                opened.add(cases.save(new ArCase(invoice.getId(), type.get())));
            }
        }
        return opened;
    }
}
