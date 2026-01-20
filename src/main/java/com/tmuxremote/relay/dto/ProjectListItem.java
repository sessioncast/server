package com.tmuxremote.relay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectListItem {
    private String projectId;
    private String name;
    private String mission;
    private String status;
    private String machineId;
    private String createdAt;
    private String startedAt;
    private List<AgentStatusItem> agents;
    private List<SourceItem> sources;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceItem {
        private String folder;
        private int fileCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentStatusItem {
        private String agentId;
        private String name;
        private String status;
        private String currentTask;
    }

    public static ProjectListItem from(ProjectInfo info) {
        List<AgentStatusItem> agentList = info.getAgents() != null
            ? info.getAgents().values().stream()
                .map(a -> AgentStatusItem.builder()
                        .agentId(a.getAgentId())
                        .name(a.getName())
                        .status(a.getStatus())
                        .currentTask(a.getCurrentTask())
                        .build())
                .collect(Collectors.toList())
            : List.of();

        List<SourceItem> sourceList = info.getSources() != null
            ? info.getSources().stream()
                .map(s -> SourceItem.builder()
                        .folder(s.getFolder())
                        .fileCount(s.getFileCount())
                        .build())
                .collect(Collectors.toList())
            : List.of();

        return ProjectListItem.builder()
                .projectId(info.getProjectId())
                .name(info.getName())
                .mission(info.getMission())
                .status(info.getStatus())
                .machineId(info.getMachineId())
                .createdAt(info.getCreatedAt() != null ? info.getCreatedAt().toString() : null)
                .startedAt(info.getStartedAt() != null ? info.getStartedAt().toString() : null)
                .agents(agentList)
                .sources(sourceList)
                .build();
    }
}
