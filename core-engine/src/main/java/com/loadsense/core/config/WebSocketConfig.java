package com.loadsense.core.config;

import com.loadsense.core.repository.TestExecutionRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.config.annotation.*;
import java.util.*;

@Configuration @EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final JwtDecoder jwt;
    private final TestExecutionRepository executions;
    private final String[] origins;
    public WebSocketConfig(JwtDecoder jwt,TestExecutionRepository executions,@Value("${loadsense.allowed-origins}") String[] origins) {
        this.jwt=jwt; this.executions=executions; this.origins=origins;
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(origins);
    }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue"); registry.setUserDestinationPrefix("/user");
    }
    @Override public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(16384).setSendBufferSizeLimit(262144).setSendTimeLimit(10000);
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
                var accessor=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
                if(accessor==null || accessor.getCommand()==null) return message;
                var command=accessor.getCommand();
                if(command==StompCommand.CONNECT) {
                    String header=accessor.getFirstNativeHeader("Authorization");
                    if(header==null || !header.startsWith("Bearer ")) throw new MessagingException("Authentication required");
                    accessor.setUser(new JwtAuthenticationToken(jwt.decode(header.substring(7))));
                } else if(command==StompCommand.SUBSCRIBE) {
                    if(!(accessor.getUser() instanceof JwtAuthenticationToken token)) throw new MessagingException("Authentication required");
                    jwt.decode(token.getToken().getTokenValue());
                    String destination=accessor.getDestination(), prefix="/user/queue/executions/";
                    if(destination==null || !destination.startsWith(prefix)) throw new MessagingException("Subscription denied");
                    UUID id=UUID.fromString(destination.substring(prefix.length()));
                    if(executions.findByIdAndUserId(id,UUID.fromString(token.getName())).isEmpty()) throw new MessagingException("Subscription denied");
                } else if(command==StompCommand.SEND) throw new MessagingException("Client SEND is disabled");
                return message;
            }
        });
    }
}
