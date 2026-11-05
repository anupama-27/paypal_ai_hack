package com.cashflowai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A customer that can connect its own AI agent to the MCP endpoint with an access key. */
@Entity
@Table(name = "customer_account")
public class CustomerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, unique = true)
    private String accessKey;

    protected CustomerAccount() {
    }

    public CustomerAccount(String name, String email) {
        this.name = name;
        this.email = email;
    }

    @PrePersist
    void onCreate() {
        if (accessKey == null) {
            accessKey = "ck_" + Tokens.random();
        }
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getAccessKey() { return accessKey; }
}
