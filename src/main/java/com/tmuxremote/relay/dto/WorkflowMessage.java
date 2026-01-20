package com.tmuxremote.relay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Workflow output message for Diff-based streaming.
 *
 * Message types:
 * - workflowOutput: Agent console output (diff-based)
 * - workflowEvent: Agent lifecycle events (started, completed, failed)
 * - workflowDecision: Decision point requiring human intervention
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowMessage {

    private String type;           // workflowOutput, workflowEvent, workflowDecision
    private String projectId;      // Project identifier
    private String agentId;        // Agent identifier
    private Long timestamp;        // Unix timestamp in millis

    // For workflowOutput (Diff-based)
    private List<LineDiff> diffs;  // Changed lines
    private Integer totalLines;    // Total line count (for sync)

    // For workflowEvent
    private String event;          // started, running, completed, failed, waiting
    private String message;        // Human readable message

    // For workflowDecision
    private String decisionType;   // approval, input, choice
    private String prompt;         // Question or prompt for user
    private List<String> options;  // Available options for choice type

    /**
     * Single line diff entry
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineDiff {
        private String action;     // add, remove, modify
        private Integer lineNum;   // Line number (0-indexed)
        private String content;    // Line content
        private String oldContent; // Previous content (for modify)
    }
}
