package com.tmuxremote.relay.controller;

import com.tmuxremote.relay.security.JwtTokenProvider;
import com.tmuxremote.relay.service.AgentTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final AgentTokenService agentTokenService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/generate")
    public ResponseEntity<?> generateToken(@RequestHeader("Authorization") String authHeader) {
        String email = extractEmail(authHeader);
        if (email == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }

        String agentToken = agentTokenService.generateToken(email);
        log.info("Agent token generated for: {}", email);

        return ResponseEntity.ok(Map.of(
            "token", agentToken,
            "message", "Add this token to your agent config (~/.tmux-remote.yml)"
        ));
    }

    @GetMapping
    public ResponseEntity<?> listTokens(@RequestHeader("Authorization") String authHeader) {
        String email = extractEmail(authHeader);
        if (email == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }

        Set<String> tokens = agentTokenService.getTokensByOwner(email);
        return ResponseEntity.ok(Map.of(
            "tokens", tokens,
            "count", tokens.size()
        ));
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<?> revokeToken(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String token) {
        String email = extractEmail(authHeader);
        if (email == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid token"));
        }

        boolean revoked = agentTokenService.revokeToken(token, email);
        if (revoked) {
            return ResponseEntity.ok(Map.of("message", "Token revoked successfully"));
        } else {
            return ResponseEntity.status(403).body(Map.of("error", "Token not found or not owned by you"));
        }
    }

    private String extractEmail(String authHeader) {
        try {
            String token = authHeader.replace("Bearer ", "");
            if (jwtTokenProvider.validateToken(token)) {
                return jwtTokenProvider.getEmailFromToken(token);
            }
        } catch (Exception e) {
            log.error("Failed to extract email from token", e);
        }
        return null;
    }
}
