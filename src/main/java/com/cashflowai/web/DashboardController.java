package com.cashflowai.web;

import com.cashflowai.agent.AgentOrchestrator;
import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.AgentAction;
import com.cashflowai.domain.AgentActionRepository;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.ActionStatus;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.PaymentStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.paypal.PayPalClient;
import com.cashflowai.policy.ActionType;
import com.cashflowai.policy.AutonomyService;
import com.cashflowai.time.DemoClock;
import com.cashflowai.web.Views.DashboardView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class DashboardController {

    private static final Set<InvoiceStatus> CLOSED = EnumSet.of(InvoiceStatus.PAID, InvoiceStatus.CANCELLED,
            InvoiceStatus.DRAFT);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ArCaseRepository cases;
    private final AgentActionRepository audit;
    private final AgentOrchestrator agent;
    private final AutonomyService autonomy;
    private final PayPalClient paypal;
    private final DemoClock clock;
    private final ObjectMapper mapper;
    private final TenantContext tenant;

    public DashboardController(InvoiceRepository invoices, PaymentRepository payments, ArCaseRepository cases,
                               AgentActionRepository audit, AgentOrchestrator agent, AutonomyService autonomy,
                               PayPalClient paypal, DemoClock clock, ObjectMapper mapper, TenantContext tenant) {
        this.tenant = tenant;
        this.invoices = invoices;
        this.payments = payments;
        this.cases = cases;
        this.audit = audit;
        this.agent = agent;
        this.autonomy = autonomy;
        this.paypal = paypal;
        this.clock = clock;
        this.mapper = mapper;
    }

    @GetMapping("/api/dashboard")
    public DashboardView summary() {
        LocalDate today = clock.today();
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        List<Invoice> mine = tenant.invoices();
        Set<Long> mineIds = new HashSet<>();
        mine.forEach(i -> mineIds.add(i.getId()));
        for (Invoice i : mine) {
            if (CLOSED.contains(i.getStatus())) {
                continue;
            }
            BigDecimal open = BalanceCalculator.outstanding(i, payments.findByInvoiceId(i.getId()));
            outstanding = outstanding.add(open);
            if (BalanceCalculator.daysOverdue(i, today) > 0) {
                overdue = overdue.add(open);
            }
        }

        List<AgentAction> actions = tenant.actions(audit.findAll());
        // Money that was already in PayPal and only needed matching: reconciled, not "collected".
        Set<Long> reconciledIds = new HashSet<>();
        for (AgentAction a : actions) {
            if (a.getStatus() == ActionStatus.EXECUTED && a.getActionType() == ActionType.RECONCILE_PAYMENT) {
                long paymentId = readJson(a.getToolOutput()).path("payment_id").asLong(-1);
                if (paymentId > 0) {
                    reconciledIds.add(paymentId);
                }
            }
        }
        BigDecimal reconciled = payments.findAllById(reconciledIds).stream()
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Money that arrived after the agent opened a case on that invoice.
        List<ArCase> all = tenant.cases(cases.findAll());
        Map<Long, Instant> firstCaseAt = new HashMap<>();
        all.forEach(c -> firstCaseAt.merge(c.getInvoiceId(), c.getCreatedAt(), (a, b) -> a.isBefore(b) ? a : b));
        BigDecimal collected = BigDecimal.ZERO;
        for (Payment p : payments.findAll()) {
            if (p.getInvoiceId() == null || !mineIds.contains(p.getInvoiceId())) {
                continue;
            }
            Instant caseAt = firstCaseAt.get(p.getInvoiceId());
            if (caseAt != null && p.getPaidAt() != null && p.getPaidAt().isAfter(caseAt)
                    && p.getStatus() == PaymentStatus.COMPLETED && !reconciledIds.contains(p.getId())) {
                collected = collected.add(p.getAmount());
            }
        }

        long blocks = actions.stream().filter(a -> a.getStatus() == ActionStatus.BLOCKED).count();
        long suggestions = autonomy.all().stream().filter(AutonomyService.RuleView::suggestPromotion).count();
        return new DashboardView(outstanding, overdue, collected, reconciled,
                all.stream().filter(ArCase::isOpen).count(),
                count(all, CaseStatus.MONITORING),
                count(all, CaseStatus.WAITING_APPROVAL),
                count(all, CaseStatus.ESCALATED),
                count(all, CaseStatus.RESOLVED),
                blocks, suggestions, agent.brainName(), paypal.isEnabled(), today);
    }

    private static long count(List<ArCase> all, CaseStatus status) {
        return all.stream().filter(c -> c.getStatus() == status).count();
    }

    private JsonNode readJson(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
