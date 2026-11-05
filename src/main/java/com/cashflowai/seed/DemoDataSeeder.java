package com.cashflowai.seed;

import com.cashflowai.domain.InvoiceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Seeds the demo scenarios on first start (see {@link DemoDataService}). */
@Component
public class DemoDataSeeder implements CommandLineRunner {

    private final InvoiceRepository invoices;
    private final DemoDataService demo;

    public DemoDataSeeder(InvoiceRepository invoices, DemoDataService demo) {
        this.invoices = invoices;
        this.demo = demo;
    }

    @Override
    public void run(String... args) {
        if (invoices.count() == 0) {
            demo.seed();
        }
    }
}
