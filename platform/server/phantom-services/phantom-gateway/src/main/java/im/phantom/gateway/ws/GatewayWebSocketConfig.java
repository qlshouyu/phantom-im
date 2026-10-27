package im.phantom.gateway.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 注册 WS 端点 /ws（binary 帧承载 Envelope）。M0 允许所有来源便于本地联调。 */
@Configuration
@EnableWebSocket
public class GatewayWebSocketConfig implements WebSocketConfigurer {

    private final PhantomWsHandler handler;

    public GatewayWebSocketConfig(PhantomWsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns("*");
    }
}
