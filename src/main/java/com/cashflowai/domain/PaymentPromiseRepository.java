package com.cashflowai.domain;

import com.cashflowai.domain.Enums.PromiseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentPromiseRepository extends JpaRepository<PaymentPromise, Long> {

    List<PaymentPromise> findByInvoiceIdOrderByDueDateAsc(Long invoiceId);

    List<PaymentPromise> findByInvoiceIdAndStatusOrderByDueDateAsc(Long invoiceId, PromiseStatus status);

    List<PaymentPromise> findByStatus(PromiseStatus status);
}
