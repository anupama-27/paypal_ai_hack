package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    Optional<Invoice> findByPaypalInvoiceId(String paypalInvoiceId);

    Optional<Invoice> findByPortalToken(String portalToken);

    List<Invoice> findByCustomerEmailIgnoreCase(String customerEmail);

    boolean existsByCustomerEmailIgnoreCase(String customerEmail);

    List<Invoice> findByTenantId(Long tenantId);
}
