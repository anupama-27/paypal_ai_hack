package com.cashflowai;

import com.cashflowai.agent.AgentOrchestrator;
import com.cashflowai.detection.ExceptionDetectionService;
import com.cashflowai.domain.AgentAction;
import com.cashflowai.domain.AgentActionRepository;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.CustomerAccountRepository;
import com.cashflowai.domain.Enums.ActionStatus;
import com.cashflowai.domain.Enums.CaseStatus;
import com.cashflowai.domain.Enums.PromiseStatus;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.lifecycle.CaseLifecycleService;
import com.cashflowai.seed.DemoDataService;
import com.cashflowai.time.DemoClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the seeded scenarios end to end with the scripted agent and PayPal disabled. */
@SpringBootTest(properties = {
        "cashflow.llm.api-key=",
        "paypal.enabled=false",
        "cashflow.followup.interval-ms=3600000"
})
@AutoConfigureMockMvc
class EndToEndFlowTest {

    @Autowired DemoDataService demo;
    @Autowired ExceptionDetectionService detection;
    @Autowired AgentOrchestrator agent;
    @Autowired ArCaseRepository cases;
    @Autowired InvoiceRepository invoices;
    @Autowired AgentActionRepository audit;
    @Autowired PaymentPromiseRepository promises;
    @Autowired CustomerAccountRepository accounts;
    @Autowired CaseLifecycleService lifecycle;
    @Autowired DemoClock clock;
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        demo.reset();
    }

    private void runAgent() {
        detection.detect();
        cases.findByStatus(CaseStatus.DETECTED).forEach(c -> agent.investigate(c.getId()));
    }

    private ArCase caseFor(String invoiceNumber) {
        Long invoiceId = invoices.findByInvoiceNumber(invoiceNumber).orElseThrow().getId();
        return cases.findByInvoiceId(invoiceId).stream().reduce((a, b) -> b).orElseThrow();
    }

    private List<AgentAction> actionsFor(String invoiceNumber) {
        return audit.findByCaseIdOrderByCreatedAtAsc(caseFor(invoiceNumber).getId());
    }

    @Test
    void agentHandlesEveryScenario() {
        runAgent();

        assertEquals(CaseStatus.WAITING_APPROVAL, caseFor("INV-101").getStatus());
        assertEquals(CaseStatus.MONITORING, caseFor("INV-102").getStatus());
        assertEquals(CaseStatus.RESOLVED, caseFor("INV-103").getStatus());
        assertEquals(CaseStatus.MONITORING, caseFor("INV-104").getStatus());
        assertEquals(CaseStatus.ESCALATED, caseFor("INV-105").getStatus());

        // Paid-check guard: Harbor paid with a typo in the reference; reconciled, never reminded.
        assertEquals(CaseStatus.RESOLVED, caseFor("INV-109").getStatus());
        assertTrue(actionsFor("INV-109").stream().noneMatch(a -> a.getToolName().equals("send_reminder")));

        // Discount request: policy blocks it and the agent escalates.
        assertEquals(CaseStatus.ESCALATED, caseFor("INV-110").getStatus());
        assertTrue(actionsFor("INV-110").stream()
                .anyMatch(a -> a.getToolName().equals("offer_discount") && a.getStatus() == ActionStatus.BLOCKED));
    }

    @Test
    void paymentClosesTheCaseAndFollowUpReopensUnpaidOnes() throws Exception {
        runAgent();
        mvc.perform(post("/api/demo/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceNumber\":\"INV-102\",\"amount\":1200.00,\"paidOnInvoice\":true}"))
                .andExpect(status().isOk());
        assertEquals(CaseStatus.RESOLVED, caseFor("INV-102").getStatus());

        clock.advance(4);
        assertTrue(lifecycle.runFollowUps() >= 1);
        assertEquals(CaseStatus.DETECTED, caseFor("INV-104").getStatus());
    }

    @Test
    void customerAgentNegotiatesPlanOverMcp() throws Exception {
        String key = accounts.findByEmailIgnoreCase("ap@acme.example").orElseThrow().getAccessKey();

        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/mcp").header("Authorization", "Bearer " + key).contentType(MediaType.APPLICATION_JSON)
                        .content(call(2, "request_payment_plan",
                                "{\"invoice_number\":\"INV-111\",\"installments\":3,\"spread_over_days\":90}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text", containsString("counter_offer")));

        mvc.perform(post("/mcp").header("Authorization", "Bearer " + key).contentType(MediaType.APPLICATION_JSON)
                        .content(call(3, "accept_payment_plan",
                                "{\"invoice_number\":\"INV-111\",\"installments\":3,\"spread_over_days\":90}")))
                .andExpect(jsonPath("$.result.isError").value(false));
        Long inv111 = invoices.findByInvoiceNumber("INV-111").orElseThrow().getId();
        assertEquals(2, promises.findByInvoiceIdAndStatusOrderByDueDateAsc(inv111, PromiseStatus.PENDING).size());

        // A customer key cannot reach another customer's invoice.
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + key).contentType(MediaType.APPLICATION_JSON)
                        .content(call(4, "get_invoice_status", "{\"invoice_number\":\"INV-102\"}")))
                .andExpect(jsonPath("$.result.isError").value(true));
    }

    @Test
    void customerSuppliedPoIsAppliedThroughTheFixAndPayLink() throws Exception {
        String token = invoices.findByInvoiceNumber("INV-101").orElseThrow().getPortalToken();
        mvc.perform(post("/api/portal/" + token + "/po").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseOrder\":\"PO-88421\"}"))
                .andExpect(jsonPath("$.ok").value(true));
        assertEquals("PO-88421", invoices.findByInvoiceNumber("INV-101").orElseThrow().getReference());
        assertEquals(CaseStatus.MONITORING, caseFor("INV-101").getStatus());

        mvc.perform(post("/api/portal/not-a-real-token/po").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchaseOrder\":\"PO-1\"}"))
                .andExpect(status().isNotFound());
    }

    private static String call(int id, String tool, String args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + args + "}}";
    }
}
