package com.cashflowai.domain;

import com.cashflowai.domain.Enums.InvoiceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "invoice")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String invoiceNumber;

    /** Id PayPal assigns (e.g. INV2-XXXX) once the invoice is pushed to the sandbox. */
    private String paypalInvoiceId;

    private String customerName;
    private String customerEmail;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    private String currency = "USD";
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    /** Purchase order number. Maps to PayPal's detail.reference field. */
    private String reference;

    /** Latest customer reply (simulated inbox for the demo). */
    @Column(length = 2000)
    private String customerMessage;

    private Instant createdAt;
    private Instant updatedAt;

    protected Invoice() {
    }

    public Invoice(String invoiceNumber, String customerName, String customerEmail,
                   BigDecimal amount, LocalDate dueDate, InvoiceStatus status) {
        this.invoiceNumber = invoiceNumber;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.amount = amount;
        this.dueDate = dueDate;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public String getPaypalInvoiceId() { return paypalInvoiceId; }
    public void setPaypalInvoiceId(String paypalInvoiceId) { this.paypalInvoiceId = paypalInvoiceId; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public LocalDate getDueDate() { return dueDate; }
    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getCustomerMessage() { return customerMessage; }
    public void setCustomerMessage(String customerMessage) { this.customerMessage = customerMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
