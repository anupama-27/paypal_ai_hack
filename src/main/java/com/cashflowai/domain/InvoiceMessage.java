package com.cashflowai.domain;

import com.cashflowai.domain.Enums.MessageAuthor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** Conversation about an invoice (customer replies, agent notes, customer-agent requests). */
@Entity
@Table(name = "invoice_message")
public class InvoiceMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long invoiceId;

    @Enumerated(EnumType.STRING)
    private MessageAuthor author;

    @Column(length = 2000)
    private String body;

    private Instant createdAt;

    protected InvoiceMessage() {
    }

    public InvoiceMessage(Long invoiceId, MessageAuthor author, String body) {
        this.invoiceId = invoiceId;
        this.author = author;
        this.body = body;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public InvoiceMessage at(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    public Long getId() { return id; }
    public Long getInvoiceId() { return invoiceId; }
    public MessageAuthor getAuthor() { return author; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
