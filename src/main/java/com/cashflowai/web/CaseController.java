package com.cashflowai.web;

import com.cashflowai.agent.AgentOrchestrator;
import com.cashflowai.agent.ToolExecutor;
import com.cashflowai.detection.ExceptionDetectionService;
import com.cashflowai.domain.AgentAction;
import com.cashflowai.domain.AgentActionRepository;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.web.Views.CaseView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CaseController {

    private final ExceptionDetectionService detection;
    private final AgentOrchestrator agent;
    private final ToolExecutor executor;
    private final ArCaseRepository cases;
    private final InvoiceRepository invoices;
    private final AgentActionRepository audit;

    public CaseController(ExceptionDetectionService detection, AgentOrchestrator agent, ToolExecutor executor,
                          ArCaseRepository cases, InvoiceRepository invoices, AgentActionRepository audit) {
        this.detection = detection;
        this.agent = agent;
        this.executor = executor;
        this.cases = cases;
        this.invoices = invoices;
        this.audit = audit;
    }

    /** Step 1: Java rules scan invoices and open cases. */
    @PostMapping("/cases/detect")
    public List<CaseView> detect() {
        return detection.detect().stream().map(this::view).toList();
    }

    @GetMapping("/cases")
    public List<CaseView> list() {
        return cases.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    /** Step 2: the AI agent investigates one case. */
    @PostMapping("/cases/{id}/investigate")
    public CaseView investigate(@PathVariable Long id) {
        return view(agent.investigate(id));
    }

    /** Demo button: detect, then investigate every new case. */
    @PostMapping("/agent/run")
    public List<CaseView> runAll() {
        detection.detect();
        return cases.findAllByOrderByCreatedAtDesc().stream()
                .filter(c -> c.getStatus() == CaseStatus.DETECTED)
                .map(c -> view(agent.investigate(c.getId())))
                .toList();
    }

    /** Audit trail for a case: every tool call and the policy decision. */
    @GetMapping("/cases/{id}/actions")
    public List<AgentAction> actions(@PathVariable Long id) {
        return audit.findByCaseIdOrderByCreatedAtAsc(id);
    }

    @PostMapping("/actions/{id}/approve")
    public AgentAction approve(@PathVariable Long id, @RequestParam(defaultValue = "finance-user") String by) {
        return executor.approve(id, by);
    }

    @PostMapping("/actions/{id}/reject")
    public AgentAction reject(@PathVariable Long id, @RequestParam(defaultValue = "finance-user") String by) {
        return executor.reject(id, by);
    }

    CaseView view(ArCase c) {
        Invoice i = invoices.findById(c.getInvoiceId()).orElseThrow();
        return new CaseView(c.getId(), i.getId(), i.getInvoiceNumber(), i.getCustomerName(), i.getAmount(),
                c.getCaseType().name(), c.getStatus().name(),
                c.getRootCause() == null ? null : c.getRootCause().name(),
                c.getConfidence(), c.getRecommendedAction(), c.getExplanation());
    }
}
