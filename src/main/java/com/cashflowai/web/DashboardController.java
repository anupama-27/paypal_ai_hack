package com.cashflowai.web;

import com.cashflowai.agent.AgentOrchestrator;
import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.web.Views.DashboardView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@RestController
public class DashboardController {

    private static final Set<InvoiceStatus> CLOSED = EnumSet.of(InvoiceStatus.PAID, InvoiceStatus.CANCELLED,
            InvoiceStatus.DRAFT);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ArCaseRepository cases;
    private final AgentOrchestrator agent;

    public DashboardController(InvoiceRepository invoices, PaymentRepository payments,
                               ArCaseRepository cases, AgentOrchestrator agent) {
        this.invoices = invoices;
        this.payments = payments;
        this.cases = cases;
        this.agent = agent;
    }

    @GetMapping("/api/dashboard")
    public DashboardView summary() {
        LocalDate today = LocalDate.now();
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        for (Invoice i : invoices.findAll()) {
            if (CLOSED.contains(i.getStatus())) {
                continue;
            }
            BigDecimal open = BalanceCalculator.outstanding(i, payments.findByInvoiceId(i.getId()));
            outstanding = outstanding.add(open);
            if (BalanceCalculator.daysOverdue(i, today) > 0) {
                overdue = overdue.add(open);
            }
        }

        List<ArCase> all = cases.findAll();
        BigDecimal recovered = all.stream()
                .filter(c -> c.getStatus() == CaseStatus.RESOLVED)
                .map(c -> invoices.findById(c.getInvoiceId()).map(Invoice::getAmount).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DashboardView(outstanding, overdue, recovered,
                all.stream().filter(ArCase::isOpen).count(),
                all.stream().filter(c -> c.getStatus() == CaseStatus.RESOLVED).count(),
                all.stream().filter(c -> c.getStatus() == CaseStatus.WAITING_APPROVAL).count(),
                agent.brainName());
    }
}
