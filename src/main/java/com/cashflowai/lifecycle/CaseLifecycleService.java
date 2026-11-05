package com.cashflowai.lifecycle;

import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.AgentAction;
import com.cashflowai.domain.AgentActionRepository;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.ActionStatus;
import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Enums.CaseType;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.PromiseStatus;
import com.cashflowai.domain.Enums.RootCause;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentPromise;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.policy.ActionType;
import com.cashflowai.time.DemoClock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Closes the loop after the agent acts: payments resolve cases, promises are kept or broken,
 * and cases waiting on the customer are re-opened when their follow-up date comes.
 */
@Service
public class CaseLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CaseLifecycleService.class);

    private final ArCaseRepository cases;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PaymentPromiseRepository promises;
    private final AgentActionRepository audit;
    private final DemoClock clock;
    private final ObjectMapper mapper;
    private final int followUpDays;

    public CaseLifecycleService(ArCaseRepository cases, InvoiceRepository invoices, PaymentRepository payments,
                                PaymentPromiseRepository promises, AgentActionRepository audit, DemoClock clock,
                                ObjectMapper mapper, @Value("${cashflow.followup.days:3}") int followUpDays) {
        this.cases = cases;
        this.invoices = invoices;
        this.payments = payments;
        this.promises = promises;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
        this.followUpDays = followUpDays;
    }

    /** The agent acted and now waits for the customer. */
    public void monitor(ArCase arCase) {
        monitorUntil(arCase, clock.today().plusDays(followUpDays));
    }

    public void monitorUntil(ArCase arCase, LocalDate nextCheck) {
        arCase.setStatus(CaseStatus.MONITORING);
        arCase.setNextCheckAt(nextCheck);
    }

    /** Recomputes the invoice after a payment was linked to it, then updates promises and cases. */
    @Transactional
    public void applyPayment(Invoice invoice, Payment payment, Actor source) {
        BigDecimal outstanding = BalanceCalculator.outstanding(invoice, payments.findByInvoiceId(invoice.getId()));
        if (invoice.getStatus() != InvoiceStatus.DISPUTED && invoice.getStatus() != InvoiceStatus.CANCELLED) {
            invoice.setStatus(outstanding.signum() == 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID);
        }
        invoices.save(invoice);

        List<PaymentPromise> pending = promises.findByInvoiceIdAndStatusOrderByDueDateAsc(invoice.getId(),
                PromiseStatus.PENDING);
        if (outstanding.signum() == 0) {
            pending.forEach(p -> p.setStatus(PromiseStatus.KEPT));
        } else if (!pending.isEmpty() && payment.getAmount().compareTo(pending.get(0).getAmount()) >= 0) {
            pending.get(0).setStatus(PromiseStatus.KEPT);
        }
        promises.saveAll(pending);

        for (ArCase c : cases.findByInvoiceId(invoice.getId())) {
            if (!c.isActive()) {
                continue;
            }
            log(c.getId(), source, "payment_received", ActionType.CHECK_PAYMENT,
                    Map.of("payment_id", String.valueOf(payment.getPaypalPaymentId()),
                            "amount", payment.getAmount().toPlainString()),
                    Map.of("outstanding_after", outstanding.toPlainString(),
                            "invoice_status", invoice.getStatus().name()),
                    "Payment confirmed by " + (source == Actor.PAYPAL ? "PayPal" : source.name()));
            if (outstanding.signum() == 0) {
                c.resolve(clock.now());
                cases.save(c);
            }
        }
    }

    /** Finds the active case for an invoice or opens a new one (customer requests, disputes). */
    @Transactional
    public ArCase activeCaseOrNew(Invoice invoice, CaseType type) {
        return cases.findByInvoiceId(invoice.getId()).stream()
                .filter(ArCase::isActive)
                .findFirst()
                .orElseGet(() -> cases.save(new ArCase(invoice.getId(), type)));
    }

    /** Disputed invoices are never chased: reminders are blocked and a human takes over. */
    @Transactional
    public ArCase markDisputed(Invoice invoice, String reason, Actor actor) {
        invoice.setStatus(InvoiceStatus.DISPUTED);
        invoices.save(invoice);
        ArCase c = activeCaseOrNew(invoice, CaseType.DISPUTE);
        c.setStatus(CaseStatus.ESCALATED);
        c.setNextCheckAt(null);
        c.setRootCause(RootCause.DISPUTED);
        c.setConfidence(1.0);
        c.setRecommendedAction("Owner reviews the dispute; reminders are paused");
        c.setExplanation(truncate("Dispute raised by " + actor.name().toLowerCase().replace('_', ' ') + ": " + reason));
        cases.save(c);
        log(c.getId(), actor, "raise_dispute", ActionType.RAISE_DISPUTE, Map.of("reason", reason),
                Map.of("status", "escalated"), "Disputes always go to a human");
        return c;
    }

    /** Something new happened (customer replied, follow-up due): hand the case back to the agent. */
    @Transactional
    public void reopenForAgent(ArCase arCase, String reason) {
        if (arCase.getStatus() == CaseStatus.MONITORING || arCase.getStatus() == CaseStatus.ACTION_EXECUTED) {
            arCase.setStatus(CaseStatus.DETECTED);
            arCase.setNextCheckAt(null);
            String prev = arCase.getExplanation() == null ? "" : arCase.getExplanation() + " ";
            arCase.setExplanation(truncate(prev + "[" + clock.today() + "] " + reason));
            cases.save(arCase);
        }
    }

    @Scheduled(fixedDelayString = "${cashflow.followup.interval-ms:60000}", initialDelay = 30000)
    public void scheduledFollowUps() {
        int reopened = runFollowUps();
        if (reopened > 0) {
            log.info("Follow-up: {} case(s) handed back to the agent", reopened);
        }
    }

    /** @return number of cases handed back to the agent */
    @Transactional
    public int runFollowUps() {
        LocalDate today = clock.today();
        int reopened = 0;
        for (ArCase c : cases.findByStatus(CaseStatus.MONITORING)) {
            Invoice invoice = invoices.findById(c.getInvoiceId()).orElseThrow();
            BigDecimal outstanding = BalanceCalculator.outstanding(invoice, payments.findByInvoiceId(invoice.getId()));
            if (outstanding.signum() == 0) {
                c.resolve(clock.now());
                cases.save(c);
                continue;
            }
            List<PaymentPromise> broken = promises.findByInvoiceIdAndStatusOrderByDueDateAsc(invoice.getId(),
                    PromiseStatus.PENDING).stream().filter(p -> p.getDueDate().isBefore(today)).toList();
            broken.forEach(p -> p.setStatus(PromiseStatus.BROKEN));
            promises.saveAll(broken);

            if (!broken.isEmpty()) {
                reopenForAgent(c, "Promise to pay " + broken.get(0).getAmount().toPlainString() + " by "
                        + broken.get(0).getDueDate() + " was not kept.");
                reopened++;
            } else if (c.getNextCheckAt() != null && !c.getNextCheckAt().isAfter(today)) {
                reopenForAgent(c, "Follow-up due: no payment since the last action.");
                reopened++;
            }
        }
        return reopened;
    }

    /** Audit entry for something that did not come from the LLM (customer, PayPal, owner, system). */
    public AgentAction log(Long caseId, Actor actor, String toolName, ActionType type,
                           Map<String, ?> input, Map<String, ?> output, String reason) {
        AgentAction a = new AgentAction(caseId, toolName, type, toJson(input)).by(actor);
        a.setStatus(ActionStatus.EXECUTED);
        a.setPolicyReason(truncate255(reason));
        a.setToolOutput(toJson(output));
        a.setExecutedAt(clock.now());
        return audit.save(a);
    }

    private String toJson(Object o) {
        try {
            return truncate(mapper.writeValueAsString(o));
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    private static String truncate(String s) {
        return s.length() <= 4000 ? s : s.substring(s.length() - 4000);
    }

    private static String truncate255(String s) {
        return s == null || s.length() <= 255 ? s : s.substring(0, 255);
    }
}
