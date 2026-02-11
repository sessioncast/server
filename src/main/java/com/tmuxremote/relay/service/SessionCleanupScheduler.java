package com.tmuxremote.relay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupScheduler {

    private final SessionManager sessionManager;
    private final RestTemplate heartbeatRestTemplate = new RestTemplate();

    @Value("${app.platform-api-url:}")
    private String platformApiUrl;

    // Run every 5 minutes
    @Scheduled(fixedRate = 300000)
    public void cleanupStaleSessions() {
        int cleaned = sessionManager.cleanupStaleSessions();
        if (cleaned > 0) {
            log.info("Cleaned up {} stale sessions", cleaned);
        }
    }

    // Log stats every minute
    @Scheduled(fixedRate = 60000)
    public void logStats() {
        SessionManager.Stats stats = sessionManager.getStats();
        log.info("Sessions: total={}, online={}, viewers={}",
                stats.totalSessions(), stats.onlineSessions(), stats.totalViewers());
    }

    // Send agent heartbeat to Platform API every 2 minutes
    @Scheduled(fixedRate = 120000, initialDelay = 30000)
    public void sendAgentHeartbeat() {
        if (platformApiUrl == null || platformApiUrl.isBlank()) {
            return;
        }

        Set<String> tokens = sessionManager.getConnectedAgentTokens();
        if (tokens.isEmpty()) {
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of("tokens", tokens);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            heartbeatRestTemplate.postForEntity(
                    platformApiUrl + "/internal/agents/heartbeat",
                    request,
                    String.class
            );
            log.debug("Agent heartbeat sent for {} tokens", tokens.size());
        } catch (Exception e) {
            log.warn("Failed to send agent heartbeat: {}", e.getMessage());
        }
    }
}
