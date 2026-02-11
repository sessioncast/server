package com.tmuxremote.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmuxremote.relay.dto.Message;
import com.tmuxremote.relay.dto.ProjectInfo;
import com.tmuxremote.relay.dto.ProjectListItem;
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
import java.util.Set;
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

    // Project management
    private final Map<String, ProjectInfo> projects = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> projectViewerSessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> projectViewerOwnerMap = new ConcurrentHashMap<>();

    // Stale session threshold (30 minutes)
    private static final long STALE_SESSION_THRESHOLD_MS = 30 * 60 * 1000;

    // Project session prefix
    private static final String PROJECT_SESSION_PREFIX = "proj_";

    // Shared viewers: sessionId -> Set<WebSocketSession> (read-only viewers from share links)
    private final Map<String, Set<WebSocketSession>> sharedViewers = new ConcurrentHashMap<>();
    // Track last screen data per session for sending snapshot to new shared viewers
    private final Map<String, Message> lastScreenMessages = new ConcurrentHashMap<>();
    // Track terminal size per session: sessionId -> [cols, rows]
    private final Map<String, int[]> terminalSizes = new ConcurrentHashMap<>();

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

    public void registerSharedViewer(String sessionId, String ownerEmail, WebSocketSession wsSession) {
        // Find matching session by looking for sessions owned by this email
        // sessionId from Platform API is a UUID, but relay uses "machineId/sessionName"
        // So we need to find the relay session that matches the owner
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null) {
            // Try to find session by owner email (Platform sessionId may not match relay sessionId)
            // For shared viewers, we find any active session for this owner
            sessionInfo = sessions.values().stream()
                    .filter(info -> ownerEmail != null && ownerEmail.equals(info.getOwnerEmail()))
                    .filter(info -> "online".equals(info.getStatus()))
                    .findFirst()
                    .orElse(null);
        }

        if (sessionInfo != null) {
            String relaySessionId = sessionInfo.getId();
            sharedViewers.computeIfAbsent(relaySessionId, k -> ConcurrentHashMap.newKeySet()).add(wsSession);
            log.info("Shared viewer registered: session={}, viewerId={}", relaySessionId, wsSession.getId());

            // Send terminal size first so viewer can set up correct dimensions
            int[] size = terminalSizes.get(relaySessionId);
            if (size != null) {
                Message sizeMessage = Message.builder()
                        .type("terminalSize")
                        .session(relaySessionId)
                        .meta(Map.of("cols", String.valueOf(size[0]), "rows", String.valueOf(size[1])))
                        .build();
                sendMessage(wsSession, sizeMessage);
            }

            // Send current screen snapshot if available
            Message lastScreen = lastScreenMessages.get(relaySessionId);
            if (lastScreen != null) {
                sendMessage(wsSession, lastScreen);
            }
        } else {
            log.warn("No active session found for shared viewer: sessionId={}, owner={}", sessionId, ownerEmail);
        }
    }

    public void handleScreen(String sessionId, String payload, String type, Map<String, String> meta) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null) {
            log.warn("Screen data for unknown session: {}", sessionId);
            return;
        }

        // Forward with original type (screen or screenGz), preserving meta (e.g., pane ID)
        Message screenMessage = Message.builder()
                .type(type)
                .session(sessionId)
                .payload(payload)
                .meta(meta)
                .build();

        broadcastToViewers(sessionInfo, screenMessage);

        // Cache last screen for new shared viewers & broadcast to shared viewers
        // Only cache non-pane screens as the main snapshot
        if (meta == null || meta.get("pane") == null) {
            lastScreenMessages.put(sessionId, screenMessage);
        }
        broadcastToSharedViewers(sessionId, screenMessage);
    }

    public void handleFileView(String sessionId, Message message) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null) {
            log.warn("File view for unknown session: {}", sessionId);
            return;
        }

        // Forward file_view message to all viewers
        log.info("Broadcasting file_view to {} viewers for session {}",
                sessionInfo.getViewers().size(), sessionId);
        broadcastToViewers(sessionInfo, message);
    }

    public void forwardRequestFileView(String sessionId, String filePath) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null || sessionInfo.getHostSession() == null) {
            log.warn("Request file view for unavailable session: {}", sessionId);
            return;
        }

        Message requestMessage = Message.builder()
                .type("requestFileView")
                .session(sessionId)
                .meta(Map.of("filePath", filePath))
                .build();

        sendMessage(sessionInfo.getHostSession(), requestMessage);
        log.info("Forwarded requestFileView to host: session={}, filePath={}", sessionId, filePath);
    }

    public void handleKeys(String sessionId, String payload, WebSocketSession viewerSession, Map<String, String> meta) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null || sessionInfo.getHostSession() == null) {
            log.warn("Keys for unavailable session: {}", sessionId);
            return;
        }

        Message keysMessage = Message.builder()
                .type("keys")
                .session(sessionId)
                .payload(payload)
                .meta(meta)
                .build();

        sendMessage(sessionInfo.getHostSession(), keysMessage);
    }

    public void handleResize(String sessionId, int cols, int rows) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null || sessionInfo.getHostSession() == null) {
            log.warn("Resize for unavailable session: {}", sessionId);
            return;
        }

        // Track terminal size for shared viewers
        terminalSizes.put(sessionId, new int[]{cols, rows});

        Message resizeMessage = Message.builder()
                .type("resize")
                .session(sessionId)
                .meta(Map.of("cols", String.valueOf(cols), "rows", String.valueOf(rows)))
                .build();

        sendMessage(sessionInfo.getHostSession(), resizeMessage);
        log.debug("Forwarded resize to host: session={}, cols={}, rows={}", sessionId, cols, rows);

        // Notify shared viewers of terminal size change
        Message sizeMessage = Message.builder()
                .type("terminalSize")
                .session(sessionId)
                .meta(Map.of("cols", String.valueOf(cols), "rows", String.valueOf(rows)))
                .build();
        broadcastToSharedViewers(sessionId, sizeMessage);
    }

    public void sendSessionList(WebSocketSession wsSession, String ownerEmail) {
        // Register viewer for broadcasts (so they receive updates when hosts connect/disconnect)
        viewerSessionMap.put(wsSession.getId(), wsSession);
        if (ownerEmail != null) {
            viewerOwnerMap.put(wsSession.getId(), ownerEmail);
        }

        List<SessionListItem> sessionList = sessions.values().stream()
                // Only show sessions that belong to this user (exact match required)
                .filter(info -> ownerEmail != null && ownerEmail.equals(info.getOwnerEmail()))
                // Filter out project sessions (proj_* prefix in session name)
                .filter(info -> !isProjectSession(info.getId()))
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

    /**
     * Check if a session ID represents a project session
     * Project sessions have format: machineId/proj_projectId_agentId
     */
    private boolean isProjectSession(String sessionId) {
        if (sessionId == null) return false;
        // Session ID format: machineId/sessionName
        int slashIdx = sessionId.indexOf('/');
        if (slashIdx < 0) return sessionId.startsWith(PROJECT_SESSION_PREFIX);
        String sessionName = sessionId.substring(slashIdx + 1);
        return sessionName.startsWith(PROJECT_SESSION_PREFIX);
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

        // Clean up from shared viewers
        sharedViewers.values().forEach(viewers -> viewers.remove(wsSession));
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

    /**
     * Forward a message to all viewers (regular + shared) of a session.
     * Used for paneLayout and similar messages that need to reach all viewers.
     */
    public void forwardToViewers(String sessionId, Message message) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo == null) return;
        broadcastToViewers(sessionInfo, message);
        broadcastToSharedViewers(sessionId, message);
    }

    private void broadcastToViewers(SessionInfo sessionInfo, Message message) {
        sessionInfo.getViewers().forEach(viewer -> sendMessage(viewer, message));
    }

    private void broadcastToSharedViewers(String sessionId, Message message) {
        Set<WebSocketSession> viewers = sharedViewers.get(sessionId);
        if (viewers != null) {
            viewers.removeIf(v -> !v.isOpen());
            viewers.forEach(v -> sendMessage(v, message));
        }
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
                // Filter out project sessions
                .filter(info -> !isProjectSession(info.getId()))
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

        // Immediately remove session from list since user explicitly killed it
        sessions.remove(sessionId);
        offlineTimestamps.remove(sessionId);
        broadcastSessionListToOwner(ownerEmail);
        log.info("Removed killed session: {}", sessionId);
    }

    // ========== Project Management Methods ==========

    /**
     * Register a new project
     */
    public void registerProject(String projectId, String name, String mission,
                                 String machineId, String ownerEmail, WebSocketSession wsSession,
                                 List<ProjectInfo.SourceInfo> sources) {
        ProjectInfo existing = projects.get(projectId);
        if (existing != null) {
            // Update existing project
            existing.setHostSession(wsSession);
            existing.setStatus("running");
            if (sources != null) {
                existing.setSources(sources);
            }
            log.info("Project re-registered: projectId={}, owner={}", projectId, ownerEmail);
        } else {
            ProjectInfo projectInfo = ProjectInfo.create(projectId, name, mission, machineId, ownerEmail, wsSession);
            if (sources != null) {
                projectInfo.setSources(sources);
            }
            projects.put(projectId, projectInfo);
            log.info("Project registered: projectId={}, name={}, owner={}, sources={}",
                    projectId, name, ownerEmail, sources != null ? sources.size() : 0);
        }

        broadcastProjectListToOwner(ownerEmail);
    }

    /**
     * Update project sources
     */
    public void updateProjectSources(String projectId, List<ProjectInfo.SourceInfo> sources) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) {
            log.warn("updateProjectSources: project not found: {}", projectId);
            return;
        }

        projectInfo.setSources(sources);
        log.info("Project sources updated: projectId={}, sources={}", projectId, sources != null ? sources.size() : 0);

        broadcastProjectStatusToViewers(projectId);
        broadcastProjectListToOwner(projectInfo.getOwnerEmail());
    }

    /**
     * Update project status
     */
    public void updateProjectStatus(String projectId, String status, Map<String, ProjectInfo.AgentStatus> agents) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) {
            log.warn("updateProjectStatus: project not found: {}", projectId);
            return;
        }

        projectInfo.setStatus(status);
        if ("running".equals(status) && projectInfo.getStartedAt() == null) {
            projectInfo.setStartedAt(Instant.now());
        } else if ("completed".equals(status) || "failed".equals(status)) {
            projectInfo.setCompletedAt(Instant.now());
        }

        if (agents != null) {
            projectInfo.getAgents().putAll(agents);
        }

        log.info("Project status updated: projectId={}, status={}", projectId, status);
        broadcastProjectStatusToViewers(projectId);
        broadcastProjectListToOwner(projectInfo.getOwnerEmail());
    }

    /**
     * Update agent status within a project
     */
    public void updateAgentStatus(String projectId, String agentId, ProjectInfo.AgentStatus agentStatus) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) {
            log.warn("updateAgentStatus: project not found: {}", projectId);
            return;
        }

        projectInfo.getAgents().put(agentId, agentStatus);
        log.info("Agent status updated: projectId={}, agentId={}, status={}",
                projectId, agentId, agentStatus.getStatus());

        broadcastProjectStatusToViewers(projectId);
        broadcastProjectListToOwner(projectInfo.getOwnerEmail());
    }

    /**
     * Start workflow for a project
     */
    public void startWorkflow(String projectId, String mission, String ownerEmail) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) {
            log.warn("startWorkflow: project not found: {}", projectId);
            return;
        }

        // Verify ownership
        if (ownerEmail != null && !ownerEmail.equals(projectInfo.getOwnerEmail())) {
            log.warn("startWorkflow: unauthorized - owner mismatch for project: {}", projectId);
            return;
        }

        // Update mission if provided
        if (mission != null && !mission.isBlank()) {
            projectInfo.setMission(mission);
        }

        // Mark workflow as started
        projectInfo.setWorkflowStarted(true);
        projectInfo.setStartedAt(java.time.Instant.now());

        log.info("Workflow started: projectId={}, mission={}", projectId,
                mission != null ? mission.substring(0, Math.min(50, mission.length())) : null);

        broadcastProjectStatusToViewers(projectId);
        broadcastProjectListToOwner(projectInfo.getOwnerEmail());

        // TODO: Send startWorkflow command to connected agents
    }

    // Track pending analysis requests: requestId -> requesting viewer session
    private final Map<String, WebSocketSession> pendingAnalysisRequests = new ConcurrentHashMap<>();

    /**
     * Forward mission analysis request to project's agent
     */
    public void forwardAnalyzeMission(String projectId, String requestId, String mission,
                                       Map<String, String> meta, String ownerEmail, WebSocketSession viewerSession) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) {
            log.warn("forwardAnalyzeMission: project not found: {}", projectId);
            sendAnalysisErrorToViewer(viewerSession, requestId, "Project not found");
            return;
        }

        // Verify ownership
        if (ownerEmail != null && !ownerEmail.equals(projectInfo.getOwnerEmail())) {
            log.warn("forwardAnalyzeMission: unauthorized for project: {}", projectId);
            sendAnalysisErrorToViewer(viewerSession, requestId, "Unauthorized");
            return;
        }

        WebSocketSession hostSession = projectInfo.getHostSession();
        if (hostSession == null || !hostSession.isOpen()) {
            log.warn("forwardAnalyzeMission: agent not connected for project: {}", projectId);
            sendAnalysisErrorToViewer(viewerSession, requestId, "Agent not connected");
            return;
        }

        // Track the request so we can forward the response
        pendingAnalysisRequests.put(requestId, viewerSession);

        // Forward to agent
        try {
            Message forwardMsg = Message.builder()
                    .type("analyzeMission")
                    .requestId(requestId)
                    .meta(Map.of(
                            "projectId", projectId,
                            "mission", mission,
                            "contextPaths", meta != null && meta.get("contextPaths") != null ? meta.get("contextPaths") : ""
                    ))
                    .build();
            String json = objectMapper.writeValueAsString(forwardMsg);
            synchronized (hostSession) {
                hostSession.sendMessage(new TextMessage(json));
            }
            log.info("Forwarded analyzeMission to agent: projectId={}, requestId={}", projectId, requestId);
        } catch (IOException e) {
            log.error("Failed to forward analyzeMission", e);
            pendingAnalysisRequests.remove(requestId);
            sendAnalysisErrorToViewer(viewerSession, requestId, "Failed to contact agent");
        }
    }

    /**
     * Forward analysis response from agent to the requesting viewer
     */
    public void forwardAnalysisResponse(Message message) {
        String requestId = message.getRequestId();
        WebSocketSession viewerSession = pendingAnalysisRequests.remove(requestId);

        if (viewerSession == null) {
            log.warn("forwardAnalysisResponse: no pending request for requestId: {}", requestId);
            return;
        }

        if (!viewerSession.isOpen()) {
            log.warn("forwardAnalysisResponse: viewer session closed for requestId: {}", requestId);
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (viewerSession) {
                viewerSession.sendMessage(new TextMessage(json));
            }
            log.info("Forwarded analysis response to viewer: requestId={}", requestId);
        } catch (IOException e) {
            log.error("Failed to forward analysis response", e);
        }
    }

    private void sendAnalysisErrorToViewer(WebSocketSession session, String requestId, String error) {
        try {
            Message errorMsg = Message.builder()
                    .type("missionAnalysis")
                    .requestId(requestId)
                    .error(error)
                    .build();
            String json = objectMapper.writeValueAsString(errorMsg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.error("Failed to send analysis error", e);
        }
    }

    // ========== Add Source to Work Folder ==========

    private final Map<String, WebSocketSession> pendingAddSourceRequests = new ConcurrentHashMap<>();

    /**
     * Forward addSource request from web viewer to the project's agent
     */
    public void forwardAddSource(String projectId, String requestId, String sourceType,
                                  String sourceUrl, String targetFolder, String ownerEmail,
                                  WebSocketSession viewerSession) {
        ProjectInfo project = projects.get(projectId);
        if (project == null) {
            log.warn("forwardAddSource: project not found: {}", projectId);
            sendAddSourceErrorToViewer(viewerSession, requestId, "Project not found");
            return;
        }

        // Validate ownership
        if (ownerEmail != null && !ownerEmail.equals(project.getOwnerEmail())) {
            log.warn("forwardAddSource: unauthorized for project: {}", projectId);
            sendAddSourceErrorToViewer(viewerSession, requestId, "Unauthorized");
            return;
        }

        WebSocketSession agentSession = project.getHostSession();
        if (agentSession == null || !agentSession.isOpen()) {
            log.warn("forwardAddSource: agent not connected for project: {}", projectId);
            sendAddSourceErrorToViewer(viewerSession, requestId, "Agent not connected");
            return;
        }

        // Store pending request
        pendingAddSourceRequests.put(requestId, viewerSession);

        // Forward to agent
        try {
            Message forwardMsg = Message.builder()
                    .type("addSource")
                    .requestId(requestId)
                    .meta(Map.of(
                            "projectId", projectId,
                            "sourceType", sourceType,
                            "sourceUrl", sourceUrl,
                            "targetFolder", targetFolder != null ? targetFolder : ""
                    ))
                    .build();
            String json = objectMapper.writeValueAsString(forwardMsg);
            synchronized (agentSession) {
                agentSession.sendMessage(new TextMessage(json));
            }
            log.info("Forwarded addSource to agent: projectId={}, requestId={}", projectId, requestId);
        } catch (IOException e) {
            log.error("Failed to forward addSource to agent", e);
            pendingAddSourceRequests.remove(requestId);
            sendAddSourceErrorToViewer(viewerSession, requestId, "Failed to forward to agent");
        }
    }

    /**
     * Forward addSource result from agent to the requesting viewer
     */
    public void forwardAddSourceResult(Message message) {
        String requestId = message.getRequestId();
        WebSocketSession viewerSession = pendingAddSourceRequests.remove(requestId);

        if (viewerSession == null) {
            log.warn("forwardAddSourceResult: no pending request for requestId: {}", requestId);
            return;
        }

        if (!viewerSession.isOpen()) {
            log.warn("forwardAddSourceResult: viewer session closed for requestId: {}", requestId);
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (viewerSession) {
                viewerSession.sendMessage(new TextMessage(json));
            }
            log.info("Forwarded addSource result to viewer: requestId={}, error={}",
                    requestId, message.getError());
        } catch (IOException e) {
            log.error("Failed to forward addSource result", e);
        }
    }

    private void sendAddSourceErrorToViewer(WebSocketSession session, String requestId, String error) {
        try {
            Message errorMsg = Message.builder()
                    .type("addSourceResult")
                    .requestId(requestId)
                    .error(error)
                    .build();
            String json = objectMapper.writeValueAsString(errorMsg);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.error("Failed to send addSource error", e);
        }
    }

    /**
     * Register a viewer for projects
     */
    public void registerProjectViewer(String ownerEmail, WebSocketSession wsSession) {
        projectViewerSessionMap.put(wsSession.getId(), wsSession);
        if (ownerEmail != null) {
            projectViewerOwnerMap.put(wsSession.getId(), ownerEmail);
        }
        log.info("Project viewer registered: viewerId={}, owner={}", wsSession.getId(), ownerEmail);
    }

    /**
     * Send project list to viewer
     */
    public void sendProjectList(WebSocketSession wsSession, String ownerEmail) {
        List<ProjectListItem> projectList = projects.values().stream()
                .filter(info -> ownerEmail != null && ownerEmail.equals(info.getOwnerEmail()))
                .map(ProjectListItem::from)
                .toList();

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "projectList",
                    "projects", projectList
            ));
            synchronized (wsSession) {
                if (wsSession.isOpen()) {
                    wsSession.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.error("Failed to send project list", e);
        }
    }

    /**
     * Broadcast project list to owner
     */
    private void broadcastProjectListToOwner(String ownerEmail) {
        List<ProjectListItem> projectList = projects.values().stream()
                .filter(info -> ownerEmail != null && ownerEmail.equals(info.getOwnerEmail()))
                .map(ProjectListItem::from)
                .toList();

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "projectList",
                    "projects", projectList
            ));

            // Broadcast to regular viewers
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
                        log.error("Failed to broadcast project list", e);
                    }
                }
            });

            // Also broadcast to project viewers
            projectViewerSessionMap.forEach((viewerId, viewer) -> {
                String viewerOwner = projectViewerOwnerMap.get(viewerId);
                if (ownerEmail == null || ownerEmail.equals(viewerOwner)) {
                    try {
                        synchronized (viewer) {
                            if (viewer.isOpen()) {
                                viewer.sendMessage(new TextMessage(json));
                            }
                        }
                    } catch (IOException e) {
                        log.error("Failed to broadcast project list to project viewer", e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("Failed to serialize project list", e);
        }
    }

    /**
     * Broadcast project status to viewers watching this project
     */
    private void broadcastProjectStatusToViewers(String projectId) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) return;

        ProjectListItem item = ProjectListItem.from(projectInfo);

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "projectStatus",
                    "project", item
            ));

            projectInfo.getViewers().forEach(viewer -> {
                try {
                    synchronized (viewer) {
                        if (viewer.isOpen()) {
                            viewer.sendMessage(new TextMessage(json));
                        }
                    }
                } catch (IOException e) {
                    log.error("Failed to send project status to viewer", e);
                }
            });
        } catch (IOException e) {
            log.error("Failed to serialize project status", e);
        }
    }

    /**
     * Handle project host disconnect
     */
    public void handleProjectDisconnect(WebSocketSession wsSession) {
        projects.forEach((projectId, info) -> {
            if (info.getHostSession() != null &&
                    info.getHostSession().getId().equals(wsSession.getId())) {
                info.setHostSession(null);
                // Don't change status to offline immediately for projects
                // They might reconnect
                log.info("Project host disconnected: projectId={}", projectId);
                broadcastProjectListToOwner(info.getOwnerEmail());
            }

            info.getViewers().remove(wsSession);
        });

        projectViewerSessionMap.remove(wsSession.getId());
        projectViewerOwnerMap.remove(wsSession.getId());
    }

    /**
     * Get project by ID
     */
    public ProjectInfo getProject(String projectId) {
        return projects.get(projectId);
    }

    /**
     * Delete a project (only allowed for owner)
     */
    public void deleteProject(String projectId, String ownerEmail) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) {
            log.warn("deleteProject: project not found: {}", projectId);
            return;
        }

        // Verify ownership
        if (ownerEmail != null && !ownerEmail.equals(projectInfo.getOwnerEmail())) {
            log.warn("deleteProject: unauthorized - owner mismatch for project: {}", projectId);
            return;
        }

        // Remove the project
        projects.remove(projectId);
        log.info("Project deleted: projectId={}, owner={}", projectId, ownerEmail);

        // Broadcast updated project list
        broadcastProjectListToOwner(ownerEmail);
    }

    /**
     * Register a viewer for a specific project
     */
    public void registerProjectDetailViewer(String projectId, String ownerEmail, WebSocketSession wsSession) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo != null) {
            // Check ownership
            if (ownerEmail != null && !ownerEmail.equals(projectInfo.getOwnerEmail())) {
                log.warn("Viewer {} tried to access project {} owned by {}",
                        ownerEmail, projectId, projectInfo.getOwnerEmail());
                return;
            }
            projectInfo.getViewers().add(wsSession);
            log.info("Project detail viewer registered: projectId={}, viewerId={}",
                    projectId, wsSession.getId());
        }
    }

    /**
     * Get project stats
     */
    public ProjectStats getProjectStats() {
        long running = projects.values().stream()
                .filter(p -> "running".equals(p.getStatus()))
                .count();
        long completed = projects.values().stream()
                .filter(p -> "completed".equals(p.getStatus()))
                .count();
        return new ProjectStats(projects.size(), (int) running, (int) completed);
    }

    public record ProjectStats(int totalProjects, int runningProjects, int completedProjects) {}

    // ========== Workflow Message Methods ==========

    /**
     * Broadcast workflow message (output, event, decision) to project viewers
     * The message is forwarded as-is (already serialized JSON)
     */
    public void broadcastWorkflowMessage(String projectId, String jsonPayload) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) {
            log.warn("broadcastWorkflowMessage: project not found: {}", projectId);
            return;
        }

        projectInfo.getViewers().forEach(viewer -> {
            try {
                synchronized (viewer) {
                    if (viewer.isOpen()) {
                        viewer.sendMessage(new TextMessage(jsonPayload));
                    }
                }
            } catch (IOException e) {
                log.error("Failed to broadcast workflow message to viewer", e);
            }
        });
    }

    /**
     * Update agent status from workflow event
     */
    public void updateAgentStatusFromEvent(String projectId, String agentId, String event, String message) {
        ProjectInfo projectInfo = projects.get(projectId);
        if (projectInfo == null) {
            log.warn("updateAgentStatusFromEvent: project not found: {}", projectId);
            return;
        }

        // Map event to status
        String status = switch (event) {
            case "started", "running" -> "running";
            case "completed" -> "completed";
            case "failed" -> "failed";
            case "waiting" -> "pending";
            default -> "pending";
        };

        ProjectInfo.AgentStatus agentStatus = projectInfo.getAgents().get(agentId);
        if (agentStatus != null) {
            agentStatus.setStatus(status);
            if (message != null) {
                agentStatus.setCurrentTask(message);
            }
        } else {
            // Create new agent status
            projectInfo.getAgents().put(agentId, ProjectInfo.AgentStatus.builder()
                    .agentId(agentId)
                    .name(agentId)
                    .status(status)
                    .currentTask(message)
                    .build());
        }

        log.info("Agent status updated from event: projectId={}, agentId={}, event={}, status={}",
                projectId, agentId, event, status);

        // Broadcast updates
        broadcastProjectStatusToViewers(projectId);
        broadcastProjectListToOwner(projectInfo.getOwnerEmail());
    }
}
