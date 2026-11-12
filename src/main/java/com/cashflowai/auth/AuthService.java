package com.cashflowai.auth;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Verifies Google ID tokens and issues/validates our own short-lived HS256 session tokens. */
@Service
public class AuthService {

    public record User(String email, String name, String picture) {}

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper json = new ObjectMapper();
    private final RestClient http = RestClient.create();
    private final String clientId;
    private final List<String> allowedEmails;
    private final long ttlSeconds;
    private final byte[] secret;

    public AuthService(@Value("${cashflow.auth.google-client-id:}") String clientId,
                       @Value("${cashflow.auth.allowed-emails:}") String allowedEmails,
                       @Value("${cashflow.auth.session-hours:12}") long sessionHours,
                       @Value("${cashflow.auth.session-secret:}") String sessionSecret) {
        this.clientId = clientId.trim();
        this.allowedEmails = Arrays.stream(allowedEmails.split(","))
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

    /** Login is only enforced once a Google client id is configured, so local dev and tests keep working. */
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
        String email = text(claims, "email").toLowerCase();
        if (!allowedEmails.isEmpty() && !allowedEmails.contains(email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, email + " is not allowed to use this app");
        }
        return new User(email, text(claims, "name"), text(claims, "picture"));
    }

    public String issueSession(User user) {
        try {
            String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = B64.encodeToString(json.writeValueAsBytes(Map.of(
                    "sub", user.email(), "name", user.name(), "picture", user.picture(),
                    "exp", Instant.now().getEpochSecond() + ttlSeconds)));
            String data = header + "." + payload;
            return data + "." + B64.encodeToString(sign(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<User> parseSession(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) return Optional.empty();
            JsonNode p = json.readTree(B64D.decode(parts[1]));
            if (p.path("exp").asLong() < Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(new User(p.path("sub").asText(), p.path("name").asText(), p.path("picture").asText()));
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
