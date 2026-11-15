package com.alamano.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesCorrelationIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertNotNull(res.getHeader(CorrelationIdFilter.HEADER));
        // MDC debe estar limpio tras el filtro
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void generatesCorrelationIdWhenHeaderIsBlank() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CorrelationIdFilter.HEADER, "   ");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        String returned = res.getHeader(CorrelationIdFilter.HEADER);
        assertNotNull(returned);
        assertNotEquals("   ", returned.strip());
    }

    @Test
    void propagatesExistingCorrelationId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertEquals("abc-123", res.getHeader(CorrelationIdFilter.HEADER));
    }

    @Test
    void mdcIsSetDuringFilterAndClearedAfter() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(CorrelationIdFilter.HEADER, "trace-99");
        MockHttpServletResponse res = new MockHttpServletResponse();

        final String[] duringFilter = {null};
        filter.doFilterInternal(req, res, (rq, rs) -> duringFilter[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertEquals("trace-99", duringFilter[0]);
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
