package com.cashflowai.domain;

import com.cashflowai.domain.Enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment seen in PayPal. invoiceId == null means it has not been matched
 * (reconciled) to an internal invoice yet.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String paypalPaymentId;

    /** Internal invoice this payment is matched to; null = unreconciled. */
    private Long invoiceId;

    /** Invoice number the payer referenced, used to find candidate matches. */
    private String invoiceNumberHint;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.COMPLETED;

    private Instant paidAt;

    protected Payment() {
    }

    public Payment(String paypalPaymentId, Long invoiceId, String invoiceNumberHint,
                   BigDecimal amount, Instant paidAt) {
        this.paypalPaymentId = paypalPaymentId;
        this.invoiceId = invoiceId;
        this.invoiceNumberHint = invoiceNumberHint;
        this.amount = amount;
        this.paidAt = paidAt;
    }

    public Long getId() { return id; }
    public String getPaypalPaymentId() { return paypalPaymentId; }
    public Long getInvoiceId() { return invoiceId; }
    public void setInvoiceId(Long invoiceId) { this.invoiceId = invoiceId; }
    public String getInvoiceNumberHint() { return invoiceNumberHint; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public Instant getPaidAt() { return paidAt; }
}
