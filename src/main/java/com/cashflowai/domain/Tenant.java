package com.cashflowai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** A seller organization. Invoices belong to exactly one tenant. */
@Entity
@Table(name = "tenant")
public class Tenant {

    /** Seeded by schema.sql; owns all invoices created before tenants existed and the demo data. */
    public static final String DEFAULT_SLUG = "default";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    private Instant createdAt;

    protected Tenant() {
    }

    public Tenant(String slug, String name) {
        this.slug = slug;
        this.name = name;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isDefault() { return DEFAULT_SLUG.equals(slug); }
}
