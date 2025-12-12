package com.tmuxremote.relay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCleanupScheduler {

    private final SessionManager sessionManager;

    // Run every 5 minutes
    @Scheduled(fixedRate = 300000)
    public void cleanupStaleSessions() {
        int cleaned = sessionManager.cleanupStaleSessions();
        if (cleaned > 0) {
            log.info("Cleaned up {} stale sessions", cleaned);
        }
    }

    // Log stats every minute
    @Scheduled(fixedRate = 60000)
    public void logStats() {
        SessionManager.Stats stats = sessionManager.getStats();
        log.info("Sessions: total={}, online={}, viewers={}",
                stats.totalSessions(), stats.onlineSessions(), stats.totalViewers());
    }
}
