package com.cashflowai.web;

import com.cashflowai.domain.Enums.Actor;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.resolution.ResolutionService;
import com.cashflowai.resolution.ResolutionService.Result;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fix & Pay: the customer's page for one invoice, reached through the secret link in every reminder.
 * The token is the only credential, and it only grants access to that one invoice.
 */
@RestController
@RequestMapping("/api/portal/{token}")
public class PortalController {

    public record PoRequest(String purchaseOrder) {
    }

    public record PaidRequest(String reference) {
    }

    public record PlanRequest(int installments, int days) {
    }

    public record PromiseRequest(BigDecimal amount, LocalDate payBy) {
    }

    public record TextRequest(String text) {
    }

    private final InvoiceRepository invoices;
    private final ResolutionService resolution;

    public PortalController(InvoiceRepository invoices, ResolutionService resolution) {
        this.invoices = invoices;
        this.resolution = resolution;
    }

    @GetMapping
    public Map<String, Object> view(@PathVariable String token) {
        Map<String, Object> v = new LinkedHashMap<>(resolution.summary(invoice(token)));
        var limits = resolution.planLimits();
        v.put("plan_limits", Map.of("max_installments", limits.maxInstallments(), "max_days", limits.maxDays(),
                "min_first_share", limits.minFirstShare()));
        return v;
    }

    @PostMapping("/po")
    public Result po(@PathVariable String token, @RequestBody PoRequest req) {
        return resolution.submitPurchaseOrder(invoice(token), req.purchaseOrder(), Actor.CUSTOMER_PORTAL);
    }

    @PostMapping("/already-paid")
    public Result alreadyPaid(@PathVariable String token, @RequestBody PaidRequest req) {
        return resolution.reportAlreadyPaid(invoice(token), req.reference(), Actor.CUSTOMER_PORTAL);
    }

    @PostMapping("/plan/quote")
    public Result quote(@PathVariable String token, @RequestBody PlanRequest req) {
        return resolution.quotePlan(invoice(token), req.installments(), req.days());
    }

    @PostMapping("/plan/accept")
    public Result accept(@PathVariable String token, @RequestBody PlanRequest req) {
        return resolution.acceptPlan(invoice(token), req.installments(), req.days(), Actor.CUSTOMER_PORTAL);
    }

    @PostMapping("/promise")
    public Result promise(@PathVariable String token, @RequestBody PromiseRequest req) {
        return resolution.promise(invoice(token), req.amount(), req.payBy(), Actor.CUSTOMER_PORTAL);
    }

    @PostMapping("/dispute")
    public Result dispute(@PathVariable String token, @RequestBody TextRequest req) {
        return resolution.dispute(invoice(token), req.text(), Actor.CUSTOMER_PORTAL);
    }

    @PostMapping("/message")
    public Result message(@PathVariable String token, @RequestBody TextRequest req) {
        return resolution.message(invoice(token), req.text(), Actor.CUSTOMER_PORTAL);
    }

    private Invoice invoice(String token) {
        return invoices.findByPortalToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown link"));
    }
}
