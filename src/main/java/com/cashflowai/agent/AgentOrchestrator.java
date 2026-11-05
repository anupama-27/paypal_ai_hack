package com.cashflowai.agent;

import com.cashflowai.agent.ToolExecutor.ToolResult;
import com.cashflowai.agent.llm.LlmClient;
import com.cashflowai.agent.llm.LlmReply;
import com.cashflowai.agent.llm.LlmReply.ToolCall;
import com.cashflowai.detection.BalanceCalculator;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The agent loop: give the model the case and the tools, run each tool it asks for
 * (through the policy engine), feed results back, repeat until it is done,
 * needs approval, or hits the step limit.
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    static final String SYSTEM_PROMPT = """
            You are CashFlow AI, an accounts-receivable agent for a small business that invoices through PayPal.
            You are working on ONE case: an invoice where Java rules detected a problem.
            Your job: find out WHY the money is stuck and take the single best next action.

            Rules:
            - Use tools to read facts. Never guess amounts and never do money arithmetic yourself;
              use calculate_outstanding_balance.
            - Quote evidence (customer message, payment ids, amounts from tools) in your explanation.
            - Call record_diagnosis exactly once before you take an action.
            - Then take ONE action: send_reminder, resend_invoice, update_purchase_order,
              reconcile_payment or escalate_to_human.
            - Some actions need human approval; if a tool returns pending_approval, stop.
            - If a tool returns blocked, do not retry it; choose another action or escalate.
            - Keep customer-facing notes short, polite and professional.
            """;

    private final LlmClient llm;
    private final AgentTools tools;
    private final ToolExecutor executor;
    private final ArCaseRepository cases;
    private final InvoiceRepository invoices;
    private final ObjectMapper mapper;
    private final int maxSteps;

    public AgentOrchestrator(LlmClient llm, AgentTools tools, ToolExecutor executor,
                             ArCaseRepository cases, InvoiceRepository invoices, ObjectMapper mapper,
                             @Value("${cashflow.agent.max-steps:8}") int maxSteps) {
        this.llm = llm;
        this.tools = tools;
        this.executor = executor;
        this.cases = cases;
        this.invoices = invoices;
        this.mapper = mapper;
        this.maxSteps = maxSteps;
    }

    public ArCase investigate(Long caseId) {
        ArCase arCase = cases.findById(caseId).orElseThrow();
        if (!arCase.isOpen()) {
            return arCase;
        }
        Invoice invoice = invoices.findById(arCase.getInvoiceId()).orElseThrow();
        arCase.setStatus(CaseStatus.INVESTIGATING);
        cases.save(arCase);

        List<ObjectNode> messages = new ArrayList<>();
        messages.add(userText("Investigate this case:\n" + caseSummary(arCase, invoice)));

        for (int step = 0; step < maxSteps; step++) {
            LlmReply reply = llm.send(SYSTEM_PROMPT, messages, tools.all());
            messages.add(reply.assistantMessage());
            if (reply.toolCalls().isEmpty()) {
                break;
            }

            boolean waitForHuman = false;
            ObjectNode toolResults = mapper.createObjectNode();
            toolResults.put("role", "user");
            ArrayNode content = toolResults.putArray("content");

            for (ToolCall call : reply.toolCalls()) {
                ToolResult result = executor.execute(caseId, call.name(), call.input());
                log.info("[case {}] {} -> {}", caseId, call.name(), result.output());
                ObjectNode block = content.addObject();
                block.put("type", "tool_result");
                block.put("tool_use_id", call.id());
                block.put("content", mapper.valueToTree(result.output()).toString());
                waitForHuman |= result.pendingApproval();
            }
            messages.add(toolResults);

            if (waitForHuman || isFinished(caseId)) {
                break;
            }
        }

        ArCase done = cases.findById(caseId).orElseThrow();
        if (done.getStatus() == CaseStatus.INVESTIGATING) {
            done.setStatus(CaseStatus.ESCALATED);
            done.setExplanation((done.getExplanation() == null ? "" : done.getExplanation() + " ")
                    + "Agent did not reach a safe action; handed to a human.");
            cases.save(done);
        }
        return done;
    }

    public String brainName() {
        return llm.name();
    }

    private boolean isFinished(Long caseId) {
        CaseStatus s = cases.findById(caseId).orElseThrow().getStatus();
        return s == CaseStatus.RESOLVED || s == CaseStatus.ESCALATED || s == CaseStatus.MONITORING;
    }

    private String caseSummary(ArCase arCase, Invoice invoice) {
        ObjectNode n = mapper.createObjectNode();
        n.put("case_id", arCase.getId());
        n.put("case_type", arCase.getCaseType().name());
        n.put("invoice_number", invoice.getInvoiceNumber());
        n.put("customer", invoice.getCustomerName());
        n.put("days_overdue", BalanceCalculator.daysOverdue(invoice, LocalDate.now()));
        return n.toPrettyString();
    }

    private ObjectNode userText(String text) {
        ObjectNode m = mapper.createObjectNode();
        m.put("role", "user");
        m.put("content", text);
        return m;
    }
}
