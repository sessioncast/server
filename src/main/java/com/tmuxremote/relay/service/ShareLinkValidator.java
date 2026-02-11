package com.tmuxremote.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ShareLinkValidator {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, CachedShareLink> cache = new ConcurrentHashMap<>();

    @Value("${app.platform-api-url:}")
    private String platformApiUrl;

    public ShareLinkInfo validate(String token) {
        // 1. Check cache
        CachedShareLink cached = cache.get(token);
        if (cached != null && !cached.isExpired()) {
            return cached.info;
        }

        // 2. Call Platform API
        if (platformApiUrl == null || platformApiUrl.isBlank()) {
            log.warn("Platform API URL not configured - cannot validate share link");
            return null;
        }

        try {
            String url = platformApiUrl + "/internal/share-links/" + token + "/validate";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                if (json.has("valid") && json.get("valid").asBoolean()) {
                    ShareLinkInfo info = new ShareLinkInfo(
                            json.get("sessionId").asText(),
                            json.get("ownerEmail").asText(),
                            json.get("mode").asText()
                    );
                    // Cache for 1 minute (short TTL since tokens are 10-minute lived)
                    cache.put(token, new CachedShareLink(info, 1));
                    log.debug("Share link validated: token={}..., session={}",
                            token.substring(0, Math.min(6, token.length())), info.getSessionId());
                    return info;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to validate share link with Platform API: {}", e.getMessage());
        }

        // Cache negative result for 30 seconds
        cache.put(token, new CachedShareLink(null, 0));
        return null;
    }

    public void cleanupCache() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    public static class ShareLinkInfo {
        private final String sessionId;
        private final String ownerEmail;
        private final String mode;

        public ShareLinkInfo(String sessionId, String ownerEmail, String mode) {
            this.sessionId = sessionId;
            this.ownerEmail = ownerEmail;
            this.mode = mode;
        }

        public String getSessionId() { return sessionId; }
        public String getOwnerEmail() { return ownerEmail; }
        public String getMode() { return mode; }
    }

    private static class CachedShareLink {
        final ShareLinkInfo info;
        final Instant expiry;

        CachedShareLink(ShareLinkInfo info, int ttlMinutes) {
            this.info = info;
            this.expiry = ttlMinutes > 0
                    ? Instant.now().plus(Duration.ofMinutes(ttlMinutes))
                    : Instant.now().plus(Duration.ofSeconds(30));
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiry);
        }
    }
}
