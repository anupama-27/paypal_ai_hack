package com.cashflowai.web;

import com.cashflowai.auth.AuthFilter;
import com.cashflowai.auth.AuthService;
import com.cashflowai.auth.AuthService.User;
import com.cashflowai.domain.AgentAction;
import com.cashflowai.domain.ArCase;
import com.cashflowai.domain.ArCaseRepository;
import com.cashflowai.domain.Invoice;
import com.cashflowai.domain.InvoiceRepository;
import com.cashflowai.domain.Tenant;
import com.cashflowai.domain.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The signed-in seller's tenant, and ownership checks for anything reached by id.
 * With login disabled and no session (tests, curl) the caller acts as a merchant of the default tenant.
 * Records of another tenant answer 404, so ids from other tenants reveal nothing.
 */
@Component
public class TenantContext {

    private final TenantRepository tenants;
    private final InvoiceRepository invoices;
    private final ArCaseRepository cases;

    public TenantContext(TenantRepository tenants, InvoiceRepository invoices, ArCaseRepository cases) {
        this.tenants = tenants;
        this.invoices = invoices;
        this.cases = cases;
    }

    /** The signed-in user; empty when login is disabled. */
    public Optional<User> user() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object u = attrs == null ? null : attrs.getAttribute(AuthFilter.USER_ATTR, RequestAttributes.SCOPE_REQUEST);
        return Optional.ofNullable((User) u);
    }

    /** Who is acting: the signed-in user, or a stand-in merchant of the default tenant when login is off. */
    public User current() {
        return user().orElseGet(() -> {
            Tenant home = defaultTenant();
            return new User("", "Local merchant", "", AuthService.MERCHANT, home.getId(), home.getName());
        });
    }

    /** The tenant chosen for this session. */
    public Long tenantId() {
        User u = current();
        if (!u.merchant()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Choose an organization to continue as a merchant");
        }
        return u.tenantId();
    }

    public Tenant tenant() {
        return tenants.findById(tenantId()).orElseThrow();
    }

    /** Platform-level actions (demo reset, creating tenants) are kept to admins of the default tenant. */
    public void requireDefaultTenant() {
        if (!tenant().isDefault()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the platform organization can do this");
        }
    }

    public List<Invoice> invoices() {
        return invoices.findByTenantId(tenantId());
    }

    public Set<Long> invoiceIds() {
        return invoices().stream().map(Invoice::getId).collect(Collectors.toSet());
    }

    public boolean owns(Invoice invoice) {
        return invoice != null && tenantId().equals(invoice.getTenantId());
    }

    public Invoice invoice(Long id) {
        return invoices.findById(id).filter(this::owns)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
    }

    public Invoice invoiceByNumber(String number) {
        return invoices.findByInvoiceNumber(number).filter(this::owns)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invoice " + number));
    }

    public ArCase arCase(Long id) {
        ArCase c = cases.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        invoices.findById(c.getInvoiceId()).filter(this::owns)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        return c;
    }

    public List<ArCase> cases(List<ArCase> all) {
        Set<Long> mine = invoiceIds();
        return all.stream().filter(c -> mine.contains(c.getInvoiceId())).toList();
    }

    public Set<Long> caseIds() {
        Set<Long> mine = invoiceIds();
        return cases.findAll().stream().filter(c -> mine.contains(c.getInvoiceId()))
                .map(ArCase::getId).collect(Collectors.toSet());
    }

    public List<AgentAction> actions(List<AgentAction> all) {
        Set<Long> mine = caseIds();
        return all.stream().filter(a -> a.getCaseId() != null && mine.contains(a.getCaseId())).toList();
    }

    private Tenant defaultTenant() {
        return tenants.findBySlug(Tenant.DEFAULT_SLUG).orElseThrow();
    }
}
