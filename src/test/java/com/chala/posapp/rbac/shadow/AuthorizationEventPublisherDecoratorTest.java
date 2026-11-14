package com.chala.posapp.rbac.shadow;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.authorization.event.AuthorizationGrantedEvent;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationEventPublisherDecoratorTest {
    @Test
    void selectedMappedGrantAndDeniedEventArePublishedButUnselectedGrantIsFiltered() throws Exception {
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher events = published::add;
        AuthorizationEventPublisherDecorator publisher = new AuthorizationEventPublisherDecorator(events, true);
        Method selected = com.chala.posapp.controller.OrderController.class
                .getDeclaredMethod("create", com.chala.posapp.dto.order.CreateOrderRequest.class);
        Method unselected = com.chala.posapp.controller.CustomerNotesController.class
                .getDeclaredMethod("list", Long.class, int.class, int.class);
        MethodInvocation selectedInvocation = invocation(selected);
        MethodInvocation unselectedInvocation = invocation(unselected);
        Supplier<Authentication> auth = () -> mock(Authentication.class);

        publisher.publishAuthorizationEvent(auth, selectedInvocation, new AuthorizationDecision(true));
        publisher.publishAuthorizationEvent(auth, unselectedInvocation, new AuthorizationDecision(true));
        publisher.publishAuthorizationEvent(auth, unselectedInvocation, new AuthorizationDecision(false));

        assertEquals(2, published.size());
        assertTrue(published.stream().anyMatch(AuthorizationGrantedEvent.class::isInstance));
        assertTrue(published.stream().anyMatch(AuthorizationDeniedEvent.class::isInstance));
        assertEquals(selectedInvocation, published.stream().filter(AuthorizationGrantedEvent.class::isInstance)
                .map(AuthorizationGrantedEvent.class::cast).findFirst().orElseThrow().getObject());
    }

    @Test
    void disabledPublisherDoesNotPublishEvents() throws Exception {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AuthorizationEventPublisherDecorator publisher = new AuthorizationEventPublisherDecorator(events, false);
        Method selected = com.chala.posapp.controller.OrderController.class
                .getDeclaredMethod("create", com.chala.posapp.dto.order.CreateOrderRequest.class);
        publisher.publishAuthorizationEvent(() -> mock(Authentication.class), invocation(selected), new AuthorizationDecision(true));
        verifyNoInteractions(events);
    }

    private static MethodInvocation invocation(Method method) {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method);
        return invocation;
    }
}
