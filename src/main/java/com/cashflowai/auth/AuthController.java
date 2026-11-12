package com.cashflowai.auth;

import com.cashflowai.auth.AuthService.TenantOption;
import com.cashflowai.auth.AuthService.User;
import com.cashflowai.domain.Tenant;
import com.cashflowai.domain.TenantMember;
import com.cashflowai.domain.TenantMemberRepository;
import com.cashflowai.domain.TenantRepository;
import com.cashflowai.web.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sign-in in two steps: prove who you are (Google, or an email in local dev), then choose to continue
 * as a customer or as a merchant of one of your tenants. Choosing again switches role or tenant.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Session(String token, User user) {
    }

    public record Options(String email, List<TenantOption> tenants) {
    }

    public record Choice(String role, Long tenantId) {
    }

    public record NewTenant(String name) {
    }

    private final AuthService auth;
    private final TenantContext tenant;
    private final TenantRepository tenants;
    private final TenantMemberRepository members;

    public AuthController(AuthService auth, TenantContext tenant, TenantRepository tenants,
                          TenantMemberRepository members) {
        this.auth = auth;
        this.tenant = tenant;
        this.tenants = tenants;
        this.members = members;
    }

    /** Tells the UI whether Google login is on and which client id to use (off = local dev sign-in). */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", auth.enabled(), "googleClientId", auth.clientId());
    }

    @PostMapping("/google")
    public Session google(@RequestBody Map<String, String> body) {
        return session(auth.verifyGoogleIdToken(body.getOrDefault("credential", "")));
    }

    /** Local dev only: answers 404 once GOOGLE_CLIENT_ID is set. */
    @PostMapping("/dev")
    public Session dev(@RequestBody Map<String, String> body) {
        return session(auth.devSignIn(body.get("email")));
    }

    /** The caller, including the chosen role and tenant (role null = not chosen yet). */
    @GetMapping("/me")
    public User me() {
        return tenant.current();
    }

    /** What the signed-in person can choose from. Anyone can continue as a customer. */
    @GetMapping("/options")
    public Options options() {
        User who = signedIn();
        return new Options(who.email(), auth.tenantsOf(who.email()));
    }

    /** role CUSTOMER, or MERCHANT with a tenantId; an empty role returns to the picker. */
    @PostMapping("/select")
    public Session select(@RequestBody Choice choice) {
        return session(auth.choose(signedIn(), choice.role(), choice.tenantId()));
    }

    /** Creates a new organization with the caller as its first merchant. */
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TenantOption createTenant(@RequestBody NewTenant body) {
        User who = signedIn();
        String name = body.name() == null ? "" : body.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Organization name is required (max 120)");
        }
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty() || tenants.findBySlug(slug).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An organization with this name already exists");
        }
        Tenant t = tenants.save(new Tenant(slug, name));
        members.save(new TenantMember(t.getId(), who.email(), who.name()));
        return new TenantOption(t.getId(), t.getName());
    }

    private Session session(User user) {
        return new Session(auth.issueSession(user), user);
    }

    private User signedIn() {
        return tenant.user().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in"));
    }
}
