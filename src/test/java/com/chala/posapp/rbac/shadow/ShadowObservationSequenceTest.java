package com.chala.posapp.rbac.shadow;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShadowObservationSequenceTest {
    @Test
    void methodEventsRemainIndependentAndResponseIsSeparate() {
        var allow = new ShadowObservation("corr-1", 1, "tenant-a", 7L, "CASHIER",
                new SecuredMethodIdentity("Controller", "create", java.util.List.of()),
                "/orders", "orders.create", "ALLOW", null,
                ComparisonStatus.MATCH, 0, true, Duration.ZERO);
        var deny = new ShadowObservation("corr-1", 2, "tenant-a", 7L, "CASHIER",
                new SecuredMethodIdentity("Service", "createOrder", java.util.List.of()),
                null, "orders.create", "DENY", null,
                ComparisonStatus.MISMATCH, 0, true, Duration.ZERO);
        var response = new ShadowObservation("corr-1", 0, "tenant-a", 7L, "CASHIER",
                null, null, null, "HTTP_RESPONSE", null,
                ComparisonStatus.NOT_APPLICABLE, 403, false, Duration.ZERO);

        assertEquals(1, allow.sequence());
        assertEquals(2, deny.sequence());
        assertEquals(0, response.sequence());
        assertEquals("Controller", allow.method().declaringClass());
        assertEquals("Service", deny.method().declaringClass());
        assertEquals(403, response.httpStatus());
    }
}
