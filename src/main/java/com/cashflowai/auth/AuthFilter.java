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

/** Requires a valid session token on /api/**, except login, the customer Fix & Pay portal and PayPal webhooks. */
@Component
public class AuthFilter extends OncePerRequestFilter {

    static final String USER_ATTR = "cashflow.user";

    private final AuthService auth;
    private final List<String> origins;

    public AuthFilter(AuthService auth, @Value("${cashflow.cors-origins}") List<String> origins) {
        this.auth = auth;
        this.origins = origins;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !auth.enabled()
                || "OPTIONS".equals(request.getMethod())
                || !path.startsWith("/api/")
                || path.equals("/api/auth/config")
                || path.equals("/api/auth/google")
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
            // This runs before Spring's CORS handling, so add the header or the browser hides the 401.
            String origin = request.getHeader("Origin");
            if (origin != null && origins.contains(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Please sign in\"}");
            return;
        }
        request.setAttribute(USER_ATTR, user.get());
        chain.doFilter(request, response);
    }
}
