package com.tmuxremote.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmuxremote.relay.dto.WorkflowMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages workflow diff streaming connections and state.
 * Separate from SessionManager (screen capture) for clean separation of concerns.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowManager {

    private final ObjectMapper objectMapper;
    private final SessionManager sessionManager;

    // projectId -> WorkflowState
    private final Map<String, WorkflowState> workflows = new ConcurrentHashMap<>();

    /**
     * Workflow state for a project
     */
    public static class WorkflowState {
        String projectId;
        String ownerEmail;

        // Agent connections: agentId -> session
        Map<String, WebSocketSession> agentSessions = new ConcurrentHashMap<>();

        // Viewer connections
        Set<WebSocketSession> viewers = ConcurrentHashMap.newKeySet();

        // Agent statuses: agentId -> status
        Map<String, AgentState> agentStates = new ConcurrentHashMap<>();

        // Accumulated output lines per agent (for late-joining viewers)
        Map<String, List<String>> outputBuffer = new ConcurrentHashMap<>();

        // Pending decisions awaiting user response
        List<WorkflowMessage> pendingDecisions = Collections.synchronizedList(new ArrayList<>());

        // Max lines to buffer per agent
        static final int MAX_BUFFER_LINES = 500;
    }

    public static class AgentState {
        String agentId;
        String name;
        String status;  // pending, running, completed, failed
        String currentTask;
        long lastUpdate;
    }

    /**
     * Register an agent connection
     */
    public void registerAgent(String projectId, String agentId, String ownerEmail, WebSocketSession session) {
        WorkflowState state = workflows.computeIfAbsent(projectId, id -> {
            WorkflowState ws = new WorkflowState();
            ws.projectId = id;
            ws.ownerEmail = ownerEmail;

            // Also register in SessionManager for project list visibility
            sessionManager.registerProject(id, id, "Workflow Project", "unknown", ownerEmail, null, null);
            return ws;
        });

        if (agentId != null) {
            state.agentSessions.put(agentId, session);

            // Initialize agent state if not exists
            state.agentStates.computeIfAbsent(agentId, id -> {
                AgentState as = new AgentState();
                as.agentId = id;
                as.name = id;
                as.status = "pending";
                as.lastUpdate = System.currentTimeMillis();
                return as;
            });

            // Update project status to running when agent connects
            sessionManager.updateProjectStatus(projectId, "running", null);
        }

        log.info("Agent registered: project={}, agent={}", projectId, agentId);
    }

    /**
     * Register a viewer connection
     */
    public void registerViewer(String projectId, String ownerEmail, WebSocketSession session) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) {
            // Create state for viewer (agent may connect later)
            state = new WorkflowState();
            state.projectId = projectId;
            state.ownerEmail = ownerEmail;
            workflows.put(projectId, state);
        }

        // Verify ownership
        if (ownerEmail != null && state.ownerEmail != null
                && !ownerEmail.equals(state.ownerEmail)) {
            log.warn("Viewer {} not authorized for project {} owned by {}",
                    ownerEmail, projectId, state.ownerEmail);
            return;
        }

        state.viewers.add(session);
        log.info("Viewer registered: project={}, viewerId={}", projectId, session.getId());
    }

    /**
     * Broadcast message to all viewers of a project
     */
    public void broadcastToViewers(String projectId, String jsonPayload) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) {
            log.warn("broadcastToViewers: project not found: {}", projectId);
            return;
        }

        state.viewers.removeIf(viewer -> !viewer.isOpen());

        state.viewers.forEach(viewer -> {
            try {
                synchronized (viewer) {
                    if (viewer.isOpen()) {
                        viewer.sendMessage(new TextMessage(jsonPayload));
                    }
                }
            } catch (IOException e) {
                log.error("Failed to send to viewer", e);
            }
        });
    }

    /**
     * Forward message to agent(s)
     */
    public void forwardToAgent(String projectId, String jsonPayload) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) {
            log.warn("forwardToAgent: project not found: {}", projectId);
            return;
        }

        // Forward to all connected agents
        state.agentSessions.values().forEach(agentSession -> {
            try {
                synchronized (agentSession) {
                    if (agentSession.isOpen()) {
                        agentSession.sendMessage(new TextMessage(jsonPayload));
                    }
                }
            } catch (IOException e) {
                log.error("Failed to forward to agent", e);
            }
        });
    }

    /**
     * Update agent status
     */
    public void updateAgentStatus(String projectId, String agentId, String event, String message) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) return;

        AgentState agentState = state.agentStates.computeIfAbsent(agentId, id -> {
            AgentState as = new AgentState();
            as.agentId = id;
            as.name = id;
            return as;
        });

        agentState.status = mapEventToStatus(event);
        agentState.currentTask = message;
        agentState.lastUpdate = System.currentTimeMillis();

        log.info("Agent status updated: project={}, agent={}, status={}",
                projectId, agentId, agentState.status);
    }

    private String mapEventToStatus(String event) {
        return switch (event) {
            case "started", "running" -> "running";
            case "completed" -> "completed";
            case "failed" -> "failed";
            case "waiting" -> "pending";
            default -> "pending";
        };
    }

    /**
     * Add a pending decision
     */
    public void addPendingDecision(String projectId, WorkflowMessage decision) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) return;

        state.pendingDecisions.add(decision);
        log.info("Pending decision added: project={}, type={}",
                projectId, decision.getDecisionType());
    }

    /**
     * Send current state snapshot to a new viewer
     */
    public void sendStateSnapshot(String projectId, WebSocketSession viewer) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) return;

        try {
            // Send agent states
            for (AgentState agentState : state.agentStates.values()) {
                WorkflowMessage stateMsg = WorkflowMessage.builder()
                        .type("workflowEvent")
                        .projectId(projectId)
                        .agentId(agentState.agentId)
                        .event(agentState.status)
                        .message(agentState.currentTask)
                        .timestamp(agentState.lastUpdate)
                        .build();

                String json = objectMapper.writeValueAsString(stateMsg);
                synchronized (viewer) {
                    if (viewer.isOpen()) {
                        viewer.sendMessage(new TextMessage(json));
                    }
                }
            }

            // Send buffered output (recent lines)
            log.info("Sending buffered output: {} agents in buffer", state.outputBuffer.size());
            for (Map.Entry<String, List<String>> entry : state.outputBuffer.entrySet()) {
                String agentId = entry.getKey();
                List<String> lines = entry.getValue();
                log.info("Agent {} has {} buffered lines", agentId, lines.size());

                if (!lines.isEmpty()) {
                    // Build diff from buffered lines
                    List<WorkflowMessage.LineDiff> diffs = new ArrayList<>();
                    for (int i = 0; i < lines.size(); i++) {
                        diffs.add(WorkflowMessage.LineDiff.builder()
                                .action("add")
                                .lineNum(i)
                                .content(lines.get(i))
                                .build());
                    }

                    WorkflowMessage outputMsg = WorkflowMessage.builder()
                            .type("workflowOutput")
                            .projectId(projectId)
                            .agentId(agentId)
                            .diffs(diffs)
                            .totalLines(lines.size())
                            .timestamp(System.currentTimeMillis())
                            .build();

                    String json = objectMapper.writeValueAsString(outputMsg);
                    synchronized (viewer) {
                        if (viewer.isOpen()) {
                            viewer.sendMessage(new TextMessage(json));
                        }
                    }
                }
            }

            // Send pending decisions
            for (WorkflowMessage decision : state.pendingDecisions) {
                String json = objectMapper.writeValueAsString(decision);
                synchronized (viewer) {
                    if (viewer.isOpen()) {
                        viewer.sendMessage(new TextMessage(json));
                    }
                }
            }

            log.info("Sent state snapshot to viewer: project={}, agents={}, bufferedAgents={}",
                    projectId, state.agentStates.size(), state.outputBuffer.size());

        } catch (Exception e) {
            log.error("Failed to send state snapshot", e);
        }
    }

    /**
     * Buffer output line for late-joining viewers
     */
    public void bufferOutput(String projectId, String agentId, String line) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) {
            log.warn("bufferOutput: project not found: {}", projectId);
            return;
        }

        List<String> buffer = state.outputBuffer.computeIfAbsent(agentId,
                id -> Collections.synchronizedList(new ArrayList<>()));

        buffer.add(line);
        log.info("Buffered line for {}/{}: total={}, content='{}'",
                projectId, agentId, buffer.size(),
                line.length() > 50 ? line.substring(0, 50) + "..." : line);

        // Trim if over limit
        while (buffer.size() > WorkflowState.MAX_BUFFER_LINES) {
            buffer.remove(0);
        }
    }

    /**
     * Reset agent output buffer (on workflowReset)
     */
    public void resetAgentBuffer(String projectId, String agentId) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) return;

        if (agentId != null) {
            List<String> removed = state.outputBuffer.remove(agentId);
            log.info("Agent buffer RESET: project={}, agent={}, removedLines={}",
                    projectId, agentId, removed != null ? removed.size() : 0);
        } else {
            int agentCount = state.outputBuffer.size();
            state.outputBuffer.clear();
            log.info("All agent buffers CLEARED: project={}, agentCount={}", projectId, agentCount);
        }
    }

    /**
     * Sync agent buffer with full state (on workflowSync)
     */
    public void syncAgentBuffer(String projectId, String agentId, WorkflowMessage syncMessage) {
        WorkflowState state = workflows.get(projectId);
        if (state == null) return;

        if (agentId == null) return;

        // Get existing buffer
        List<String> existingBuffer = state.outputBuffer.get(agentId);
        int existingSize = existingBuffer != null ? existingBuffer.size() : 0;

        // Only replace buffer if sync contains actual content
        // If diffs is null/empty, this is just a keep-alive sync - preserve existing buffer
        if (syncMessage.getDiffs() != null && !syncMessage.getDiffs().isEmpty()) {
            List<String> lines = Collections.synchronizedList(new ArrayList<>());
            for (WorkflowMessage.LineDiff diff : syncMessage.getDiffs()) {
                if (diff.getContent() != null) {
                    lines.add(diff.getContent());
                }
            }
            state.outputBuffer.put(agentId, lines);

            // Trim if over limit
            while (lines.size() > WorkflowState.MAX_BUFFER_LINES) {
                lines.remove(0);
            }

            log.info("Agent buffer SYNCED with content: project={}, agent={}, existingLines={}, newLines={}",
                    projectId, agentId, existingSize, lines.size());
        } else {
            // Keep-alive sync without content - ensure agent is registered in buffer
            state.outputBuffer.computeIfAbsent(agentId,
                    id -> Collections.synchronizedList(new ArrayList<>()));
            log.debug("Agent buffer sync keep-alive: project={}, agent={}, existingLines={}",
                    projectId, agentId, existingSize);
        }
    }

    /**
     * Handle disconnect
     */
    public void handleDisconnect(WebSocketSession session) {
        workflows.forEach((projectId, state) -> {
            // Remove from agent sessions
            state.agentSessions.entrySet().removeIf(entry ->
                    entry.getValue().getId().equals(session.getId()));

            // Remove from viewers
            state.viewers.remove(session);
        });

        // Clean up empty workflows
        workflows.entrySet().removeIf(entry -> {
            WorkflowState state = entry.getValue();
            return state.agentSessions.isEmpty() && state.viewers.isEmpty();
        });
    }

    /**
     * Get stats
     */
    public WorkflowStats getStats() {
        int totalProjects = workflows.size();
        int totalAgents = workflows.values().stream()
                .mapToInt(s -> s.agentSessions.size())
                .sum();
        int totalViewers = workflows.values().stream()
                .mapToInt(s -> s.viewers.size())
                .sum();
        return new WorkflowStats(totalProjects, totalAgents, totalViewers);
    }

    public record WorkflowStats(int totalProjects, int totalAgents, int totalViewers) {}
}
