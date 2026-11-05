package com.cashflowai.domain;

import com.cashflowai.domain.Enums.CaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArCaseRepository extends JpaRepository<ArCase, Long> {

    List<ArCase> findByInvoiceId(Long invoiceId);

    List<ArCase> findAllByOrderByCreatedAtDesc();

    List<ArCase> findByStatus(CaseStatus status);
}
