package com.callverse.infrastructure.realtime;

import com.callverse.infrastructure.security.StompAuthorizationInterceptor;
import com.callverse.infrastructure.security.StompSessionRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * The live channel: STOMP over a native WebSocket at {@code /ws}, with an in-memory broker for the
 * five frozen {@code /topic/**} destinations (brief 01).
 *
 * <ul>
 *   <li><strong>Native WebSocket, no SockJS</strong>: the Next.js client ({@code @stomp/stompjs})
 *       speaks it directly, and a fallback transport would be one more surface to secure.
 *   <li><strong>Browser origins</strong> are the same exact list as REST CORS. Non-browser clients
 *       send no Origin and are judged by their token alone.
 *   <li><strong>In-memory broker, single instance.</strong> Enough for this platform; several
 *       instances would need a broker relay so that every instance sees every event.
 *   <li><strong>Heartbeats</strong> every ten seconds both ways, so a dead connection is noticed,
 *       on Spring's own broker scheduler rather than a second scheduler bean.
 *   <li><strong>Small frames</strong>: clients only send CONNECT and SUBSCRIBE, so 16 KB is plenty
 *       and a flood is cut short.
 *   <li><strong>No application destinations</strong> ({@code /app}) and no user destinations
 *       ({@code /user}): clients listen, the server speaks. {@link StompAuthorizationInterceptor}
 *       enforces it frame by frame on the way in; {@link StompSessionRegistry} stops delivery to a
 *       session whose token has expired, on the way out.
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {

    private final StompAuthorizationInterceptor authorization;
    private final StompSessionRegistry sessions;
    private final List<String> allowedOrigins;
    private final TaskScheduler brokerScheduler;

    public WebSocketConfiguration(
            StompAuthorizationInterceptor authorization,
            StompSessionRegistry sessions,
            @Value("${callverse.cors.allowed-origins}") List<String> allowedOrigins,
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler brokerScheduler) {
        this.authorization = authorization;
        this.sessions = sessions;
        this.allowedOrigins = allowedOrigins;
        this.brokerScheduler = brokerScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(brokerScheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authorization);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(sessions);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024).setSendBufferSizeLimit(512 * 1024).setSendTimeLimit(10_000);
    }
}
