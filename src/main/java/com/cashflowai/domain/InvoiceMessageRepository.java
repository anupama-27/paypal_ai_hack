package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceMessageRepository extends JpaRepository<InvoiceMessage, Long> {

    List<InvoiceMessage> findByInvoiceIdOrderByCreatedAtAsc(Long invoiceId);
}
