package com.tmuxremote.relay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectInfo {
    private String projectId;
    private String name;
    private String mission;
    private String status;  // pending, running, completed, failed (agent connection status)
    private boolean workflowStarted;  // true if user has started the workflow with a mission
    private String ownerEmail;
    private String machineId;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;

    // Agent statuses
    private Map<String, AgentStatus> agents;

    // Sources in work/ folder
    private List<SourceInfo> sources;

    // WebSocket session from the host agent
    private WebSocketSession hostSession;

    // Viewers watching this project
    private Set<WebSocketSession> viewers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentStatus {
        private String agentId;
        private String name;
        private String status;  // pending, running, completed, failed
        private String currentTask;
        private String error;
        private Instant startedAt;
        private Instant completedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceInfo {
        private String folder;
        private int fileCount;
    }

    public static ProjectInfo create(String projectId, String name, String mission,
                                      String machineId, String ownerEmail, WebSocketSession hostSession) {
        return ProjectInfo.builder()
                .projectId(projectId)
                .name(name)
                .mission(mission)
                .status("pending")
                .workflowStarted(false)
                .machineId(machineId)
                .ownerEmail(ownerEmail)
                .createdAt(Instant.now())
                .hostSession(hostSession)
                .agents(new ConcurrentHashMap<>())
                .viewers(ConcurrentHashMap.newKeySet())
                .build();
    }
}
