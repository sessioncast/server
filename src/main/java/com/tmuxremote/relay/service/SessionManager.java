package com.tmuxremote.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmuxremote.relay.dto.Message;
import com.tmuxremote.relay.dto.SessionInfo;
import com.tmuxremote.relay.dto.SessionListItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManager {

    private final ObjectMapper objectMapper;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> viewerSessionMap = new ConcurrentHashMap<>();
    // viewerId -> ownerEmail (for filtering sessions)
    private final Map<String, String> viewerOwnerMap = new ConcurrentHashMap<>();
    // Track when sessions went offline
    private final Map<String, Instant> offlineTimestamps = new ConcurrentHashMap<>();

    // Stale session threshold (30 minutes)
    private static final long STALE_SESSION_THRESHOLD_MS = 30 * 60 * 1000;

    public void registerHost(String sessionId, String label, String machineId, String ownerEmail, WebSocketSession wsSession) {
        SessionInfo existing = sessions.get(sessionId);
        if (existing != null && existing.getHostSession() != null) {
            log.warn("Host already registered for session: {}, replacing", sessionId);
        }

        SessionInfo sessionInfo = SessionInfo.create(sessionId, label, machineId, ownerEmail, wsSession);
        if (existing != null) {
            sessionInfo.setViewers(existing.getViewers());
        }
        sessions.put(sessionId, sessionInfo);
        offlineTimestamps.remove(sessionId); // Clear offline timestamp when host connects

        log.info("Host registered: session={}, machine={}, owner={}", sessionId, machineId, ownerEmail);
        broadcastSessionListToOwner(ownerEmail);
    }

    public void registerViewer(String sessionId, String ownerEmail, WebSocketSession wsSession) {
        viewerSessionMap.put(wsSession.getId(), wsSession);
        viewerOwnerMap.put(wsSession.getId(), ownerEmail);

        // Remove viewer from all other sessions first
        sessions.values().forEach(info -> info.getViewers().remove(wsSession));

        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo != null) {
            // Check if viewer owns this session
            if (ownerEmail != null && !ownerEmail.equals(sessionInfo.getOwnerEmail())) {
                log.warn("Viewer {} tried to access session {} owned by {}", ownerEmail, sessionId, sessionInfo.getOwnerEmail());
                return;
            }
            sessionInfo.getViewers().add(wsSession);
            log.info("Viewer registered: session={}, viewerId={}, owner={}", sessionId, wsSession.getId(), ownerEmail);
        } else {
            SessionInfo newSession = SessionInfo.builder()
                    .id(sessionId)
                    .status("offline")
                    .ownerEmail(ownerEmail)
                    .viewers(ConcurrentHashMap.newKeySet())
                    .build();
            newSession.getViewers().add(wsSession);
            sessions.put(sessionId, newSession);
            log.info("Viewer registered for offline session: session={}", sessionId);
        }
    }

    public void handleScreen(String sessionId, String payload, String type) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null) {
            log.warn("Screen data for unknown session: {}", sessionId);
            return;
        }

        // Forward with original type (screen or screenGz)
        Message screenMessage = Message.builder()
                .type(type)
                .session(sessionId)
                .payload(payload)
                .build();

        broadcastToViewers(sessionInfo, screenMessage);
    }

    public void handleKeys(String sessionId, String payload, WebSocketSession viewerSession) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null || sessionInfo.getHostSession() == null) {
            log.warn("Keys for unavailable session: {}", sessionId);
            return;
        }

        Message keysMessage = Message.builder()
                .type("keys")
                .session(sessionId)
                .payload(payload)
                .build();

        sendMessage(sessionInfo.getHostSession(), keysMessage);
    }

    public void handleResize(String sessionId, int cols, int rows) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null || sessionInfo.getHostSession() == null) {
            log.warn("Resize for unavailable session: {}", sessionId);
            return;
        }

        Message resizeMessage = Message.builder()
                .type("resize")
                .session(sessionId)
                .meta(Map.of("cols", String.valueOf(cols), "rows", String.valueOf(rows)))
                .build();

        sendMessage(sessionInfo.getHostSession(), resizeMessage);
        log.debug("Forwarded resize to host: session={}, cols={}, rows={}", sessionId, cols, rows);
    }

    public void sendSessionList(WebSocketSession wsSession, String ownerEmail) {
        List<SessionListItem> sessionList = sessions.values().stream()
                // Only show sessions that belong to this user (exact match required)
                .filter(info -> ownerEmail != null && ownerEmail.equals(info.getOwnerEmail()))
                .map(SessionListItem::from)
                .toList();

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "sessionList",
                    "sessions", sessionList
            ));
            synchronized (wsSession) {
                if (wsSession.isOpen()) {
                    wsSession.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.error("Failed to send session list", e);
        }
    }

    public void handleDisconnect(WebSocketSession wsSession) {
        sessions.forEach((sessionId, info) -> {
            if (info.getHostSession() != null &&
                    info.getHostSession().getId().equals(wsSession.getId())) {
                info.setHostSession(null);
                info.setStatus("offline");
                offlineTimestamps.put(sessionId, Instant.now()); // Track offline time
                log.info("Host disconnected: session={}", sessionId);

                broadcastSessionStatus(sessionId, "offline");
                if (info.getOwnerEmail() != null) {
                    broadcastSessionListToOwner(info.getOwnerEmail());
                }
            }

            info.getViewers().remove(wsSession);
        });

        viewerSessionMap.remove(wsSession.getId());
        viewerOwnerMap.remove(wsSession.getId());
    }

    /**
     * Remove sessions that have been offline for longer than threshold
     * @return number of sessions cleaned up
     */
    public int cleanupStaleSessions() {
        AtomicInteger cleaned = new AtomicInteger(0);
        Instant threshold = Instant.now().minusMillis(STALE_SESSION_THRESHOLD_MS);

        offlineTimestamps.forEach((sessionId, offlineTime) -> {
            if (offlineTime.isBefore(threshold)) {
                SessionInfo info = sessions.get(sessionId);
                if (info != null && "offline".equals(info.getStatus())) {
                    sessions.remove(sessionId);
                    offlineTimestamps.remove(sessionId);
                    cleaned.incrementAndGet();
                    log.info("Removed stale session: {}", sessionId);
                }
            }
        });

        // Also clean up closed viewer sessions
        viewerSessionMap.entrySet().removeIf(entry -> {
            if (!entry.getValue().isOpen()) {
                viewerOwnerMap.remove(entry.getKey());
                return true;
            }
            return false;
        });

        return cleaned.get();
    }

    /**
     * Get current stats for monitoring
     */
    public Stats getStats() {
        long online = sessions.values().stream()
                .filter(s -> "online".equals(s.getStatus()))
                .count();
        int viewers = viewerSessionMap.size();
        return new Stats(sessions.size(), (int) online, viewers);
    }

    public record Stats(int totalSessions, int onlineSessions, int totalViewers) {}

    private void broadcastToViewers(SessionInfo sessionInfo, Message message) {
        sessionInfo.getViewers().forEach(viewer -> sendMessage(viewer, message));
    }

    private void broadcastSessionStatus(String sessionId, String status) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "sessionStatus",
                    "session", sessionId,
                    "status", status
            ));

            SessionInfo sessionInfo = sessions.get(sessionId);
            if (sessionInfo != null) {
                sessionInfo.getViewers().forEach(viewer -> {
                    try {
                        synchronized (viewer) {
                            if (viewer.isOpen()) {
                                viewer.sendMessage(new TextMessage(json));
                            }
                        }
                    } catch (IOException e) {
                        log.error("Failed to send status to viewer", e);
                    }
                });
            }
        } catch (IOException e) {
            log.error("Failed to serialize status message", e);
        }
    }

    private void broadcastSessionListToOwner(String ownerEmail) {
        List<SessionListItem> sessionList = sessions.values().stream()
                // Only show sessions that belong to this user (exact match required)
                .filter(info -> ownerEmail != null && ownerEmail.equals(info.getOwnerEmail()))
                .map(SessionListItem::from)
                .toList();

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "sessionList",
                    "sessions", sessionList
            ));

            viewerSessionMap.forEach((viewerId, viewer) -> {
                String viewerOwner = viewerOwnerMap.get(viewerId);
                if (ownerEmail == null || ownerEmail.equals(viewerOwner)) {
                    try {
                        synchronized (viewer) {
                            if (viewer.isOpen()) {
                                viewer.sendMessage(new TextMessage(json));
                            }
                        }
                    } catch (IOException e) {
                        log.error("Failed to broadcast session list", e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Failed to serialize session list", e);
        }
    }

    private void sendMessage(WebSocketSession session, Message message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.error("Failed to send message to session {}", session.getId(), e);
        }
    }

    public void forwardCreateSession(String machineId, String sessionName, String ownerEmail) {
        // Find any host session for this machineId owned by this user
        sessions.values().stream()
                .filter(info -> info.getId().startsWith(machineId + "/"))
                .filter(info -> ownerEmail == null || ownerEmail.equals(info.getOwnerEmail()))
                .filter(info -> info.getHostSession() != null && info.getHostSession().isOpen())
                .findFirst()
                .ifPresentOrElse(
                        info -> {
                            Message createMsg = Message.builder()
                                    .type("createSession")
                                    .meta(Map.of("sessionName", sessionName))
                                    .build();
                            sendMessage(info.getHostSession(), createMsg);
                            log.info("Forwarded createSession to machine: {}, session: {}", machineId, sessionName);
                        },
                        () -> log.warn("No active host found for machine: {}", machineId)
                );
    }

    public List<String> getActiveMachines(String ownerEmail) {
        return sessions.values().stream()
                .filter(info -> ownerEmail == null || ownerEmail.equals(info.getOwnerEmail()))
                .filter(info -> info.getHostSession() != null && info.getHostSession().isOpen())
                .map(info -> {
                    String id = info.getId();
                    int slashIdx = id.indexOf('/');
                    return slashIdx > 0 ? id.substring(0, slashIdx) : id;
                })
                .distinct()
                .toList();
    }

    public void forwardKillSession(String sessionId, String ownerEmail) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null) {
            log.warn("killSession: session not found: {}", sessionId);
            return;
        }

        // Verify ownership
        if (ownerEmail != null && !ownerEmail.equals(sessionInfo.getOwnerEmail())) {
            log.warn("killSession: user {} not authorized for session {} owned by {}",
                    ownerEmail, sessionId, sessionInfo.getOwnerEmail());
            return;
        }

        if (sessionInfo.getHostSession() == null || !sessionInfo.getHostSession().isOpen()) {
            log.warn("killSession: host not connected for session: {}", sessionId);
            return;
        }

        Message killMsg = Message.builder()
                .type("killSession")
                .session(sessionId)
                .build();
        sendMessage(sessionInfo.getHostSession(), killMsg);
        log.info("Forwarded killSession to host: session={}", sessionId);
    }
}
