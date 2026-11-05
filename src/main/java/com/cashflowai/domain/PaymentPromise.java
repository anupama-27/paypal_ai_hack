package com.cashflowai.domain;

import com.cashflowai.domain.Enums.PromiseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** "We will pay X by date Y", from the customer or from an agreed payment plan. Checked against PayPal payments. */
@Entity
@Table(name = "payment_promise")
public class PaymentPromise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long invoiceId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDate dueDate;

    /** PORTAL, CUSTOMER_AGENT or PAYMENT_PLAN. */
    private String source;

    @Enumerated(EnumType.STRING)
    private PromiseStatus status = PromiseStatus.PENDING;

    private Instant createdAt;

    protected PaymentPromise() {
    }

    public PaymentPromise(Long invoiceId, BigDecimal amount, LocalDate dueDate, String source) {
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.dueDate = dueDate;
        this.source = source;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getInvoiceId() { return invoiceId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getDueDate() { return dueDate; }
    public String getSource() { return source; }
    public PromiseStatus getStatus() { return status; }
    public void setStatus(PromiseStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
