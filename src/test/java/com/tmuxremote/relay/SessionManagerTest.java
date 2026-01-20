package com.tmuxremote.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tmuxremote.relay.dto.SessionInfo;
import com.tmuxremote.relay.service.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionManagerTest {

    private SessionManager sessionManager;
    private ObjectMapper objectMapper;
    private static final String TEST_OWNER = "test@example.com";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sessionManager = new SessionManager(objectMapper);
    }

    @Test
    @DisplayName("Host 등록 시 세션이 생성되어야 함")
    void testRegisterHost() throws IOException {
        // Given
        WebSocketSession mockSession = mock(WebSocketSession.class);
        when(mockSession.getId()).thenReturn("host-session-1");
        when(mockSession.isOpen()).thenReturn(true);

        // When
        sessionManager.registerHost("test/dev", "Test Dev", "test-machine", TEST_OWNER, mockSession);

        // Then - verify by requesting session list
        WebSocketSession viewerSession = mock(WebSocketSession.class);
        when(viewerSession.getId()).thenReturn("viewer-1");
        when(viewerSession.isOpen()).thenReturn(true);

        AtomicReference<String> capturedMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            capturedMessage.set(msg.getPayload());
            return null;
        }).when(viewerSession).sendMessage(any(TextMessage.class));

        sessionManager.sendSessionList(viewerSession, TEST_OWNER);

        assertNotNull(capturedMessage.get());
        assertTrue(capturedMessage.get().contains("test/dev"));
        assertTrue(capturedMessage.get().contains("online"));
    }

    @Test
    @DisplayName("Viewer 등록 시 세션에 추가되어야 함")
    void testRegisterViewer() {
        // Given
        WebSocketSession hostSession = mock(WebSocketSession.class);
        when(hostSession.getId()).thenReturn("host-1");
        when(hostSession.isOpen()).thenReturn(true);

        WebSocketSession viewerSession = mock(WebSocketSession.class);
        when(viewerSession.getId()).thenReturn("viewer-1");
        when(viewerSession.isOpen()).thenReturn(true);

        // When
        sessionManager.registerHost("test/dev", "Test Dev", "test-machine", TEST_OWNER, hostSession);
        sessionManager.registerViewer("test/dev", TEST_OWNER, viewerSession);

        // Then - no exception means success
        assertDoesNotThrow(() -> sessionManager.handleScreen("test/dev", "dGVzdA==", "screen"));
    }

    @Test
    @DisplayName("Screen 데이터가 Viewer에게 전달되어야 함")
    void testScreenBroadcast() throws IOException {
        // Given
        WebSocketSession hostSession = mock(WebSocketSession.class);
        when(hostSession.getId()).thenReturn("host-1");
        when(hostSession.isOpen()).thenReturn(true);

        WebSocketSession viewerSession = mock(WebSocketSession.class);
        when(viewerSession.getId()).thenReturn("viewer-1");
        when(viewerSession.isOpen()).thenReturn(true);

        AtomicReference<String> capturedMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            capturedMessage.set(msg.getPayload());
            return null;
        }).when(viewerSession).sendMessage(any(TextMessage.class));

        sessionManager.registerHost("test/dev", "Test Dev", "test-machine", TEST_OWNER, hostSession);
        sessionManager.registerViewer("test/dev", TEST_OWNER, viewerSession);

        // When
        sessionManager.handleScreen("test/dev", "SGVsbG8gV29ybGQ=", "screen"); // "Hello World" in base64

        // Then
        assertNotNull(capturedMessage.get());
        assertTrue(capturedMessage.get().contains("screen"));
        assertTrue(capturedMessage.get().contains("SGVsbG8gV29ybGQ="));
    }

    @Test
    @DisplayName("Keys가 Host에게 전달되어야 함")
    void testKeysForward() throws IOException {
        // Given
        WebSocketSession hostSession = mock(WebSocketSession.class);
        when(hostSession.getId()).thenReturn("host-1");
        when(hostSession.isOpen()).thenReturn(true);

        AtomicReference<String> capturedMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            capturedMessage.set(msg.getPayload());
            return null;
        }).when(hostSession).sendMessage(any(TextMessage.class));

        WebSocketSession viewerSession = mock(WebSocketSession.class);
        when(viewerSession.getId()).thenReturn("viewer-1");

        sessionManager.registerHost("test/dev", "Test Dev", "test-machine", TEST_OWNER, hostSession);

        // When
        sessionManager.handleKeys("test/dev", "ls -la\n", viewerSession);

        // Then
        assertNotNull(capturedMessage.get());
        assertTrue(capturedMessage.get().contains("keys"));
        assertTrue(capturedMessage.get().contains("ls -la"));
    }

    @Test
    @DisplayName("Host 연결 해제 시 세션 상태가 offline으로 변경되어야 함")
    void testHostDisconnect() throws IOException {
        // Given
        WebSocketSession hostSession = mock(WebSocketSession.class);
        when(hostSession.getId()).thenReturn("host-1");
        when(hostSession.isOpen()).thenReturn(true);

        sessionManager.registerHost("test/dev", "Test Dev", "test-machine", TEST_OWNER, hostSession);

        // When
        sessionManager.handleDisconnect(hostSession);

        // Then - check session list shows offline
        WebSocketSession viewerSession = mock(WebSocketSession.class);
        when(viewerSession.getId()).thenReturn("viewer-1");
        when(viewerSession.isOpen()).thenReturn(true);

        AtomicReference<String> capturedMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            capturedMessage.set(msg.getPayload());
            return null;
        }).when(viewerSession).sendMessage(any(TextMessage.class));

        sessionManager.sendSessionList(viewerSession, TEST_OWNER);

        assertNotNull(capturedMessage.get());
        assertTrue(capturedMessage.get().contains("offline"));
    }

    @Test
    @DisplayName("존재하지 않는 세션에 screen 전송 시 예외가 발생하지 않아야 함")
    void testScreenToNonExistentSession() {
        assertDoesNotThrow(() -> sessionManager.handleScreen("non-existent", "data", "screen"));
    }

    @Test
    @DisplayName("세션 리스트 요청 시 올바른 형식으로 응답해야 함")
    void testSessionListFormat() throws IOException {
        // Given
        WebSocketSession hostSession = mock(WebSocketSession.class);
        when(hostSession.getId()).thenReturn("host-1");
        when(hostSession.isOpen()).thenReturn(true);

        sessionManager.registerHost("machine1/session1", "Session 1", "machine1", TEST_OWNER, hostSession);

        WebSocketSession viewerSession = mock(WebSocketSession.class);
        when(viewerSession.getId()).thenReturn("viewer-1");
        when(viewerSession.isOpen()).thenReturn(true);

        AtomicReference<String> capturedMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            capturedMessage.set(msg.getPayload());
            return null;
        }).when(viewerSession).sendMessage(any(TextMessage.class));

        // When
        sessionManager.sendSessionList(viewerSession, TEST_OWNER);

        // Then
        String response = capturedMessage.get();
        assertNotNull(response);
        assertTrue(response.contains("\"type\":\"sessionList\""));
        assertTrue(response.contains("\"sessions\""));
        assertTrue(response.contains("\"id\":\"machine1/session1\""));
        assertTrue(response.contains("\"label\":\"Session 1\""));
        assertTrue(response.contains("\"machineId\":\"machine1\""));
        assertTrue(response.contains("\"status\":\"online\""));
    }

    @Test
    @DisplayName("다른 소유자의 세션은 보이지 않아야 함")
    void testOwnerFiltering() throws IOException {
        // Given
        String owner1 = "user1@example.com";
        String owner2 = "user2@example.com";

        WebSocketSession host1 = mock(WebSocketSession.class);
        when(host1.getId()).thenReturn("host-1");
        when(host1.isOpen()).thenReturn(true);

        WebSocketSession host2 = mock(WebSocketSession.class);
        when(host2.getId()).thenReturn("host-2");
        when(host2.isOpen()).thenReturn(true);

        sessionManager.registerHost("machine1/session1", "Session 1", "machine1", owner1, host1);
        sessionManager.registerHost("machine2/session2", "Session 2", "machine2", owner2, host2);

        // When - owner1 requests session list
        WebSocketSession viewerSession = mock(WebSocketSession.class);
        when(viewerSession.getId()).thenReturn("viewer-1");
        when(viewerSession.isOpen()).thenReturn(true);

        AtomicReference<String> capturedMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            TextMessage msg = invocation.getArgument(0);
            capturedMessage.set(msg.getPayload());
            return null;
        }).when(viewerSession).sendMessage(any(TextMessage.class));

        sessionManager.sendSessionList(viewerSession, owner1);

        // Then - should only see owner1's session
        String response = capturedMessage.get();
        assertNotNull(response);
        assertTrue(response.contains("machine1/session1"));
        assertFalse(response.contains("machine2/session2"));
    }
}
