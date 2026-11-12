package com.cashflowai.auth;

import com.cashflowai.domain.Tenant;
import com.cashflowai.domain.TenantMemberRepository;
import com.cashflowai.domain.TenantRepository;
import com.cashflowai.domain.TenantMember;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies Google ID tokens and issues/validates our own short-lived HS256 session tokens.
 *
 * A session first proves only who you are (role = null). The user then picks how to continue:
 * as a CUSTOMER (invoices billed to their email) or as a MERCHANT of one tenant they belong to.
 * The pick is stored in the session; tenant membership is re-checked on every request.
 */
@Service
public class AuthService {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String MERCHANT = "MERCHANT";

    /** role is null until chosen; tenantId/tenantName are only set for merchants. */
    public record User(String email, String name, String picture, String role, Long tenantId, String tenantName) {
        public boolean merchant() {
            return MERCHANT.equals(role);
        }

        public boolean customer() {
            return CUSTOMER.equals(role);
        }

        User identity() {
            return new User(email, name, picture, null, null, null);
        }
    }

    public record TenantOption(Long id, String name) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper json = new ObjectMapper();
    private final RestClient http = RestClient.create();
    private final String clientId;
    private final List<String> bootstrapEmails;
    private final long ttlSeconds;
    private final byte[] secret;
    private final TenantRepository tenants;
    private final TenantMemberRepository members;

    public AuthService(@Value("${cashflow.auth.google-client-id:}") String clientId,
                       @Value("${cashflow.auth.allowed-emails:}") String allowedEmails,
                       @Value("${cashflow.auth.session-hours:12}") long sessionHours,
                       @Value("${cashflow.auth.session-secret:}") String sessionSecret,
                       TenantRepository tenants, TenantMemberRepository members) {
        this.tenants = tenants;
        this.members = members;
        this.clientId = clientId.trim();
        this.bootstrapEmails = Arrays.stream(allowedEmails.split(","))
                .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).toList();
        this.ttlSeconds = sessionHours * 3600;
        if (sessionSecret.isBlank()) {
            // Sessions then end on every restart; set CASHFLOW_AUTH_SESSION_SECRET to keep them.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
        } else {
            this.secret = sessionSecret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Google login is only enforced once a client id is configured; otherwise local dev sign-in is offered. */
    public boolean enabled() {
        return !clientId.isEmpty();
    }

    public String clientId() {
        return clientId;
    }

    public User verifyGoogleIdToken(String idToken) {
        JsonNode claims;
        try {
            claims = http.get()
                    .uri("https://oauth2.googleapis.com/tokeninfo?id_token={t}", idToken)
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token");
        }
        String iss = text(claims, "iss");
        boolean issuerOk = "accounts.google.com".equals(iss) || "https://accounts.google.com".equals(iss);
        if (claims == null || !issuerOk || !clientId.equals(text(claims, "aud"))
                || !"true".equals(text(claims, "email_verified"))
                || claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token");
        }
        return signedIn(text(claims, "email").toLowerCase(), text(claims, "name"), text(claims, "picture"),
                bootstrapEmails.contains(text(claims, "email").toLowerCase()));
    }

    /** Local dev only (Google login off): sign in as any email, which also joins the default tenant. */
    public User devSignIn(String email) {
        if (enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String e = email == null ? "" : email.trim().toLowerCase();
        if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
        }
        return signedIn(e, e, "", true);
    }

    /** Bootstrap emails become merchants of the default tenant the first time they sign in. */
    private User signedIn(String email, String name, String picture, boolean bootstrap) {
        Tenant home = tenants.findBySlug(Tenant.DEFAULT_SLUG).orElseThrow();
        if (bootstrap && !members.existsByTenantIdAndEmailIgnoreCase(home.getId(), email)) {
            members.save(new TenantMember(home.getId(), email, name));
        }
        return new User(email, name, picture, null, null, null);
    }

    /** Tenants this email may work in as a merchant. */
    public List<TenantOption> tenantsOf(String email) {
        return members.findByEmailIgnoreCase(email).stream()
                .map(m -> tenants.findById(m.getTenantId()).orElse(null))
                .filter(t -> t != null)
                .map(t -> new TenantOption(t.getId(), t.getName()))
                .toList();
    }

    /** Switches the session to a role; MERCHANT needs a tenant the user belongs to. null role = back to the picker. */
    public User choose(User who, String role, Long tenantId) {
        if (role == null || role.isBlank()) {
            return who.identity();
        }
        if (CUSTOMER.equals(role)) {
            return new User(who.email(), who.name(), who.picture(), CUSTOMER, null, null);
        }
        if (MERCHANT.equals(role)) {
            return merchantOf(who, tenantId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not a member of this organization"));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be CUSTOMER or MERCHANT");
    }

    private Optional<User> merchantOf(User who, Long tenantId) {
        if (tenantId == null || !members.existsByTenantIdAndEmailIgnoreCase(tenantId, who.email())) {
            return Optional.empty();
        }
        return tenants.findById(tenantId)
                .map(t -> new User(who.email(), who.name(), who.picture(), MERCHANT, t.getId(), t.getName()));
    }

    public String issueSession(User user) {
        try {
            String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> claims = new HashMap<>(Map.of(
                    "sub", user.email(), "name", user.name(), "picture", user.picture(),
                    "exp", Instant.now().getEpochSecond() + ttlSeconds));
            if (user.role() != null) {
                claims.put("role", user.role());
            }
            if (user.tenantId() != null) {
                claims.put("tid", user.tenantId());
            }
            String payload = B64.encodeToString(json.writeValueAsBytes(claims));
            String data = header + "." + payload;
            return data + "." + B64.encodeToString(sign(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Validates the token and re-checks the chosen role. A merchant removed from their tenant
     * falls back to an identity-only session, so the UI shows the picker again.
     */
    public Optional<User> parseSession(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) return Optional.empty();
            JsonNode p = json.readTree(B64D.decode(parts[1]));
            if (p.path("exp").asLong() < Instant.now().getEpochSecond()) return Optional.empty();
            User who = new User(p.path("sub").asText(), p.path("name").asText(), p.path("picture").asText(),
                    null, null, null);
            String role = p.path("role").asText(null);
            if (CUSTOMER.equals(role)) {
                return Optional.of(choose(who, CUSTOMER, null));
            }
            if (MERCHANT.equals(role)) {
                Long tid = p.hasNonNull("tid") ? p.path("tid").asLong() : null;
                return Optional.of(merchantOf(who, tid).orElse(who));
            }
            return Optional.of(who);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode n, String field) {
        return n == null || n.path(field).isMissingNode() ? "" : n.path(field).asText("");
    }
}
