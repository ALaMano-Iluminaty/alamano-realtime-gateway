package com.alamano.gateway.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

class OriginRejectionHandshakeInterceptorTest {

    private static final String ALLOWED = "http://localhost:5173,http://localhost";

    private final OriginRejectionHandshakeInterceptor interceptor =
            new OriginRejectionHandshakeInterceptor(ALLOWED);

    @Test
    void allowedOriginPasses() throws Exception {
        assertTrue(intercept("http://localhost:5173"));
    }

    @Test
    void unknownOriginIsRejectedWith403() throws Exception {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        assertFalse(interceptor.beforeHandshake(
                request("http://evil.example.com"), response,
                mock(WebSocketHandler.class), new HashMap<>()));
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    void nullOriginPasses() throws Exception {
        assertTrue(intercept(null));
    }

    @Test
    void wildcardAllowsAnyOrigin() throws Exception {
        OriginRejectionHandshakeInterceptor wild = new OriginRejectionHandshakeInterceptor("*");
        assertTrue(wild.beforeHandshake(request("http://anywhere.com"), mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class), new HashMap<>()));
    }

    // ── helper ──────────────────────────────────────────────────────────────

    private boolean intercept(String origin) throws Exception {
        return interceptor.beforeHandshake(
                request(origin),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                new HashMap<>());
    }

    private static ServerHttpRequest request(String origin) {
        ServerHttpRequest req = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (origin != null) headers.setOrigin(origin);
        when(req.getHeaders()).thenReturn(headers);
        try { when(req.getURI()).thenReturn(URI.create("ws://localhost:8083/ws")); } catch (Exception ignored) {}
        return req;
    }
}
