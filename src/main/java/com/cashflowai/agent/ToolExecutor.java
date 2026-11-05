package com.cashflowai.agent;

import com.cashflowai.action.ActionService;
import com.cashflowai.agent.AgentTools.ToolSpec;
import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.detection.PaymentMatcher;
import com.cashflowai.domain.AgentAction;
import com.cashflowai.domain.AgentActionRepository;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.ActionStatus;
import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.RootCause;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceMessageRepository;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.policy.ActionType;
import com.cashflowai.policy.AutonomyService;
import com.cashflowai.policy.PolicyEngine;
import com.cashflowai.policy.PolicyEngine.Decision;
import com.cashflowai.policy.PolicyEngine.PaidCheck;
import com.cashflowai.time.DemoClock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a tool call from the agent: policy check first, audit log always,
 * then the deterministic action.
 */
@Service
public class ToolExecutor {

    /** Result handed back to the LLM. pendingApproval = the agent should stop and wait. */
    public record ToolResult(Map<String, Object> output, boolean pendingApproval) {
    }

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final AgentTools tools;
    private final PolicyEngine policy;
    private final ActionService actions;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ArCaseRepository cases;
    private final AgentActionRepository audit;
    private final ObjectMapper mapper;
    private final PaymentMatcher matcher;
    private final AutonomyService autonomy;
    private final CaseLifecycleService lifecycle;
    private final InvoiceMessageRepository messages;
    private final PaymentPromiseRepository promises;
    private final DemoClock clock;

    public ToolExecutor(AgentTools tools, PolicyEngine policy, ActionService actions,
                        InvoiceRepository invoices, PaymentRepository payments,
                        ArCaseRepository cases, AgentActionRepository audit, ObjectMapper mapper,
                        PaymentMatcher matcher, AutonomyService autonomy, CaseLifecycleService lifecycle,
                        InvoiceMessageRepository messages, PaymentPromiseRepository promises, DemoClock clock) {
        this.tools = tools;
        this.policy = policy;
        this.actions = actions;
        this.invoices = invoices;
        this.payments = payments;
        this.cases = cases;
        this.audit = audit;
        this.mapper = mapper;
        this.matcher = matcher;
        this.autonomy = autonomy;
        this.lifecycle = lifecycle;
        this.messages = messages;
        this.promises = promises;
        this.clock = clock;
    }

    @Transactional
    public ToolResult execute(Long caseId, String toolName, JsonNode input) {
        ArCase arCase = cases.findById(caseId).orElseThrow();
        ToolSpec spec = tools.find(toolName).orElse(null);
        if (spec == null) {
            return new ToolResult(Map.of("error", "Unknown tool " + toolName), false);
        }

        Invoice invoice = invoices.findById(arCase.getInvoiceId()).orElseThrow();
        AgentAction action = new AgentAction(caseId, toolName, spec.actionType(), toJson(input));

        Decision decision;
        try {
            decision = policy.evaluate(spec.actionType(), policyContext(caseId, invoice, spec.actionType(), input));
        } catch (IllegalArgumentException e) {
            decision = new Decision(PolicyEngine.Outcome.DENY, e.getMessage());
        }
        action.setPolicyReason(decision.reason());

        switch (decision.outcome()) {
            case DENY -> {
                action.setStatus(ActionStatus.BLOCKED);
                Map<String, Object> out = Map.of("blocked", true, "reason", decision.reason());
                action.setToolOutput(toJson(out));
                audit.save(action);
                return new ToolResult(out, false);
            }
            case NEEDS_APPROVAL -> {
                action.setStatus(ActionStatus.PENDING_APPROVAL);
                action.setRequiresApproval(true);
                arCase.setStatus(CaseStatus.WAITING_APPROVAL);
                AgentAction saved = audit.save(action);
                return new ToolResult(Map.of(
                        "pending_approval", true,
                        "action_id", saved.getId(),
                        "reason", decision.reason()), true);
            }
            default -> {
                Map<String, Object> out;
                try {
                    out = run(arCase, invoice, toolName, input);
                    action.setStatus(ActionStatus.EXECUTED);
                } catch (RuntimeException e) {
                    log.warn("[case {}] {} failed: {}", caseId, toolName, e.getMessage());
                    out = Map.of("error", "Action failed: " + e.getMessage(), "hint", "Escalate to a human");
                    action.setStatus(ActionStatus.FAILED);
                }
                action.setExecutedAt(clock.now());
                action.setToolOutput(toJson(out));
                audit.save(action);
                return new ToolResult(out, false);
            }
        }
    }

