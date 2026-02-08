package com.tmuxremote.relay.dto;

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
public class SessionListItem {
    private String id;
    private String label;
    private String machineId;
    private String status;
    private List<Map<String, Object>> panes;

    public static SessionListItem from(SessionInfo info) {
        return SessionListItem.builder()
                .id(info.getId())
                .label(info.getLabel())
                .machineId(info.getMachineId())
                .status(info.getStatus())
                .panes(info.getPanes())
                .build();
    }
}
