package com.cashflowai.resolution;

import com.cashflowai.action.ActionService;
import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.detection.PaymentMatcher;
import com.cashflowai.domain.AgentAction;
import com.cashflowai.domain.AgentActionRepository;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.Enums.ActionStatus;
import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Enums.CaseType;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.MessageAuthor;
import com.cashflowai.domain.Enums.PromiseStatus;
import com.cashflowai.domain.Enums.RootCause;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceMessage;
import com.cashflowai.domain.InvoiceMessageRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentPromise;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.policy.ActionType;
import com.cashflowai.policy.PaymentPlanPolicy;
import com.cashflowai.policy.PaymentPlanPolicy.Limits;
import com.cashflowai.policy.PaymentPlanPolicy.Offer;
import com.cashflowai.policy.PolicyEngine;
import com.cashflowai.policy.PolicyEngine.Decision;
import com.cashflowai.policy.PolicyEngine.Outcome;
import com.cashflowai.time.DemoClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything the customer side can do to unblock an invoice. Shared by the Fix & Pay page
 * (a person) and the MCP endpoint (the customer's AI agent). Every change goes through the
 * same policy engine as the merchant's agent and is written to the case audit trail.
 */
@Service
public class ResolutionService {

    public record Result(boolean ok, String status, String message, Map<String, Object> data) {
        static Result ok(String status, String message, Map<String, Object> data) {
            return new Result(true, status, message, data);
        }

        static Result refused(String status, String message) {
            return new Result(false, status, message, Map.of());
        }
    }

    private static final Pattern PO_FORMAT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_/-]{1,39}$");
    private static final int MAX_TEXT = 1000;

    private final PaymentRepository payments;
    private final InvoiceMessageRepository messages;
    private final PaymentPromiseRepository promises;
    private final AgentActionRepository audit;
    private final CaseLifecycleService lifecycle;
    private final ActionService actions;
    private final PolicyEngine policy;
    private final PaymentMatcher matcher;
    private final DemoClock clock;
    private final Limits planLimits;
    private final int promiseMaxDays;

    public ResolutionService(PaymentRepository payments, InvoiceMessageRepository messages,
                             PaymentPromiseRepository promises, AgentActionRepository audit,
                             CaseLifecycleService lifecycle, ActionService actions, PolicyEngine policy,
                             PaymentMatcher matcher, DemoClock clock,
                             @Value("${cashflow.payment-plan.max-installments:2}") int maxInstallments,
                             @Value("${cashflow.payment-plan.max-days:30}") int maxDays,
                             @Value("${cashflow.payment-plan.min-first-share:0.50}") BigDecimal minFirstShare,
                             @Value("${cashflow.promise.max-days:14}") int promiseMaxDays) {
        this.payments = payments;
        this.messages = messages;
        this.promises = promises;
        this.audit = audit;
        this.lifecycle = lifecycle;
        this.actions = actions;
        this.policy = policy;
        this.matcher = matcher;
        this.clock = clock;
        this.planLimits = new Limits(maxInstallments, maxDays, minFirstShare);
        this.promiseMaxDays = promiseMaxDays;
    }

    public Limits planLimits() {
        return planLimits;
    }

    /** What the customer (or their agent) sees about one invoice. */
    public Map<String, Object> summary(Invoice i) {
        List<Payment> matched = payments.findByInvoiceId(i.getId());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("invoice_number", i.getInvoiceNumber());
        v.put("customer", i.getCustomerName());
        v.put("amount", i.getAmount().toPlainString());
        v.put("paid", BalanceCalculator.totalPaid(matched).toPlainString());
        v.put("outstanding", BalanceCalculator.outstanding(i, matched).toPlainString());
        v.put("currency", i.getCurrency());
        v.put("due_date", String.valueOf(i.getDueDate()));
        v.put("days_overdue", BalanceCalculator.daysOverdue(i, clock.today()));
        v.put("status", i.getStatus().name());
        v.put("purchase_order", i.getReference());
        v.put("pay_url", i.getPaypalPayerUrl());
        v.put("promises", promises.findByInvoiceIdOrderByDueDateAsc(i.getId()).stream()
                .map(p -> Map.of("amount", p.getAmount().toPlainString(), "due", p.getDueDate().toString(),
                        "status", p.getStatus().name(), "source", String.valueOf(p.getSource())))
                .toList());
        v.put("messages", messages.findByInvoiceIdOrderByCreatedAtAsc(i.getId()).stream()
                .map(m -> Map.of("from", m.getAuthor().name(), "at", String.valueOf(m.getCreatedAt()),
                        "text", m.getBody()))
                .toList());
        return v;
    }

    @Transactional
    public Result submitPurchaseOrder(Invoice invoice, String purchaseOrder, Actor actor) {
        Optional<Result> closed = refuseIfClosed(invoice);
        if (closed.isPresent()) {
            return closed.get();
        }
        String po = purchaseOrder == null ? "" : purchaseOrder.trim();
        if (!PO_FORMAT.matcher(po).matches()) {
            return Result.refused("invalid_po", "A PO number is 2-40 letters, digits, '-', '_' or '/'.");
        }
        say(invoice, actor, "Our purchase order number is " + po + ".");
        ArCase c = lifecycle.activeCaseOrNew(invoice, CaseType.CUSTOMER_REQUEST);
        Decision decision = policy.evaluate(ActionType.UPDATE_REFERENCE, context(invoice).fromCustomer());
        if (decision.outcome() != Outcome.AUTO) {
            return Result.refused("not_allowed", decision.reason());
        }

        Map<String, Object> out = actions.updatePurchaseOrder(invoice, po);
        lifecycle.log(c.getId(), actor, toolName(actor, "submit_purchase_order"), ActionType.UPDATE_REFERENCE,
                Map.of("purchase_order", po), out, decision.reason());
        supersedePendingPoUpdates(c.getId());
        if (c.getRootCause() == null) {
            c.setRootCause(RootCause.MISSING_PO);
            c.setConfidence(1.0);
            c.setExplanation("Customer supplied PO " + po + " themselves.");
        }
        followUp(c, clock.today().plusDays(3));
        return Result.ok("po_updated", "PO " + po + " added. The corrected invoice was re-sent through PayPal.",
                Map.of("invoice", summary(invoice)));
    }

    @Transactional
    public Result reportAlreadyPaid(Invoice invoice, String reference, Actor actor) {
        Optional<Result> closed = refuseIfClosed(invoice);
        if (closed.isPresent()) {
            return closed.get();
        }
        String ref = clip(reference);
        say(invoice, actor, "We already paid this invoice. Payment reference: " + ref);
        ArCase c = lifecycle.activeCaseOrNew(invoice, CaseType.CUSTOMER_REQUEST);

        BigDecimal outstanding = outstanding(invoice);
        List<Payment> found = matcher.candidates(invoice).stream().map(PaymentMatcher.Candidate::payment).toList();
        if (found.isEmpty() && !ref.isBlank()) {
            found = payments.findByPaypalPaymentId(ref).filter(p -> p.getInvoiceId() == null).stream().toList();
        }
        if (found.size() == 1) {
            Payment p = found.get(0);
            Decision decision = policy.evaluate(ActionType.RECONCILE_PAYMENT,
                    new PolicyEngine.Context(0, outstanding, p.getAmount()));
            if (decision.outcome() == Outcome.AUTO) {
                Map<String, Object> out = actions.reconcilePayment(invoice, p, actor);
                lifecycle.log(c.getId(), actor, toolName(actor, "report_already_paid"), ActionType.RECONCILE_PAYMENT,
                        Map.of("reference", ref), out, decision.reason());
                return Result.ok("payment_matched", "Found your payment " + p.getPaypalPaymentId()
                        + " and applied it to " + invoice.getInvoiceNumber() + ". Thank you.", out);
            }
        }
        lifecycle.reopenForAgent(c, "Customer reports the invoice as paid (reference " + ref + ").");
        return Result.ok("checking", "We could not match a payment automatically yet. "
                + "The team will verify it with PayPal.", Map.of());
    }

    public Result quotePlan(Invoice invoice, int installments, int days) {
        Optional<Result> closed = refuseIfClosed(invoice);
        if (closed.isPresent()) {
            return closed.get();
        }
        Decision decision = policy.evaluate(ActionType.PAYMENT_PLAN, context(invoice));
        if (decision.outcome() != Outcome.AUTO) {
            return Result.refused("not_allowed", decision.reason());
        }
        Offer offer = PaymentPlanPolicy.offer(outstanding(invoice), installments, days, clock.today(), planLimits);
        return Result.ok(offer.adjusted() ? "counter_offer" : "offer", offer.note(), Map.of("plan", planView(offer)));
    }

    @Transactional
    public Result acceptPlan(Invoice invoice, int installments, int days, Actor actor) {
        Result quote = quotePlan(invoice, installments, days);
        if (!quote.ok()) {
            return quote;
        }
        if (!promises.findByInvoiceIdAndStatusOrderByDueDateAsc(invoice.getId(), PromiseStatus.PENDING).isEmpty()) {
            return Result.refused("plan_exists", "A payment plan or promise is already in place for this invoice.");
        }
        Offer offer = PaymentPlanPolicy.offer(outstanding(invoice), installments, days, clock.today(), planLimits);
        offer.installments().forEach(i -> promises.save(
                new PaymentPromise(invoice.getId(), i.amount(), i.dueDate(), "PAYMENT_PLAN")));
        String channel = offer.installments().size() > 1
                ? actions.enablePartialPayment(invoice, offer.firstPayment()) : "not_needed";

        say(invoice, actor, "We accept the payment plan: " + describe(offer) + ".");
        ArCase c = lifecycle.activeCaseOrNew(invoice, CaseType.CUSTOMER_REQUEST);
        c.setRootCause(RootCause.CUSTOMER_CASH_FLOW);
        c.setConfidence(1.0);
        c.setRecommendedAction("Monitor plan installments");
        c.setExplanation("Customer agreed a plan inside the owner's limits: " + describe(offer) + ".");
        Map<String, Object> out = Map.of("plan", planView(offer), "paypal_partial_payment", channel);
        lifecycle.log(c.getId(), actor, toolName(actor, "accept_payment_plan"), ActionType.PAYMENT_PLAN,
                Map.of("installments", installments, "days", days), out,
                "Plan is inside the owner's payment-plan limits");
        followUp(c, offer.installments().get(0).dueDate().plusDays(1));
        return Result.ok("plan_accepted", "Payment plan agreed: " + describe(offer)
                + ". You can pay the first installment on the PayPal invoice now.", out);
    }

    @Transactional
    public Result promise(Invoice invoice, BigDecimal amount, LocalDate payBy, Actor actor) {
        Optional<Result> closed = refuseIfClosed(invoice);
        if (closed.isPresent()) {
            return closed.get();
        }
        BigDecimal outstanding = outstanding(invoice);
        LocalDate today = clock.today();
        if (amount == null || amount.signum() <= 0 || amount.compareTo(outstanding) > 0) {
            return Result.refused("invalid_amount", "Amount must be between 0 and the outstanding " + outstanding);
        }
        if (payBy == null || payBy.isBefore(today) || payBy.isAfter(today.plusDays(promiseMaxDays))) {
            return Result.refused("invalid_date", "Pick a date within the next " + promiseMaxDays + " days.");
        }
        promises.save(new PaymentPromise(invoice.getId(), amount, payBy, actor.name()));
        say(invoice, actor, "We will pay " + amount.toPlainString() + " by " + payBy + ".");
        ArCase c = lifecycle.activeCaseOrNew(invoice, CaseType.CUSTOMER_REQUEST);
        lifecycle.log(c.getId(), actor, toolName(actor, "promise_payment"), ActionType.RECORD_PROMISE,
                Map.of("amount", amount.toPlainString(), "pay_by", payBy.toString()), Map.of("status", "recorded"),
                "Recording what the customer committed to");
        followUp(c, payBy.plusDays(1));
        return Result.ok("promise_recorded", "Thanks. No reminders until " + payBy
                + ". We will check PayPal for the payment after that date.", Map.of());
    }

    @Transactional
    public Result dispute(Invoice invoice, String reason, Actor actor) {
        String text = clip(reason);
        if (text.isBlank()) {
            return Result.refused("reason_required", "Please describe what is wrong with the invoice.");
        }
        say(invoice, actor, "Dispute: " + text);
        lifecycle.markDisputed(invoice, text, actor);
        return Result.ok("disputed", "Thanks. Reminders are paused and the owner will contact you.", Map.of());
    }

    @Transactional
    public Result message(Invoice invoice, String text, Actor actor) {
        String body = clip(text);
        if (body.isBlank()) {
            return Result.refused("empty", "Message is empty.");
        }
        say(invoice, actor, body);
        ArCase c = lifecycle.activeCaseOrNew(invoice, CaseType.CUSTOMER_REQUEST);
        lifecycle.log(c.getId(), actor, toolName(actor, "send_message"), ActionType.CUSTOMER_MESSAGE,
                Map.of("text", body), Map.of("status", "received"), "Customer message for the agent to read");
        lifecycle.reopenForAgent(c, "New customer message.");
        return Result.ok("received", "Message received. The team's assistant will reply here.", Map.of());
    }

    public Result paymentLink(Invoice invoice) {
        if (invoice.getPaypalPayerUrl() != null) {
            return Result.ok("pay_url", "Pay on PayPal.", Map.of("pay_url", invoice.getPaypalPayerUrl(),
                    "outstanding", outstanding(invoice).toPlainString()));
        }
        return Result.ok("not_in_paypal_yet", "This invoice has not been published to PayPal yet.",
                Map.of("fix_and_pay_link", actions.portalLink(invoice)));
    }

    private Optional<Result> refuseIfClosed(Invoice invoice) {
        if (invoice.getStatus() == InvoiceStatus.PAID || invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return Optional.of(Result.refused("closed", "This invoice is already " + invoice.getStatus().name().toLowerCase() + "."));
        }
        if (invoice.getStatus() == InvoiceStatus.DISPUTED) {
            return Optional.of(Result.refused("disputed", "This invoice is under dispute; the owner will be in touch."));
        }
        return Optional.empty();
    }

    private void followUp(ArCase c, LocalDate when) {
        if (c.getStatus() != CaseStatus.ESCALATED && c.getStatus() != CaseStatus.WAITING_APPROVAL) {
            lifecycle.monitorUntil(c, when);
        }
    }

    /** A PO the agent proposed is no longer needed once the customer supplied it directly. */
    private void supersedePendingPoUpdates(Long caseId) {
        for (AgentAction a : audit.findByCaseIdOrderByCreatedAtAsc(caseId)) {
            if (a.getStatus() == ActionStatus.PENDING_APPROVAL && a.getActionType() == ActionType.UPDATE_REFERENCE) {
                a.setStatus(ActionStatus.REJECTED);
                a.setApprovedBy("superseded: customer supplied the PO");
                audit.save(a);
            }
        }
    }

    private PolicyEngine.Context context(Invoice invoice) {
        return PolicyEngine.Context.of(BalanceCalculator.daysOverdue(invoice, clock.today()))
                .withDisputed(invoice.getStatus() == InvoiceStatus.DISPUTED);
    }

    private BigDecimal outstanding(Invoice invoice) {
        return BalanceCalculator.outstanding(invoice, payments.findByInvoiceId(invoice.getId()));
    }

    private void say(Invoice invoice, Actor actor, String text) {
        MessageAuthor author = actor == Actor.CUSTOMER_AGENT ? MessageAuthor.CUSTOMER_AGENT : MessageAuthor.CUSTOMER;
        messages.save(new InvoiceMessage(invoice.getId(), author, text).at(clock.now()));
    }

    private static String toolName(Actor actor, String action) {
        return (actor == Actor.CUSTOMER_AGENT ? "mcp." : "portal.") + action;
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= MAX_TEXT ? t : t.substring(0, MAX_TEXT);
    }

    private static List<Map<String, String>> planView(Offer offer) {
        return offer.installments().stream()
                .map(i -> Map.of("amount", i.amount().toPlainString(), "due", i.dueDate().toString()))
                .toList();
    }

    private static String describe(Offer offer) {
        return String.join(", ", offer.installments().stream()
                .map(i -> i.amount().toPlainString() + " by " + i.dueDate()).toList());
    }
}
