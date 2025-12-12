package com.tmuxremote.relay.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmuxremote.relay.dto.Message;
import com.tmuxremote.relay.security.JwtTokenProvider;
import com.tmuxremote.relay.service.AgentTokenService;
import com.tmuxremote.relay.service.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RelayWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final SessionManager sessionManager;
    private final AgentTokenService agentTokenService;
    private final JwtTokenProvider jwtTokenProvider;

    // sessionId -> ownerEmail (extracted from token)
    private final Map<String, String> sessionOwnerMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket connected: id={}, remote={}",
                session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        try {
            String payload = textMessage.getPayload();
            Message message = objectMapper.readValue(payload, Message.class);

            log.debug("Received message: type={}, session={}", message.getType(), message.getSession());

            switch (message.getType()) {
                case "register" -> handleRegister(session, message);
                case "screen", "screenGz" -> handleScreen(message);  // Handle both compressed and uncompressed
                case "keys" -> handleKeys(session, message);
                case "resize" -> handleResize(session, message);
                case "listSessions" -> handleListSessions(session);
                case "createSession" -> handleCreateSession(session, message);
                case "sessionCreated" -> handleSessionCreated(message);
                case "killSession" -> handleKillSession(session, message);
                default -> log.warn("Unknown message type: {}", message.getType());
            }
        } catch (Exception e) {
            log.error("Failed to handle message", e);
        }
    }

    private void handleRegister(WebSocketSession session, Message message) {
        String role = message.getRole();
        String sessionId = message.getSession();

        if ("host".equals(role)) {
            Map<String, String> meta = message.getMeta();
            String label = meta != null ? meta.get("label") : sessionId;
            String machineId = meta != null ? meta.get("machineId") : "unknown";
            String agentToken = meta != null ? meta.get("token") : null;

            // Validate agent token and get owner
            String ownerEmail = null;
            if (agentToken != null) {
                ownerEmail = agentTokenService.getOwnerByToken(agentToken).orElse(null);
                if (ownerEmail == null) {
                    log.warn("Invalid agent token for session: {}", sessionId);
                    // Allow registration but without owner (for backward compatibility)
                }
            }

            if (ownerEmail != null) {
                sessionOwnerMap.put(session.getId(), ownerEmail);
            }
            sessionManager.registerHost(sessionId, label, machineId, ownerEmail, session);
            log.info("Host registered: session={}, machine={}, owner={}", sessionId, machineId, ownerEmail);

        } else if ("viewer".equals(role)) {
            // Get owner from JWT token (passed as query param)
            String ownerEmail = extractOwnerFromSession(session);
            if (ownerEmail != null) {
                sessionOwnerMap.put(session.getId(), ownerEmail);
            }
            sessionManager.registerViewer(sessionId, ownerEmail, session);
            log.info("Viewer registered: session={}, owner={}", sessionId, ownerEmail);
        }
    }

    private void handleScreen(Message message) {
        sessionManager.handleScreen(message.getSession(), message.getPayload(), message.getType());
    }

    private void handleKeys(WebSocketSession session, Message message) {
        sessionManager.handleKeys(message.getSession(), message.getPayload(), session);
    }

    private void handleResize(WebSocketSession session, Message message) {
        Map<String, String> meta = message.getMeta();
        if (meta == null) return;

        String colsStr = meta.get("cols");
        String rowsStr = meta.get("rows");
        if (colsStr == null || rowsStr == null) return;

        try {
            int cols = Integer.parseInt(colsStr);
            int rows = Integer.parseInt(rowsStr);
            log.debug("Resize request: session={}, cols={}, rows={}", message.getSession(), cols, rows);
            sessionManager.handleResize(message.getSession(), cols, rows);
        } catch (NumberFormatException e) {
            log.warn("Invalid resize dimensions: cols={}, rows={}", colsStr, rowsStr);
        }
    }

    private void handleListSessions(WebSocketSession session) {
        String ownerEmail = extractOwnerFromSession(session);
        sessionManager.sendSessionList(session, ownerEmail);
    }

    private void handleCreateSession(WebSocketSession session, Message message) {
        String ownerEmail = extractOwnerFromSession(session);
        String machineId = message.getMeta() != null ? message.getMeta().get("machineId") : null;
        String sessionName = message.getMeta() != null ? message.getMeta().get("sessionName") : null;

        if (machineId == null || sessionName == null) {
            log.warn("createSession missing machineId or sessionName");
            return;
        }

        log.info("Create session request: machine={}, session={}, owner={}", machineId, sessionName, ownerEmail);
        sessionManager.forwardCreateSession(machineId, sessionName, ownerEmail);
    }

    private void handleSessionCreated(Message message) {
        String sessionId = message.getSession();
        log.info("Session created: {}", sessionId);
        // The new session will auto-register via the agent's scanner
    }

    private void handleKillSession(WebSocketSession session, Message message) {
        String ownerEmail = extractOwnerFromSession(session);
        String sessionId = message.getSession();

        if (sessionId == null) {
            log.warn("killSession missing sessionId");
            return;
        }

        log.info("Kill session request: session={}, owner={}", sessionId, ownerEmail);
        sessionManager.forwardKillSession(sessionId, ownerEmail);
    }

    private String extractOwnerFromSession(WebSocketSession session) {
        // First check if we already have it cached
        String cached = sessionOwnerMap.get(session.getId());
        if (cached != null) {
            return cached;
        }

        // Try to extract from query param
        try {
            URI uri = session.getUri();
            if (uri != null && uri.getQuery() != null) {
                String query = uri.getQuery();
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && "token".equals(pair[0])) {
                        String token = java.net.URLDecoder.decode(pair[1], "UTF-8");
                        if (jwtTokenProvider.validateToken(token)) {
                            String email = jwtTokenProvider.getEmailFromToken(token);
                            sessionOwnerMap.put(session.getId(), email);
                            return email;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to extract owner from session", e);
        }
        return null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket disconnected: id={}, status={}", session.getId(), status);
        sessionManager.handleDisconnect(session);
        sessionOwnerMap.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error: id={}", session.getId(), exception);
    }
}
