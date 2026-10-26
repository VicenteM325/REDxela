package com.redxela.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registra el endpoint {@code /ws} por el que la interfaz recibe el estado en tiempo real. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveBroadcaster broadcaster;

    public WebSocketConfig(LiveBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Solo orígenes locales: la interfaz se sirve desde este mismo servidor
        registry.addHandler(broadcaster, "/ws").setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }
}
