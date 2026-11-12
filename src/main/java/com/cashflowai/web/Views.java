package com.cashflowai.web;

import com.cashflowai.domain.AgentAction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** JSON shapes returned to the React dashboard. */
public final class Views {

    private Views() {
    }

    public record InvoiceView(Long id, String invoiceNumber, String customer, String customerEmail, BigDecimal amount,
                              BigDecimal paid, BigDecimal outstanding, String currency, LocalDate dueDate,
                              long daysOverdue, String status, String purchaseOrder, String paypalInvoiceId,
                              String paypalPayerUrl, String fixAndPayLink) {
    }

    public record CaseView(Long id, Long invoiceId, String invoiceNumber, String customer, BigDecimal amount,
                           String invoiceStatus, String caseType, String status, String rootCause, Double confidence,
                           String recommendedAction, String explanation, LocalDate nextCheckAt,
                           Instant createdAt, Instant resolvedAt) {
    }

    public record CaseDetail(CaseView caseInfo, Map<String, Object> invoice, List<AgentAction> actions,
                             String fixAndPayLink) {
    }

    public record PendingAction(AgentAction action, CaseView caseInfo) {
    }

    /** One event on the Timeline page (Bryntum calendar). kind: INVOICE_DUE | PROMISE_DUE | FOLLOW_UP */
    public record TimelineEvent(String kind, Long sourceId, LocalDate date, String label, String invoiceNumber,
                                Long caseId, BigDecimal amount, String status, String time) {
    }

    public record DashboardView(BigDecimal outstanding, BigDecimal overdue, BigDecimal collectedSinceAgent,
                                BigDecimal reconciledByAgent, long openExceptions, long monitoring,
                                long waitingApproval, long escalated, long resolved, long guardrailBlocks,
                                long autonomySuggestions, String agentBrain, boolean paypalEnabled,
                                LocalDate today) {
    }
}
