package com.cashflowai.seed;

import com.cashflowai.domain.AgentActionRepository;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.AutonomyRuleRepository;
import com.cashflowai.domain.CustomerAccount;
import com.cashflowai.domain.CustomerAccountRepository;
import com.cashflowai.domain.Enums.InvoiceStatus;
import com.cashflowai.domain.Enums.MessageAuthor;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceMessage;
import com.cashflowai.domain.InvoiceMessageRepository;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Payment;
import com.cashflowai.domain.PaymentPromiseRepository;
import com.cashflowai.domain.PaymentRepository;
import com.cashflowai.domain.WebhookEventRepository;
import com.cashflowai.time.DemoClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Demo data: one scenario per thing the agent should handle, plus a few healthy invoices.
 * Customers and emails are fictional; customer replies simulate an inbox.
 */
@Service
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final InvoiceMessageRepository messages;
    private final PaymentPromiseRepository promises;
    private final ArCaseRepository cases;
    private final AgentActionRepository audit;
    private final AutonomyRuleRepository autonomy;
    private final WebhookEventRepository webhooks;
    private final CustomerAccountRepository accounts;
    private final DemoClock clock;

    public DemoDataService(InvoiceRepository invoices, PaymentRepository payments, InvoiceMessageRepository messages,
                           PaymentPromiseRepository promises, ArCaseRepository cases, AgentActionRepository audit,
                           AutonomyRuleRepository autonomy, WebhookEventRepository webhooks,
                           CustomerAccountRepository accounts, DemoClock clock) {
        this.invoices = invoices;
        this.payments = payments;
        this.messages = messages;
        this.promises = promises;
        this.cases = cases;
        this.audit = audit;
        this.autonomy = autonomy;
        this.webhooks = webhooks;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Wipes business data and re-seeds. Customer access keys are kept so connected agents keep working. */
    @Transactional
    public void reset() {
        audit.deleteAllInBatch();
        messages.deleteAllInBatch();
        promises.deleteAllInBatch();
        cases.deleteAllInBatch();
        payments.deleteAllInBatch();
        invoices.deleteAllInBatch();
        autonomy.deleteAllInBatch();
        webhooks.deleteAllInBatch();
        clock.reset();
        seed();
    }

    @Transactional
    public void seed() {
        LocalDate today = clock.today();
        Instant now = clock.now();

        // Missing PO: the customer told us which PO they need.
        Invoice acme = invoices.save(new Invoice("INV-101", "Acme Inc.", "ap@acme.example",
                usd("4500.00"), today.minusDays(23), InvoiceStatus.SENT));
        say(acme, "Hi, we cannot process this invoice because the PO number is missing. "
                + "Please add PO-88421 and resend. Thanks, Sarah (Accounts Payable)", now.minus(4, ChronoUnit.DAYS));

        // Overdue, no response.
        invoices.save(new Invoice("INV-102", "Nova Labs", "billing@novalabs.example",
                usd("1200.00"), today.minusDays(8), InvoiceStatus.SENT));

        // Paid in PayPal, but our books still show it open.
        invoices.save(new Invoice("INV-103", "Brightside Co.", "finance@brightside.example",
                usd("800.00"), today.minusDays(5), InvoiceStatus.SENT));
        payments.save(new Payment("PAY-1921", null, "INV-103", usd("800.00"), now.minus(2, ChronoUnit.DAYS))
                .withPayerEmail("finance@brightside.example"));

        // Partial payment.
        Invoice orbit = invoices.save(new Invoice("INV-104", "Orbit Studio", "accounts@orbit.example",
                usd("2500.00"), today.minusDays(12), InvoiceStatus.PARTIALLY_PAID));
        payments.save(new Payment("PAY-2002", orbit.getId(), "INV-104", usd("2000.00"), now.minus(4, ChronoUnit.DAYS))
                .withPayerEmail("accounts@orbit.example"));

        // Escalation tier: very late, no reply.
        invoices.save(new Invoice("INV-105", "Lumen Retail", "ap@lumen.example",
                usd("6400.00"), today.minusDays(65), InvoiceStatus.SENT));

        // Paid-check guard: looks overdue, but the customer paid with a typo in the reference.
        invoices.save(new Invoice("INV-109", "Harbor Supply", "ap@harbor.example",
                usd("1500.00"), today.minusDays(10), InvoiceStatus.SENT));
        payments.save(new Payment("PAY-3107", null, "INV-1O9", usd("1500.00"), now.minus(3, ChronoUnit.DAYS))
                .withPayerEmail("ap@harbor.example"));

        // Guardrail: the customer asks for a discount; policy blocks the agent from offering one.
        Invoice summit = invoices.save(new Invoice("INV-110", "Summit Partners", "finance@summit.example",
                usd("3200.00"), today.minusDays(15), InvoiceStatus.SENT));
        say(summit, "We have been loyal customers for years. Can you take 20% off this invoice? "
                + "If so we will pay this week. - Raj, Finance", now.minus(2, ChronoUnit.DAYS));

        // Second Acme invoice, not yet due: used by Acme's own AI agent over MCP (payment plan demo).
        invoices.save(new Invoice("INV-111", "Acme Inc.", "ap@acme.example",
                usd("2000.00"), today.plusDays(5), InvoiceStatus.SENT));

        // Healthy invoices for realism.
        Invoice delta = invoices.save(new Invoice("INV-106", "Delta Foods", "pay@delta.example",
                usd("3000.00"), today.minusDays(3), InvoiceStatus.PAID));
        payments.save(new Payment("PAY-1800", delta.getId(), "INV-106", usd("3000.00"), now.minus(6, ChronoUnit.DAYS))
                .withPayerEmail("pay@delta.example"));
        invoices.save(new Invoice("INV-107", "Kite Media", "hello@kite.example",
                usd("950.00"), today.plusDays(10), InvoiceStatus.SENT));
        invoices.save(new Invoice("INV-108", "Pine & Co.", "ops@pine.example",
                usd("1750.00"), today.plusDays(20), InvoiceStatus.SENT));

        invoices.findAll().forEach(i -> {
            if (accounts.findByEmailIgnoreCase(i.getCustomerEmail()).isEmpty()) {
                accounts.save(new CustomerAccount(i.getCustomerName(), i.getCustomerEmail()));
            }
        });
        log.info("Seeded {} demo invoices. Acme's agent access key: {}", invoices.count(),
                accounts.findByEmailIgnoreCase("ap@acme.example").map(CustomerAccount::getAccessKey).orElse("?"));
    }

    private void say(Invoice invoice, String text, Instant at) {
        messages.save(new InvoiceMessage(invoice.getId(), MessageAuthor.CUSTOMER, text).at(at));
    }

    private static BigDecimal usd(String value) {
        return new BigDecimal(value);
    }
}
