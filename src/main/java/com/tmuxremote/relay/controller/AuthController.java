package com.tmuxremote.relay.controller;

import com.tmuxremote.relay.security.DomainFilter;
import com.tmuxremote.relay.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final DomainFilter domainFilter;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    @GetMapping("/oauth2/success")
    public void oauth2Success(@AuthenticationPrincipal OAuth2User oauth2User,
                              HttpServletResponse response) throws IOException {
        String email = oauth2User.getAttribute("email");
        String name = oauth2User.getAttribute("name");

        log.info("OAuth2 login success: email={}, name={}", email, name);

        if (!domainFilter.isAllowedDomain(email)) {
            log.warn("Domain not allowed for email: {}", email);
            response.sendRedirect(frontendUrl + "/login?error=domain_not_allowed");
            return;
        }

        String token = jwtTokenProvider.generateToken(email, name);
        response.sendRedirect(frontendUrl + "/login?token=" + token);
    }

    @GetMapping("/oauth2/failure")
    public void oauth2Failure(HttpServletResponse response) throws IOException {
        log.error("OAuth2 login failed");
        response.sendRedirect(frontendUrl + "/login?error=oauth_failed");
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.replace("Bearer ", "");
            if (!jwtTokenProvider.validateToken(token)) {
                return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
            }

            String email = jwtTokenProvider.getEmailFromToken(token);
            return ResponseEntity.ok(Map.of(
                "email", email,
                "authenticated", true
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        // JWT is stateless, client just needs to remove the token
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
