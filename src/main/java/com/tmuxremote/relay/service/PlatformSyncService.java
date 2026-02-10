package com.tmuxremote.relay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Syncs session state from Relay to Platform API.
 * All calls are fire-and-forget — failures are logged but never block relay operation.
 */
@Slf4j
@Service
public class PlatformSyncService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final SessionManager sessionManager;
    private final String platformApiUrl;

    public PlatformSyncService(
            SessionManager sessionManager,
            @Value("${platform.api.url:}") String platformApiUrl
    ) {
        this.sessionManager = sessionManager;
        this.platformApiUrl = platformApiUrl;
    }

    private boolean isEnabled() {
        return platformApiUrl != null && !platformApiUrl.isBlank();
    }

    @Async
    public void notifySessionConnected(UUID agentId, String sessionName) {
        if (!isEnabled()) return;
        try {
            Map<String, Object> body = Map.of(
                    "agentId", agentId.toString(),
                    "sessionName", sessionName
            );
            restTemplate.postForEntity(
                    platformApiUrl + "/internal/sessions/connected",
                    body,
                    Map.class
            );
            log.debug("Notified platform: session connected agentId={} session={}", agentId, sessionName);
        } catch (Exception e) {
            log.warn("Failed to notify session connected: {}", e.getMessage());
        }
    }

    @Async
    public void notifySessionDisconnected(UUID agentId, String sessionName) {
        if (!isEnabled()) return;
        try {
            Map<String, Object> body = Map.of(
                    "agentId", agentId.toString(),
                    "sessionName", sessionName
            );
            restTemplate.postForEntity(
                    platformApiUrl + "/internal/sessions/disconnected",
                    body,
                    Map.class
            );
            log.debug("Notified platform: session disconnected agentId={} session={}", agentId, sessionName);
        } catch (Exception e) {
            log.warn("Failed to notify session disconnected: {}", e.getMessage());
        }
    }

    /**
     * Heartbeat: send all active sessions to Platform every 60 seconds.
     * Platform will mark any ACTIVE sessions not in the list as DISCONNECTED.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void heartbeat() {
        if (!isEnabled()) return;
        try {
            List<Map<String, Object>> activeSessions = sessionManager.getActiveSessions().stream()
                    .map(s -> {
                        String sessionId = s.sessionId();
                        int slashIdx = sessionId.indexOf('/');
                        String agentPart = slashIdx >= 0 ? sessionId.substring(0, slashIdx) : sessionId;
                        String sessionName = slashIdx >= 0 ? sessionId.substring(slashIdx + 1) : sessionId;
                        return Map.<String, Object>of(
                                "agentId", agentPart,
                                "sessionName", sessionName,
                                "ownerEmail", s.ownerEmail() != null ? s.ownerEmail() : ""
                        );
                    })
                    .filter(s -> !s.get("agentId").toString().isEmpty())
                    .collect(Collectors.toList());

            Map<String, Object> body = Map.of("activeSessions", activeSessions);
            restTemplate.postForEntity(
                    platformApiUrl + "/internal/sessions/heartbeat",
                    body,
                    Map.class
            );
            log.debug("Heartbeat sent: {} active sessions", activeSessions.size());
        } catch (Exception e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }
}
