package com.cashflowai.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Requires a valid session token on /api/**, except login, the customer Fix & Pay portal and PayPal webhooks.
 * /api/auth/** works for any signed-in user, /api/customer/** only as a customer, everything else only as a
 * merchant of the tenant chosen for the session.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String USER_ATTR = "cashflow.user";

    private final AuthService auth;
    private final List<String> origins;

    public AuthFilter(AuthService auth, @Value("${cashflow.cors-origins}") List<String> origins) {
        this.auth = auth;
        this.origins = origins;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equals(request.getMethod())
                || !path.startsWith("/api/")
                || path.equals("/api/auth/config")
                || path.equals("/api/auth/google")
                || path.equals("/api/auth/dev")
                || path.startsWith("/api/portal/")
                || path.equals("/api/paypal/webhooks");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Optional<AuthService.User> user = header != null && header.startsWith("Bearer ")
                ? auth.parseSession(header.substring(7)) : Optional.empty();
        if (user.isEmpty()) {
            if (!auth.enabled()) {
                // Login off (local dev, tests): no token means the local merchant of the default tenant.
                chain.doFilter(request, response);
                return;
            }
            deny(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Please sign in");
            return;
        }
        String path = request.getRequestURI();
        AuthService.User u = user.get();
        boolean allowed = path.startsWith("/api/auth/")
                || (path.startsWith("/api/customer/") ? u.customer() : u.merchant());
        if (!allowed) {
            deny(request, response, HttpServletResponse.SC_FORBIDDEN, u.role() == null
                    ? "Choose to continue as a customer or a merchant"
                    : "Not available as a " + u.role().toLowerCase());
            return;
        }
        request.setAttribute(USER_ATTR, u);
        chain.doFilter(request, response);
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, int status, String message)
            throws IOException {
        // This runs before Spring's CORS handling, so add the header or the browser hides the error.
        // Echo our own configured value, never the raw request header.
        String origin = request.getHeader("Origin");
        origins.stream().filter(o -> o.equals(origin)).findFirst().ifPresent(allowed -> {
            response.setHeader("Access-Control-Allow-Origin", allowed);
            response.setHeader("Vary", "Origin");
        });
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
