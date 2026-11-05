package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByInvoiceId(Long invoiceId);

    /** Payments not yet matched to any invoice that reference this invoice number. */
    List<Payment> findByInvoiceIdIsNullAndInvoiceNumberHint(String invoiceNumberHint);
}
