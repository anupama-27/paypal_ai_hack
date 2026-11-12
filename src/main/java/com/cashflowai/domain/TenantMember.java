package com.cashflowai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** A merchant's access to one tenant. The same email may be a member of several tenants. */
@Entity
@Table(name = "tenant_member")
public class TenantMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String email;

    private String name;

    private Instant createdAt;

    protected TenantMember() {
    }

    public TenantMember(Long tenantId, String email, String name) {
        this.tenantId = tenantId;
        this.email = email.trim().toLowerCase();
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
