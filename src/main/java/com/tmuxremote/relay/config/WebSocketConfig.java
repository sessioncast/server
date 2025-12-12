package com.tmuxremote.relay.config;

import com.tmuxremote.relay.handler.RelayWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final RelayWebSocketHandler relayWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(relayWebSocketHandler, "/ws")
                .setAllowedOrigins("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // Reduced buffer sizes for lower memory usage (screen data is typically ~50KB compressed)
        container.setMaxTextMessageBufferSize(256 * 1024);   // 256KB (was 1MB)
        container.setMaxBinaryMessageBufferSize(256 * 1024); // 256KB (was 1MB)
        // Set idle timeout - close inactive connections after 5 minutes
        container.setMaxSessionIdleTimeout(300000L);         // 5 minutes
        // Set async send timeout
        container.setAsyncSendTimeout(30000L);               // 30 seconds
        return container;
    }
}
