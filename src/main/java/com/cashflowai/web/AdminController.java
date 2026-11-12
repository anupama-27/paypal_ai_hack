package com.cashflowai.web;

import com.cashflowai.domain.Tenant;
import com.cashflowai.domain.TenantMember;
import com.cashflowai.domain.TenantMemberRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Merchants manage who else can work in the current tenant. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public record MemberView(Long id, String email, String name, Instant createdAt) {
    }

    public record TenantView(Long id, String name, boolean platform, List<MemberView> members) {
    }

    public record NewMember(String email, String name) {
    }

    private final TenantMemberRepository members;
    private final TenantContext tenant;

    public AdminController(TenantMemberRepository members, TenantContext tenant) {
        this.members = members;
        this.tenant = tenant;
    }

    @GetMapping("/tenant")
    public TenantView myTenant() {
        Tenant t = tenant.tenant();
        return new TenantView(t.getId(), t.getName(), t.isDefault(),
                members.findByTenantIdOrderByEmailAsc(t.getId()).stream().map(AdminController::view).toList());
    }

    /** Gives another Google account merchant access to this tenant. */
    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView invite(@RequestBody NewMember body) {
        String email = body.email() == null ? "" : body.email().trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
        }
        Long tenantId = tenant.tenantId();
        if (members.existsByTenantIdAndEmailIgnoreCase(tenantId, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, email + " is already a member");
        }
        String name = body.name() == null || body.name().isBlank() ? null : body.name().trim();
        return view(members.save(new TenantMember(tenantId, email, name)));
    }

    @DeleteMapping("/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id) {
        TenantMember m = members.findById(id).filter(x -> tenant.tenantId().equals(x.getTenantId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        if (m.getEmail().equalsIgnoreCase(tenant.current().email())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot remove yourself");
        }
        members.delete(m);
    }

    private static MemberView view(TenantMember m) {
        return new MemberView(m.getId(), m.getEmail(), m.getName(), m.getCreatedAt());
    }
}
