package com.tmuxremote.relay.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentTokenService {

    // agentToken -> ownerEmail
    private final Map<String, String> tokenToOwner = new ConcurrentHashMap<>();

    // ownerEmail -> Set<agentToken>
    private final Map<String, Set<String>> ownerToTokens = new ConcurrentHashMap<>();

    // Cache for Platform API results with expiry time
    private final Map<String, CachedToken> platformTokenCache = new ConcurrentHashMap<>();

    private final SecureRandom secureRandom = new SecureRandom();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.token-storage-path:./agent-tokens.json}")
    private String tokenStoragePath;

    @Value("${app.platform-api-url:}")
    private String platformApiUrl;

    @Value("${app.platform-token-cache-ttl-minutes:30}")
    private int cacheMinutes;

    @PostConstruct
    public void init() {
        loadTokensFromFile();
        if (platformApiUrl != null && !platformApiUrl.isBlank()) {
            log.info("Platform API integration enabled: {}", platformApiUrl);
        } else {
            log.warn("Platform API URL not configured - using local token storage only");
        }
    }

    private void loadTokensFromFile() {
        File file = new File(tokenStoragePath);
        if (file.exists()) {
            try {
                Map<String, String> saved = objectMapper.readValue(file, new TypeReference<Map<String, String>>() {});
                saved.forEach((token, owner) -> {
                    tokenToOwner.put(token, owner);
                    ownerToTokens.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(token);
                });
                log.info("Loaded {} agent tokens from {}", saved.size(), tokenStoragePath);
            } catch (IOException e) {
                log.error("Failed to load tokens from file", e);
            }
        } else {
            log.info("No token storage file found at {}, starting fresh", tokenStoragePath);
        }
    }

    private void saveTokensToFile() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(tokenStoragePath), tokenToOwner);
            log.debug("Saved {} tokens to {}", tokenToOwner.size(), tokenStoragePath);
        } catch (IOException e) {
            log.error("Failed to save tokens to file", e);
        }
    }

    public String generateToken(String ownerEmail) {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        String token = "agt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        tokenToOwner.put(token, ownerEmail);
        ownerToTokens.computeIfAbsent(ownerEmail, k -> ConcurrentHashMap.newKeySet()).add(token);

        saveTokensToFile();
        log.info("Agent token generated for owner: {}", ownerEmail);
        return token;
    }

    public boolean validateToken(String token) {
        // Check local cache first
        if (tokenToOwner.containsKey(token)) {
            return true;
        }
        // Check Platform API
        return getOwnerByToken(token).isPresent();
    }

    public Optional<String> getOwnerByToken(String token) {
        // 1. Check local memory cache
        String localOwner = tokenToOwner.get(token);
        if (localOwner != null) {
            return Optional.of(localOwner);
        }

        // 2. Check Platform API cache
        CachedToken cached = platformTokenCache.get(token);
        if (cached != null && !cached.isExpired()) {
            if (cached.owner != null) {
                return Optional.of(cached.owner);
            }
            return Optional.empty(); // Cached negative result
        }

        // 3. Call Platform API
        if (platformApiUrl != null && !platformApiUrl.isBlank()) {
            try {
                String owner = validateTokenWithPlatformApi(token);
                if (owner != null) {
                    // Cache positive result
                    platformTokenCache.put(token, new CachedToken(owner, cacheMinutes));
                    log.debug("Token validated via Platform API: {}..., owner: {}",
                            token.substring(0, Math.min(16, token.length())), owner);
                    return Optional.of(owner);
                } else {
                    // Cache negative result for shorter time
                    platformTokenCache.put(token, new CachedToken(null, 5));
                }
            } catch (Exception e) {
                log.warn("Failed to validate token with Platform API: {}", e.getMessage());
            }
        }

        return Optional.empty();
    }

    private String validateTokenWithPlatformApi(String token) {
        try {
            String url = platformApiUrl + "/public/agent-tokens/validate/" + token;
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                if (json.has("valid") && json.get("valid").asBoolean()) {
                    return json.get("owner").asText();
                }
            }
        } catch (Exception e) {
            log.debug("Platform API call failed: {}", e.getMessage());
        }
        return null;
    }

    public Set<String> getTokensByOwner(String ownerEmail) {
        return ownerToTokens.getOrDefault(ownerEmail, Set.of());
    }

    public boolean revokeToken(String token, String ownerEmail) {
        String actualOwner = tokenToOwner.get(token);
        if (actualOwner != null && actualOwner.equals(ownerEmail)) {
            tokenToOwner.remove(token);
            Set<String> tokens = ownerToTokens.get(ownerEmail);
            if (tokens != null) {
                tokens.remove(token);
            }
            platformTokenCache.remove(token);
            saveTokensToFile();
            log.info("Agent token revoked: {} by {}", token.substring(0, 12) + "...", ownerEmail);
            return true;
        }
        return false;
    }

    public Set<String> getOwnerTokenPrefixes(String ownerEmail) {
        return getTokensByOwner(ownerEmail).stream()
                .map(t -> t.substring(0, Math.min(t.length(), 16)) + "...")
                .collect(Collectors.toSet());
    }

    // Clear expired cache entries periodically
    public void cleanupCache() {
        platformTokenCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private static class CachedToken {
        final String owner;
        final Instant expiry;

        CachedToken(String owner, int ttlMinutes) {
            this.owner = owner;
            this.expiry = Instant.now().plus(Duration.ofMinutes(ttlMinutes));
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiry);
        }
    }
}
