package com.tmuxremote.relay.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionInfo {
    private String id;
    private String label;
    private String machineId;
    private String status;
    private String ownerEmail;  // Owner of this session (from agent token)
    private WebSocketSession hostSession;
    private Set<WebSocketSession> viewers;

    public static SessionInfo create(String id, String label, String machineId, String ownerEmail, WebSocketSession hostSession) {
        return SessionInfo.builder()
                .id(id)
                .label(label)
                .machineId(machineId)
                .status("online")
                .ownerEmail(ownerEmail)
                .hostSession(hostSession)
                .viewers(ConcurrentHashMap.newKeySet())
                .build();
    }
}
