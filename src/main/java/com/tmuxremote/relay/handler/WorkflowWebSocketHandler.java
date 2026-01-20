package com.tmuxremote.relay.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmuxremote.relay.dto.WorkflowMessage;
import com.tmuxremote.relay.security.JwtTokenProvider;
import com.tmuxremote.relay.service.AgentTokenService;
import com.tmuxremote.relay.service.WorkflowManager;
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

/**
 * WebSocket handler for Workflow Diff-based streaming.
 * Endpoint: /ws/workflow
 *
 * Separate from RelayWebSocketHandler (session capture) for:
 * - Different message formats (Diff vs full screen)
 * - Different buffer requirements (smaller for diffs)
 * - Independent scaling
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final WorkflowManager workflowManager;
    private final AgentTokenService agentTokenService;
    private final JwtTokenProvider jwtTokenProvider;

    // sessionId -> ownerEmail
    private final Map<String, String> sessionOwnerMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("Workflow WebSocket connected: id={}, remote={}",
                session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        try {
            String payload = textMessage.getPayload();
            WorkflowMessage message = objectMapper.readValue(payload, WorkflowMessage.class);

            log.debug("Workflow message: type={}, project={}, agent={}",
                    message.getType(), message.getProjectId(), message.getAgentId());

            switch (message.getType()) {
                // Agent -> Server (from CLI)
                case "registerWorkflow" -> handleRegisterWorkflow(session, message, payload);
                case "workflowOutput" -> handleWorkflowOutput(session, message, payload);
                case "workflowEvent" -> handleWorkflowEvent(session, message, payload);
                case "workflowDecision" -> handleWorkflowDecision(session, message, payload);
                case "workflowReset" -> handleWorkflowReset(session, message, payload);
                case "workflowSync" -> handleWorkflowSync(session, message, payload);

                // Viewer -> Server (from Web UI)
                case "watchWorkflow" -> handleWatchWorkflow(session, message);
                case "userDecision" -> handleUserDecision(session, message, payload);

                default -> log.warn("Unknown workflow message type: {}", message.getType());
            }
        } catch (Exception e) {
            log.error("Failed to handle workflow message", e);
        }
    }

    /**
     * Register a workflow agent connection
     */
    private void handleRegisterWorkflow(WebSocketSession session, WorkflowMessage message, String payload) {
        String projectId = message.getProjectId();
        String agentId = message.getAgentId();

        if (projectId == null) {
            log.warn("registerWorkflow missing projectId");
            return;
        }

        // Extract owner from token in query param
        String ownerEmail = extractOwnerFromSession(session);
        if (ownerEmail != null) {
            sessionOwnerMap.put(session.getId(), ownerEmail);
        }

        workflowManager.registerAgent(projectId, agentId, ownerEmail, session);
        log.info("Workflow agent registered: project={}, agent={}, owner={}",
                projectId, agentId, ownerEmail);
    }

    /**
     * Handle diff-based output from agent
     */
    private void handleWorkflowOutput(WebSocketSession session, WorkflowMessage message, String payload) {
        String projectId = message.getProjectId();
        if (projectId == null) {
            log.warn("workflowOutput missing projectId");
            return;
        }

        int diffCount = message.getDiffs() != null ? message.getDiffs().size() : 0;
        log.info("workflowOutput: project={}, agent={}, diffs={}, totalLines={}",
                projectId, message.getAgentId(), diffCount, message.getTotalLines());

        // Buffer output for late-joining viewers
        if (message.getDiffs() != null && message.getAgentId() != null) {
            for (var diff : message.getDiffs()) {
                if ("add".equals(diff.getAction()) && diff.getContent() != null) {
                    workflowManager.bufferOutput(projectId, message.getAgentId(), diff.getContent());
                }
            }
        }

        // Forward to all viewers watching this project
        workflowManager.broadcastToViewers(projectId, payload);
    }

    /**
     * Handle lifecycle events from agent
     */
    private void handleWorkflowEvent(WebSocketSession session, WorkflowMessage message, String payload) {
        String projectId = message.getProjectId();
        String agentId = message.getAgentId();

        if (projectId == null) {
            log.warn("workflowEvent missing projectId");
            return;
        }

        // Update agent status
        if (agentId != null && message.getEvent() != null) {
            workflowManager.updateAgentStatus(projectId, agentId,
                    message.getEvent(), message.getMessage());
        }

        // Forward to viewers
        workflowManager.broadcastToViewers(projectId, payload);
    }

    /**
     * Handle decision request from agent (needs human intervention)
     */
    private void handleWorkflowDecision(WebSocketSession session, WorkflowMessage message, String payload) {
        String projectId = message.getProjectId();
        if (projectId == null) {
            log.warn("workflowDecision missing projectId");
            return;
        }

        // Store pending decision and forward to viewers
        workflowManager.addPendingDecision(projectId, message);
        workflowManager.broadcastToViewers(projectId, payload);
    }

    /**
     * Register a viewer to watch workflow output
     */
    private void handleWatchWorkflow(WebSocketSession session, WorkflowMessage message) {
        String projectId = message.getProjectId();
        if (projectId == null) {
            log.warn("watchWorkflow missing projectId");
            return;
        }

        String ownerEmail = extractOwnerFromSession(session);
        if (ownerEmail != null) {
            sessionOwnerMap.put(session.getId(), ownerEmail);
        }

        workflowManager.registerViewer(projectId, ownerEmail, session);
        log.info("Workflow viewer registered: project={}, owner={}", projectId, ownerEmail);

        // Send current state snapshot to new viewer
        workflowManager.sendStateSnapshot(projectId, session);
    }

    /**
     * Handle user's decision response
     */
    private void handleUserDecision(WebSocketSession session, WorkflowMessage message, String payload) {
        String projectId = message.getProjectId();
        if (projectId == null) {
            log.warn("userDecision missing projectId");
            return;
        }

        // Forward decision to agent
        workflowManager.forwardToAgent(projectId, payload);
    }

    /**
     * Handle workflow reset (clear buffer for agent)
     */
    private void handleWorkflowReset(WebSocketSession session, WorkflowMessage message, String payload) {
        String projectId = message.getProjectId();
        String agentId = message.getAgentId();

        if (projectId == null) {
            log.warn("workflowReset missing projectId");
            return;
        }

        // Clear buffer and forward to viewers
        workflowManager.resetAgentBuffer(projectId, agentId);
        workflowManager.broadcastToViewers(projectId, payload);
        log.debug("Workflow reset: project={}, agent={}", projectId, agentId);
    }

    /**
     * Handle workflow sync (full state snapshot from agent)
     */
    private void handleWorkflowSync(WebSocketSession session, WorkflowMessage message, String payload) {
        String projectId = message.getProjectId();
        String agentId = message.getAgentId();

        if (projectId == null) {
            log.warn("workflowSync missing projectId");
            return;
        }

        // Update buffer with sync data and forward to viewers
        workflowManager.syncAgentBuffer(projectId, agentId, message);
        workflowManager.broadcastToViewers(projectId, payload);
        log.debug("Workflow sync: project={}, agent={}, lines={}",
                projectId, agentId, message.getTotalLines());
    }

    private String extractOwnerFromSession(WebSocketSession session) {
        try {
            URI uri = session.getUri();
            if (uri != null && uri.getQuery() != null) {
                String query = uri.getQuery();
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && "token".equals(pair[0])) {
                        String token = java.net.URLDecoder.decode(pair[1], "UTF-8");

                        // Local dev: accept email directly
                        if (token.contains("@")) {
                            return token;
                        }

                        // Try as agent token
                        String owner = agentTokenService.getOwnerByToken(token).orElse(null);
                        if (owner != null) return owner;

                        // Try as JWT token
                        if (jwtTokenProvider.validateToken(token)) {
                            return jwtTokenProvider.getEmailFromToken(token);
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
        log.info("Workflow WebSocket disconnected: id={}, status={}", session.getId(), status);
        workflowManager.handleDisconnect(session);
        sessionOwnerMap.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Workflow WebSocket transport error: id={}", session.getId(), exception);
    }
}
