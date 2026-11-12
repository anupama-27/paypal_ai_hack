package com.cashflowai.auth;

import com.cashflowai.auth.AuthService.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    /** Tells the UI whether login is required and which Google client id to use. */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", auth.enabled(), "googleClientId", auth.clientId());
    }

    @PostMapping("/google")
    public Map<String, Object> google(@RequestBody Map<String, String> body) {
        User user = auth.verifyGoogleIdToken(body.getOrDefault("credential", ""));
        return Map.of("token", auth.issueSession(user), "user", user);
    }

    @GetMapping("/me")
    public User me(HttpServletRequest request) {
        return (User) request.getAttribute(AuthFilter.USER_ATTR);
    }
}
