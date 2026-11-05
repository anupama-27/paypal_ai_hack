package com.cashflowai.web;

import java.math.BigDecimal;
import java.time.LocalDate;

/** JSON shapes returned to the React dashboard. */
public final class Views {

    private Views() {
    }

    public record InvoiceView(Long id, String invoiceNumber, String customer, BigDecimal amount,
                              BigDecimal paid, BigDecimal outstanding, String currency, LocalDate dueDate,
                              long daysOverdue, String status, String purchaseOrder, String paypalInvoiceId) {
    }

    public record CaseView(Long id, Long invoiceId, String invoiceNumber, String customer, BigDecimal amount,
                           String caseType, String status, String rootCause, Double confidence,
                           String recommendedAction, String explanation) {
    }

    public record DashboardView(BigDecimal outstanding, BigDecimal overdue, BigDecimal recoveredByAi,
                                long openExceptions, long selfHealed, long waitingApproval,
                                String agentBrain) {
    }
}
