package com.tmuxremote.relay.controller;

import com.tmuxremote.relay.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Development endpoints - only available in dev profile
 */
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
public class DevController {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Generate a dev token for testing
     * Usage: GET /api/dev/token?email=test@example.com&name=TestUser
     */
    @GetMapping("/token")
    public Map<String, String> generateDevToken(
            @RequestParam(defaultValue = "dev@localhost") String email,
            @RequestParam(defaultValue = "Dev User") String name
    ) {
        String token = jwtTokenProvider.generateToken(email, name);
        return Map.of(
                "token", token,
                "email", email,
                "name", name,
                "note", "This is a development token. Do not use in production."
        );
    }
}
