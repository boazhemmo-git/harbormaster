package io.harbormaster.config;

import io.harbormaster.api.LiveWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * HTTP/WebSocket wiring. CORS and origins are wide open by design: this is a
 * LAN demo application with no credentials, secrets, or mutating endpoints.
 * A production deployment would pin origins and put the API behind auth.
 */
@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer, WebMvcConfigurer {

    private final LiveWebSocketHandler liveHandler;

    public WebConfig(LiveWebSocketHandler liveHandler) {
        this.liveHandler = liveHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveHandler, "/ws/live").setAllowedOriginPatterns("*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("*").allowedMethods("GET");
    }
}