    /** Called when a human approves a pending action from the dashboard. */
    @Transactional
    public AgentAction approve(Long actionId, String approver) {
        AgentAction action = audit.findById(actionId).orElseThrow();
        if (action.getStatus() != ActionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Action " + actionId + " is not waiting for approval");
        }
        ArCase arCase = cases.findById(action.getCaseId()).orElseThrow();
        Invoice invoice = invoices.findById(arCase.getInvoiceId()).orElseThrow();
        Map<String, Object> out = run(arCase, invoice, action.getToolName(), readJson(action.getToolInput()));
        action.setStatus(ActionStatus.EXECUTED);
        action.setApprovedBy(approver);
        action.setExecutedAt(clock.now());
        action.setToolOutput(toJson(out));
        autonomy.recordApproval(action.getActionType());
        return audit.save(action);
    }

    @Transactional
    public AgentAction reject(Long actionId, String approver) {
        AgentAction action = audit.findById(actionId).orElseThrow();
        if (action.getStatus() != ActionStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Action " + actionId + " is not waiting for approval");
        }
        action.setStatus(ActionStatus.REJECTED);
        action.setApprovedBy(approver);
        ArCase arCase = cases.findById(action.getCaseId()).orElseThrow();
        arCase.setStatus(CaseStatus.ESCALATED);
        autonomy.recordRejection(action.getActionType());
        return audit.save(action);
    }

    private PolicyEngine.Context policyContext(Long caseId, Invoice invoice, ActionType type, JsonNode input) {
        long days = BalanceCalculator.daysOverdue(invoice, clock.today());
        PolicyEngine.Context ctx = PolicyEngine.Context.of(days);
        if (type == ActionType.RECONCILE_PAYMENT) {
            Payment payment = candidatePayment(invoice, input);
            BigDecimal outstanding = BalanceCalculator.outstanding(invoice, payments.findByInvoiceId(invoice.getId()));
            ctx = new PolicyEngine.Context(days, outstanding, payment.getAmount());
        }
        return ctx.withPaidCheck(paidCheck(caseId))
                .withDisputed(invoice.getStatus() == InvoiceStatus.DISPUTED)
                .withAutonomy(autonomy.isGranted(type));
    }

    /** Paid-check guard: what did the latest find_potential_payment in this case return? */
    private PaidCheck paidCheck(Long caseId) {
        return audit.findByCaseIdAndToolNameAndStatusOrderByCreatedAtDesc(caseId, AgentTools.FIND_POTENTIAL_PAYMENT,
                        ActionStatus.EXECUTED).stream()
                .findFirst()
                .map(a -> readJson(a.getToolOutput()).path("candidates").isEmpty()
                        ? PaidCheck.NO_PAYMENT_FOUND : PaidCheck.PAYMENT_FOUND)
                .orElse(PaidCheck.NOT_RUN);
    }

