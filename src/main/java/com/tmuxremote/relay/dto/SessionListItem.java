package com.tmuxremote.relay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionListItem {
    private String id;
    private String label;
    private String machineId;
    private String status;

    public static SessionListItem from(SessionInfo info) {
        return SessionListItem.builder()
                .id(info.getId())
                .label(info.getLabel())
                .machineId(info.getMachineId())
                .status(info.getStatus())
                .build();
    }
}
