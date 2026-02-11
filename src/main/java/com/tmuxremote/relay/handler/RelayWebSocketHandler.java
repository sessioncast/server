package com.tmuxremote.relay.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmuxremote.relay.dto.Message;
import com.tmuxremote.relay.security.JwtTokenProvider;
import com.tmuxremote.relay.service.AgentTokenService;
import com.tmuxremote.relay.service.SessionManager;
import com.tmuxremote.relay.service.ShareLinkValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RelayWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final SessionManager sessionManager;
    private final AgentTokenService agentTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final ShareLinkValidator shareLinkValidator;

    // sessionId -> ownerEmail (extracted from token)
    private final Map<String, String> sessionOwnerMap = new ConcurrentHashMap<>();

    // Track which sessions are shared viewers (no input allowed)
    private final Map<String, Boolean> sharedViewerSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket connected: id={}, remote={}",
                session.getId(), session.getRemoteAddress());

        // Check for shareToken query parameter
        try {
            URI uri = session.getUri();
            if (uri != null && uri.getQuery() != null) {
                String shareToken = getQueryParam(uri.getQuery(), "shareToken");
                if (shareToken != null) {
                    ShareLinkValidator.ShareLinkInfo info = shareLinkValidator.validate(shareToken);
                    if (info == null) {
                        session.close(new CloseStatus(4403, "Invalid share token"));
                        return;
                    }
                    // Register as shared (read-only) viewer
                    sharedViewerSessions.put(session.getId(), true);
                    sessionManager.registerSharedViewer(info.getSessionId(), info.getOwnerEmail(), session);
                    log.info("Shared viewer registered: session={}, shareToken={}...",
                            info.getSessionId(), shareToken.substring(0, Math.min(6, shareToken.length())));
                }
            }
        } catch (Exception e) {
            log.error("Failed to process share token", e);
        }
    }

    private String getQueryParam(String query, String paramName) {
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && paramName.equals(pair[0])) {
                try {
                    return java.net.URLDecoder.decode(pair[1], "UTF-8");
                } catch (Exception e) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        try {
            // Shared viewers are read-only — ignore all their messages
            if (sharedViewerSessions.containsKey(session.getId())) {
                log.debug("Ignoring message from shared viewer: {}", session.getId());
                return;
            }

            String payload = textMessage.getPayload();
            Message message = objectMapper.readValue(payload, Message.class);

            log.info("Received message: type={}, session={}", message.getType(), message.getSession());

            switch (message.getType()) {
                case "register" -> handleRegister(session, message);
                case "screen", "screenGz" -> handleScreen(message);  // Handle both compressed and uncompressed
                case "keys" -> handleKeys(session, message);
                case "resize" -> handleResize(session, message);
                case "listSessions" -> handleListSessions(session);
                case "createSession" -> handleCreateSession(session, message);
                case "sessionCreated" -> handleSessionCreated(message);
                case "killSession" -> handleKillSession(session, message);
                // Project-related messages
                case "registerProject" -> handleRegisterProject(session, message);
                case "projectStatus" -> handleProjectStatus(session, message);
                case "listProjects" -> handleListProjects(session);
                case "watchProject" -> handleWatchProject(session, message);
                case "startWorkflow" -> handleStartWorkflow(session, message);
                case "analyzeMission" -> handleAnalyzeMission(session, message);
                case "missionAnalysis" -> handleMissionAnalysisResponse(session, message);
                case "addSource" -> handleAddSource(session, message);
                case "addSourceResult" -> handleAddSourceResult(session, message);
                case "updateSources" -> handleUpdateSources(session, message);
                case "deleteProject" -> handleDeleteProject(session, message);
                case "ping" -> {} // Heartbeat - no action needed
                case "file_view" -> handleFileView(message);  // Forward file content to viewers
                case "requestFileView" -> handleRequestFileView(session, message);  // Forward file request to host
                // Note: workflowOutput/workflowEvent/workflowDecision use /ws/workflow endpoint
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
                // Local dev: accept email directly as token (same as viewer logic)
                if (agentToken.contains("@")) {
                    ownerEmail = agentToken;
                } else {
                    ownerEmail = agentTokenService.getOwnerByToken(agentToken).orElse(null);
                    if (ownerEmail == null) {
                        log.warn("Invalid agent token for session: {}", sessionId);
                        // Allow registration but without owner (for backward compatibility)
                    }
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

    private void handleFileView(Message message) {
        // Forward file_view message to all viewers of this session
        String sessionId = message.getSession();
        if (sessionId != null) {
            sessionManager.handleFileView(sessionId, message);
        }
    }

    private void handleRequestFileView(WebSocketSession session, Message message) {
        // Forward file view request from viewer to host
        String sessionId = message.getSession();
        Map<String, String> meta = message.getMeta();
        // Support both "path" (from web client) and "filePath" keys
        String filePath = meta != null ? (meta.get("path") != null ? meta.get("path") : meta.get("filePath")) : null;

        if (sessionId == null || filePath == null) {
            log.warn("requestFileView missing sessionId or filePath");
            return;
        }

        log.info("Request file view: session={}, filePath={}", sessionId, filePath);
        sessionManager.forwardRequestFileView(sessionId, filePath);
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

    // ========== Project Handlers ==========

    private void handleRegisterProject(WebSocketSession session, Message message) {
        Map<String, String> meta = message.getMeta();
        if (meta == null) {
            log.warn("registerProject missing meta");
            return;
        }

        String projectId = meta.get("projectId");
        String name = meta.get("name");
        String mission = meta.get("mission");
        String machineId = meta.get("machineId");
        String agentToken = meta.get("token");

        if (projectId == null || name == null) {
            log.warn("registerProject missing projectId or name");
            return;
        }

        // Validate token and get owner
        String ownerEmail = null;
        if (agentToken != null) {
            // Local dev: accept email directly as token
            if (agentToken.contains("@")) {
                ownerEmail = agentToken;
            } else {
                // First try as agent token
                ownerEmail = agentTokenService.getOwnerByToken(agentToken).orElse(null);

                // If not an agent token, try as JWT token
                if (ownerEmail == null) {
                    try {
                        if (jwtTokenProvider.validateToken(agentToken)) {
                            ownerEmail = jwtTokenProvider.getEmailFromToken(agentToken);
                        }
                    } catch (Exception e) {
                        log.warn("Invalid token for project: {}, error: {}", projectId, e.getMessage());
                    }
                }
            }
        }

        if (ownerEmail != null) {
            sessionOwnerMap.put(session.getId(), ownerEmail);
        }

        // Parse sources from payload if present
        List<com.tmuxremote.relay.dto.ProjectInfo.SourceInfo> sources = null;
        if (message.getPayload() != null && !message.getPayload().isBlank()) {
            try {
                sources = objectMapper.readValue(message.getPayload(),
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class, com.tmuxremote.relay.dto.ProjectInfo.SourceInfo.class));
            } catch (Exception e) {
                log.warn("Failed to parse sources from payload: {}", e.getMessage());
            }
        }

        sessionManager.registerProject(projectId, name, mission, machineId, ownerEmail, session, sources);
        log.info("Project registered: projectId={}, name={}, owner={}, sources={}",
                projectId, name, ownerEmail, sources != null ? sources.size() : 0);
    }

    /**
     * Handle updateSources message from agent - updates sources list for a project
     */
    private void handleUpdateSources(WebSocketSession session, Message message) {
        Map<String, String> meta = message.getMeta();
        if (meta == null) {
            log.warn("updateSources missing meta");
            return;
        }

        String projectId = meta.get("projectId");
        if (projectId == null) {
            log.warn("updateSources missing projectId");
            return;
        }

        // Parse sources from payload
        List<com.tmuxremote.relay.dto.ProjectInfo.SourceInfo> sources = null;
        if (message.getPayload() != null && !message.getPayload().isBlank()) {
            try {
                sources = objectMapper.readValue(message.getPayload(),
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class, com.tmuxremote.relay.dto.ProjectInfo.SourceInfo.class));
            } catch (Exception e) {
                log.warn("Failed to parse sources from updateSources payload: {}", e.getMessage());
                return;
            }
        }

        sessionManager.updateProjectSources(projectId, sources);
        log.info("Sources updated: projectId={}, sources={}", projectId, sources != null ? sources.size() : 0);
    }

    private void handleProjectStatus(WebSocketSession session, Message message) {
        Map<String, String> meta = message.getMeta();
        if (meta == null) {
            log.warn("projectStatus missing meta");
            return;
        }

        String projectId = meta.get("projectId");
        String status = meta.get("status");

        if (projectId == null) {
            log.warn("projectStatus missing projectId");
            return;
        }

        // Parse agent statuses from payload if present
        // The payload can contain JSON with agent statuses
        sessionManager.updateProjectStatus(projectId, status, null);
        log.info("Project status update: projectId={}, status={}", projectId, status);
    }

    private void handleListProjects(WebSocketSession session) {
        String ownerEmail = extractOwnerFromSession(session);
        if (ownerEmail == null) {
            log.warn("listProjects: unauthorized request (invalid/expired token)");
            return;
        }
        sessionManager.registerProjectViewer(ownerEmail, session);
        sessionManager.sendProjectList(session, ownerEmail);
    }

    private void handleWatchProject(WebSocketSession session, Message message) {
        String ownerEmail = extractOwnerFromSession(session);
        if (ownerEmail == null) {
            log.warn("watchProject: unauthorized request (invalid/expired token)");
            return;
        }
        Map<String, String> meta = message.getMeta();
        String projectId = meta != null ? meta.get("projectId") : null;

        if (projectId == null) {
            log.warn("watchProject missing projectId");
            return;
        }

        sessionManager.registerProjectDetailViewer(projectId, ownerEmail, session);
        log.info("Watch project request: projectId={}, owner={}", projectId, ownerEmail);
    }

    private void handleDeleteProject(WebSocketSession session, Message message) {
        String ownerEmail = extractOwnerFromSession(session);
        if (ownerEmail == null) {
            log.warn("deleteProject: unauthorized request (invalid/expired token)");
            return;
        }
        Map<String, String> meta = message.getMeta();
        String projectId = meta != null ? meta.get("projectId") : null;

        if (projectId == null) {
            log.warn("deleteProject missing projectId");
            return;
        }

        log.info("Delete project request: projectId={}, owner={}", projectId, ownerEmail);
        sessionManager.deleteProject(projectId, ownerEmail);
    }

    private void handleStartWorkflow(WebSocketSession session, Message message) {
        String ownerEmail = extractOwnerFromSession(session);
        if (ownerEmail == null) {
            log.warn("startWorkflow: unauthorized request (invalid/expired token)");
            return;
        }
        Map<String, String> meta = message.getMeta();
        String projectId = meta != null ? meta.get("projectId") : null;
        String mission = meta != null ? meta.get("mission") : null;

        if (projectId == null) {
            log.warn("startWorkflow missing projectId");
            return;
        }

        sessionManager.startWorkflow(projectId, mission, ownerEmail);
        log.info("Start workflow request: projectId={}, owner={}, mission={}",
                projectId, ownerEmail, mission != null ? mission.substring(0, Math.min(50, mission.length())) : null);
    }

    private void handleAnalyzeMission(WebSocketSession session, Message message) {
        String ownerEmail = extractOwnerFromSession(session);
        if (ownerEmail == null) {
            log.warn("analyzeMission: unauthorized request (invalid/expired token)");
            sendAnalysisError(session, message.getRequestId(), "Unauthorized");
            return;
        }
        Map<String, String> meta = message.getMeta();
        String projectId = meta != null ? meta.get("projectId") : null;
        String mission = meta != null ? meta.get("mission") : null;
        String requestId = message.getRequestId();

        if (projectId == null || mission == null || requestId == null) {
            log.warn("analyzeMission missing projectId, mission, or requestId");
            sendAnalysisError(session, requestId, "Missing required fields");
            return;
        }

        log.info("Analyze mission request: projectId={}, requestId={}, mission={}",
                projectId, requestId, mission.substring(0, Math.min(50, mission.length())));

        // Forward to agent for Claude Code analysis
        sessionManager.forwardAnalyzeMission(projectId, requestId, mission, meta, ownerEmail, session);
    }

    private void handleMissionAnalysisResponse(WebSocketSession session, Message message) {
        // Response from agent with analyzed steps - forward to requesting viewer
        String requestId = message.getRequestId();
        if (requestId == null) {
            log.warn("missionAnalysis response missing requestId");
            return;
        }

        sessionManager.forwardAnalysisResponse(message);
        log.info("Mission analysis response: requestId={}", requestId);
    }

    private void sendAnalysisError(WebSocketSession session, String requestId, String error) {
        try {
            Message errorMsg = new Message();
            errorMsg.setType("missionAnalysis");
            errorMsg.setRequestId(requestId);
            errorMsg.setError(error);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorMsg)));
        } catch (Exception e) {
            log.error("Failed to send analysis error", e);
        }
    }

    /**
     * Handle addSource request from web client
     * Forwards to agent to clone/copy source into work folder
     * meta: { projectId, sourceType (git|gh|cp), sourceUrl, targetFolder }
     */
    private void handleAddSource(WebSocketSession session, Message message) {
        String ownerEmail = extractOwnerFromSession(session);
        if (ownerEmail == null) {
            log.warn("addSource: unauthorized request (invalid/expired token)");
            sendAddSourceError(session, message.getRequestId(), "Unauthorized");
            return;
        }
        Map<String, String> meta = message.getMeta();
        String projectId = meta != null ? meta.get("projectId") : null;
        String sourceType = meta != null ? meta.get("sourceType") : null;
        String sourceUrl = meta != null ? meta.get("sourceUrl") : null;
        String targetFolder = meta != null ? meta.get("targetFolder") : null;
        String requestId = message.getRequestId();

        if (projectId == null || sourceType == null || sourceUrl == null || requestId == null) {
            log.warn("addSource missing required fields");
            sendAddSourceError(session, requestId, "Missing required fields");
            return;
        }

        log.info("Add source request: projectId={}, type={}, url={}, target={}",
                projectId, sourceType, sourceUrl, targetFolder);

        sessionManager.forwardAddSource(projectId, requestId, sourceType, sourceUrl, targetFolder, ownerEmail, session);
    }

    private void handleAddSourceResult(WebSocketSession session, Message message) {
        String requestId = message.getRequestId();
        if (requestId == null) {
            log.warn("addSourceResult missing requestId");
            return;
        }

        sessionManager.forwardAddSourceResult(message);
        log.info("Add source result: requestId={}, error={}", requestId, message.getError());
    }

    private void sendAddSourceError(WebSocketSession session, String requestId, String error) {
        try {
            Message errorMsg = new Message();
            errorMsg.setType("addSourceResult");
            errorMsg.setRequestId(requestId);
            errorMsg.setError(error);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorMsg)));
        } catch (Exception e) {
            log.error("Failed to send addSource error", e);
        }
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

                        // Local dev: accept email directly as token
                        if (token.contains("@")) {
                            sessionOwnerMap.put(session.getId(), token);
                            return token;
                        }

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
        sessionManager.handleProjectDisconnect(session);
        sessionOwnerMap.remove(session.getId());
        sharedViewerSessions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error: id={}", session.getId(), exception);
    }
}