    /** Only unmatched payments that the matcher links to THIS invoice may be reconciled to it. */
    private Payment candidatePayment(Invoice invoice, JsonNode input) {
        long paymentId;
        try {
            paymentId = Long.parseLong(input.path("payment_id").asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("payment_id must be a number");
        }
        return matcher.candidate(invoice, paymentId)
                .map(PaymentMatcher.Candidate::payment)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment " + paymentId + " is not an unmatched payment for this invoice"));
    }

    private Map<String, Object> run(ArCase arCase, Invoice invoice, String toolName, JsonNode input) {
        List<Payment> matched = payments.findByInvoiceId(invoice.getId());
        return switch (toolName) {
            case AgentTools.GET_INVOICE -> invoiceView(invoice);
            case AgentTools.GET_INVOICE_PAYMENTS -> Map.of("payments", matched.stream().map(this::paymentView).toList());
            case AgentTools.CALCULATE_OUTSTANDING_BALANCE -> Map.of(
                    "invoice_amount", invoice.getAmount().toPlainString(),
                    "total_paid", BalanceCalculator.totalPaid(matched).toPlainString(),
                    "outstanding", BalanceCalculator.outstanding(invoice, matched).toPlainString(),
                    "currency", invoice.getCurrency());
            case AgentTools.FIND_POTENTIAL_PAYMENT -> {
                BigDecimal outstanding = BalanceCalculator.outstanding(invoice, matched);
                yield Map.of("candidates", matcher.candidates(invoice).stream().map(c -> {
                    Map<String, Object> v = paymentView(c.payment());
                    v.put("why_it_matches", c.reason());
                    v.put("exact_match", c.payment().getAmount().compareTo(outstanding) == 0);
                    return v;
                }).toList());
            }
            case AgentTools.SEND_REMINDER -> {
                Map<String, Object> out = actions.sendReminder(invoice, input.path("note").asText());
                lifecycle.monitor(arCase);
                yield out;
            }
            case AgentTools.RESEND_INVOICE -> {
                Map<String, Object> out = actions.resendInvoice(invoice);
                lifecycle.monitor(arCase);
                yield out;
            }
            case AgentTools.UPDATE_PURCHASE_ORDER -> {
                Map<String, Object> out = actions.updatePurchaseOrder(invoice, input.path("purchase_order").asText());
                lifecycle.monitor(arCase);
                yield out;
            }
            case AgentTools.RECONCILE_PAYMENT -> {
                Map<String, Object> out = actions.reconcilePayment(invoice, candidatePayment(invoice, input), Actor.AGENT);
                if (arCase.isActive()) {
                    lifecycle.monitor(arCase);
                }
                yield out;
            }
            case AgentTools.ESCALATE_TO_HUMAN -> {
                arCase.setStatus(CaseStatus.ESCALATED);
                yield Map.of("status", "escalated", "reason", input.path("reason").asText());
            }
            case AgentTools.RECORD_DIAGNOSIS -> {
                arCase.setRootCause(parseRootCause(input.path("root_cause").asText()));
                arCase.setConfidence(parseConfidence(input.path("confidence").asText()));
                arCase.setExplanation(input.path("explanation").asText());
                arCase.setRecommendedAction(input.path("recommended_action").asText());
                yield Map.of("status", "diagnosis_recorded");
            }
            default -> Map.of("error", "Tool not implemented: " + toolName);
        };
    }

    private Map<String, Object> invoiceView(Invoice i) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("invoice_number", i.getInvoiceNumber());
        v.put("customer", i.getCustomerName());
        v.put("amount", i.getAmount().toPlainString());
        v.put("currency", i.getCurrency());
        v.put("due_date", String.valueOf(i.getDueDate()));
        v.put("days_overdue", BalanceCalculator.daysOverdue(i, clock.today()));
        v.put("status", i.getStatus().name());
        v.put("purchase_order", i.getReference() == null ? "(none)" : i.getReference());
        List<Map<String, String>> conversation = messages.findByInvoiceIdOrderByCreatedAtAsc(i.getId()).stream()
                .map(m -> Map.of("from", m.getAuthor().name(), "at", String.valueOf(m.getCreatedAt()),
                        "text", m.getBody()))
                .toList();
        v.put("conversation", conversation.isEmpty() ? "(no messages from the customer)" : conversation);
        v.put("promises", promises.findByInvoiceIdOrderByDueDateAsc(i.getId()).stream()
                .map(p -> Map.of("amount", p.getAmount().toPlainString(), "due", p.getDueDate().toString(),
                        "status", p.getStatus().name(), "source", String.valueOf(p.getSource())))
                .toList());
        return v;
    }

    private Map<String, Object> paymentView(Payment p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("payment_id", p.getId());
        v.put("paypal_payment_id", p.getPaypalPaymentId());
        v.put("amount", p.getAmount().toPlainString());
        v.put("status", p.getStatus().name());
        v.put("paid_at", String.valueOf(p.getPaidAt()));
        return v;
    }

    private static RootCause parseRootCause(String value) {
        try {
            return RootCause.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RootCause.UNKNOWN;
        }
    }

    private static Double parseConfidence(String value) {
        try {
            double d = Double.parseDouble(value.trim());
            return Math.max(0, Math.min(1, d));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }
}
