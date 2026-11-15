package com.alamano.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

class CorrelationIdChannelInterceptorTest {

    private final CorrelationIdChannelInterceptor interceptor = new CorrelationIdChannelInterceptor();

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void connectWithHeaderStoresCorrelationIdInSessionAndMdc() {
        Message<?> msg = connect("correlation-abc");

        interceptor.preSend(msg, null);

        assertEquals("correlation-abc", MDC.get(CorrelationIdFilter.MDC_KEY));
        StompHeaderAccessor acc = StompHeaderAccessor.getAccessor(msg, StompHeaderAccessor.class);
        assertEquals("correlation-abc", acc.getSessionAttributes().get(CorrelationIdChannelInterceptor.SESSION_ATTR));
    }

    @Test
    void connectWithoutHeaderGeneratesCorrelationId() {
        Message<?> msg = connect(null);

        interceptor.preSend(msg, null);

        String inMdc = MDC.get(CorrelationIdFilter.MDC_KEY);
        assertNotNull(inMdc);
        StompHeaderAccessor acc = StompHeaderAccessor.getAccessor(msg, StompHeaderAccessor.class);
        assertEquals(inMdc, acc.getSessionAttributes().get(CorrelationIdChannelInterceptor.SESSION_ATTR));
    }

    @Test
    void nonConnectFrameRestoresCorrelationIdFromSession() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(CorrelationIdChannelInterceptor.SESSION_ATTR, "session-cid");
        Message<?> msg = frameWithAttrs(StompCommand.SUBSCRIBE, attrs);

        interceptor.preSend(msg, null);

        assertEquals("session-cid", MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void nonConnectFrameWithEmptySessionDoesNotSetMdc() {
        Map<String, Object> attrs = new HashMap<>(); // no correlationId key
        Message<?> msg = frameWithAttrs(StompCommand.SEND, attrs);

        interceptor.preSend(msg, null);

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void nonConnectFrameWithNullAttrsDoesNotSetMdc() {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.SEND);
        acc.setSessionId("s1");
        // no setSessionAttributes → getSessionAttributes() returns null
        Message<?> msg = MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());

        interceptor.preSend(msg, null);

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void nonStompMessagePassesThrough() {
        Message<String> plain = MessageBuilder.withPayload("hello").build();

        Message<?> result = interceptor.preSend(plain, null);

        assertSame(plain, result);
    }

    @Test
    void afterSendCompletionClearsMdc() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "to-be-cleared");
        Message<?> msg = connect("any");

        interceptor.afterSendCompletion(msg, null, true, null);

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Message<?> connect(String correlationId) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(StompCommand.CONNECT);
        acc.setSessionId("test-session");
        acc.setSessionAttributes(new HashMap<>());
        if (correlationId != null) {
            acc.addNativeHeader(CorrelationIdChannelInterceptor.STOMP_HEADER, correlationId);
        }
        return MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());
    }

    private static Message<?> frameWithAttrs(StompCommand command, Map<String, Object> attrs) {
        StompHeaderAccessor acc = StompHeaderAccessor.create(command);
        acc.setSessionId("test-session");
        acc.setSessionAttributes(attrs);
        return MessageBuilder.createMessage(new byte[0], acc.getMessageHeaders());
    }
}
