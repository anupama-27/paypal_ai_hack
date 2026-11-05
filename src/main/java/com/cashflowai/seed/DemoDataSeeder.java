package com.cashflowai.seed;

import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Demo data for the four scenarios (plus a few normal invoices).
 * Customers and emails are fictional; customer replies simulate an inbox.
 */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;

    public DemoDataSeeder(InvoiceRepository invoices, PaymentRepository payments) {
        this.invoices = invoices;
        this.payments = payments;
    }

    @Override
    public void run(String... args) {
        if (invoices.count() > 0) {
            return;
        }
        LocalDate today = LocalDate.now();

        // Scenario 1: missing PO. Customer replied with the PO they need.
        Invoice acme = new Invoice("INV-101", "Acme Inc.", "ap@acme.example",
                usd("4500.00"), today.minusDays(23), InvoiceStatus.SENT);
        acme.setCustomerMessage("Hi, we cannot process this invoice because the PO number is missing. "
                + "Please add PO-88421 and resend. Thanks, Sarah (Accounts Payable)");
        invoices.save(acme);

        // Scenario 2: overdue, no response.
        invoices.save(new Invoice("INV-102", "Nova Labs", "billing@novalabs.example",
                usd("1200.00"), today.minusDays(8), InvoiceStatus.SENT));

        // Scenario 3: paid in PayPal, but our books still show it open.
        invoices.save(new Invoice("INV-103", "Brightside Co.", "finance@brightside.example",
                usd("800.00"), today.minusDays(5), InvoiceStatus.SENT));
        payments.save(new Payment("PAY-1921", null, "INV-103", usd("800.00"),
                Instant.now().minus(2, ChronoUnit.DAYS)));

        // Scenario 4: partial payment.
        Invoice orbit = invoices.save(new Invoice("INV-104", "Orbit Studio", "accounts@orbit.example",
                usd("2500.00"), today.minusDays(12), InvoiceStatus.SENT));
        payments.save(new Payment("PAY-2002", orbit.getId(), "INV-104", usd("2000.00"),
                Instant.now().minus(4, ChronoUnit.DAYS)));

        // Escalation tier: very late, no reply.
        invoices.save(new Invoice("INV-105", "Lumen Retail", "ap@lumen.example",
                usd("6400.00"), today.minusDays(65), InvoiceStatus.SENT));

        // Healthy invoices for realism.
        Invoice delta = invoices.save(new Invoice("INV-106", "Delta Foods", "pay@delta.example",
                usd("3000.00"), today.minusDays(3), InvoiceStatus.PAID));
        payments.save(new Payment("PAY-1800", delta.getId(), "INV-106", usd("3000.00"),
                Instant.now().minus(6, ChronoUnit.DAYS)));
        invoices.save(new Invoice("INV-107", "Kite Media", "hello@kite.example",
                usd("950.00"), today.plusDays(10), InvoiceStatus.SENT));
        invoices.save(new Invoice("INV-108", "Pine & Co.", "ops@pine.example",
                usd("1750.00"), today.plusDays(20), InvoiceStatus.SENT));

        log.info("Seeded {} demo invoices", invoices.count());
    }

    private static BigDecimal usd(String value) {
        return new BigDecimal(value);
    }
}
