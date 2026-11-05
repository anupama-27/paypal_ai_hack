package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArCaseRepository extends JpaRepository<ArCase, Long> {

    List<ArCase> findByInvoiceId(Long invoiceId);

    List<ArCase> findAllByOrderByCreatedAtDesc();
}
