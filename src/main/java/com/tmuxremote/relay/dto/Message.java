package com.tmuxremote.relay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {
    private String type;
    private String role;
    private String session;
    private String payload;
    private Map<String, String> meta;

    // For async request/response (analyzeMission)
    private String requestId;
    private String error;
    private List<MissionStep> steps;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissionStep {
        private String id;
        private String title;
        private String description;
        private String agent;
        private String estimatedTime;
        private List<String> dependsOn;
    }
}
