package com.cashflowai.web;

import com.cashflowai.action.ActionService;
import com.cashflowai.agent.AgentOrchestrator;
import com.cashflowai.agent.AgentRunService;
import com.cashflowai.agent.ToolExecutor;
import com.cashflowai.detection.ExceptionDetectionService;
import com.cashflowai.domain.AgentAction;
import com.cashflowai.domain.AgentActionRepository;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.ActionStatus;
import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.policy.ActionType;
import com.cashflowai.resolution.ResolutionService;
import com.cashflowai.time.DemoClock;
import com.cashflowai.web.Views.CaseDetail;
import com.cashflowai.web.Views.CaseView;
import com.cashflowai.web.Views.PendingAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CaseController {

    private final ExceptionDetectionService detection;
    private final AgentOrchestrator agent;
    private final AgentRunService runs;
    private final ToolExecutor executor;
    private final ArCaseRepository cases;
    private final InvoiceRepository invoices;
    private final AgentActionRepository audit;
    private final ResolutionService resolution;
    private final ActionService actions;
    private final CaseLifecycleService lifecycle;
    private final TenantContext tenant;
    private final DemoClock clock;

    public CaseController(ExceptionDetectionService detection, AgentOrchestrator agent, AgentRunService runs,
                          ToolExecutor executor, ArCaseRepository cases, InvoiceRepository invoices,
                          AgentActionRepository audit, ResolutionService resolution, ActionService actions,
                          CaseLifecycleService lifecycle, TenantContext tenant, DemoClock clock) {
        this.tenant = tenant;
        this.detection = detection;
        this.agent = agent;
        this.runs = runs;
        this.executor = executor;
        this.cases = cases;
        this.invoices = invoices;
        this.audit = audit;
        this.resolution = resolution;
        this.actions = actions;
        this.lifecycle = lifecycle;
        this.clock = clock;
    }

    /** Step 1: Java rules scan invoices and open cases. */
    @PostMapping("/cases/detect")
    public List<CaseView> detect() {
        return tenant.cases(detection.detect()).stream().map(this::view).toList();
    }

    @GetMapping("/cases")
    public List<CaseView> list() {
        return tenant.cases(cases.findAllByOrderByCreatedAtDesc()).stream().map(this::view).toList();
    }

    @GetMapping("/cases/{id}")
    public CaseDetail detail(@PathVariable Long id) {
        ArCase c = tenant.arCase(id);
        Invoice i = invoices.findById(c.getInvoiceId()).orElseThrow();
        return new CaseDetail(view(c), resolution.summary(i), audit.findByCaseIdOrderByCreatedAtAsc(id),
                actions.portalLink(i));
    }

    /** Step 2: the AI agent investigates one case. */
    @PostMapping("/cases/{id}/investigate")
    public CaseView investigate(@PathVariable Long id) {
        tenant.arCase(id);
        return view(agent.investigate(id));
    }

    /** The owner closes a case the agent handed over (e.g. after calling the customer). */
    @PostMapping("/cases/{id}/resolve")
    public CaseView resolve(@PathVariable Long id, @RequestParam(defaultValue = "owner") String by,
                            @RequestParam(defaultValue = "Resolved by the owner") String note) {
        ArCase c = tenant.arCase(id);
        lifecycle.log(id, Actor.OWNER, "owner.resolve", ActionType.ESCALATE_TO_HUMAN, Map.of("by", by),
                Map.of("note", note), "Owner closed the case");
        c.resolve(clock.now());
        return view(cases.save(c));
    }

    /** Detect, then investigate every waiting case in the background. Poll the returned run. */
    @PostMapping("/agent/run")
    public AgentRunService.RunView runAll() {
        return runs.start(tenant.tenantId(), tenant.invoiceIds());
    }

    @GetMapping("/agent/runs/{runId}")
    public AgentRunService.RunView run(@PathVariable String runId) {
        return runs.get(runId, tenant.tenantId()).orElseThrow();
    }

    @GetMapping("/agent/runs/latest")
    public ResponseEntity<AgentRunService.RunView> latestRun() {
        return ResponseEntity.of(runs.latest(tenant.tenantId()));
    }

    /** Audit trail for a case: every tool call and the policy decision. */
    @GetMapping("/cases/{id}/actions")
    public List<AgentAction> actions(@PathVariable Long id) {
        tenant.arCase(id);
        return audit.findByCaseIdOrderByCreatedAtAsc(id);
    }

    @GetMapping("/actions/pending")
    public List<PendingAction> pending() {
        return tenant.actions(audit.findByStatusOrderByCreatedAtAsc(ActionStatus.PENDING_APPROVAL)).stream()
                .map(a -> new PendingAction(a, view(cases.findById(a.getCaseId()).orElseThrow())))
                .toList();
    }

    @PostMapping("/actions/{id}/approve")
    public AgentAction approve(@PathVariable Long id, @RequestParam(defaultValue = "finance-user") String by) {
        ownedAction(id);
        return executor.approve(id, by);
    }

    @PostMapping("/actions/{id}/reject")
    public AgentAction reject(@PathVariable Long id, @RequestParam(defaultValue = "finance-user") String by) {
        ownedAction(id);
        return executor.reject(id, by);
    }

    private void ownedAction(Long id) {
        AgentAction a = audit.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Action not found"));
        tenant.arCase(a.getCaseId());
    }

    CaseView view(ArCase c) {
        Invoice i = invoices.findById(c.getInvoiceId()).orElseThrow();
        return new CaseView(c.getId(), i.getId(), i.getInvoiceNumber(), i.getCustomerName(), i.getAmount(),
                i.getStatus().name(), c.getCaseType().name(), c.getStatus().name(),
                c.getRootCause() == null ? null : c.getRootCause().name(),
                c.getConfidence(), c.getRecommendedAction(), c.getExplanation(), c.getNextCheckAt(),
                c.getCreatedAt(), c.getResolvedAt());
    }
}
