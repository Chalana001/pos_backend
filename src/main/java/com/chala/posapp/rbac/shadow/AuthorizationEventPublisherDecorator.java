package com.chala.posapp.rbac.shadow;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/** Selects secured method events without replacing Spring Security's authorization manager. */
@Component
public class AuthorizationEventPublisherDecorator implements AuthorizationEventPublisher {
    private final AuthorizationEventPublisher delegate;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final boolean enabled;
    private final ThreadLocal<Boolean> selectedGrant = new ThreadLocal<>();

    public AuthorizationEventPublisherDecorator(
            org.springframework.context.ApplicationEventPublisher events,
            @Value("${app.rbac.shadow-enabled:false}") boolean enabled) {
        this.eventPublisher = events;
        this.enabled = enabled;
        var publisher = new org.springframework.security.authorization.SpringAuthorizationEventPublisher(events);
        publisher.setShouldPublishResult(result -> !result.isGranted() || Boolean.TRUE.equals(selectedGrant.get()));
        this.delegate = enabled ? publisher : new AuthorizationEventPublisher() {
            @Override
            public <T> void publishAuthorizationEvent(Supplier<org.springframework.security.core.Authentication> authentication,
                                                       T securedObject, org.springframework.security.authorization.AuthorizationResult result) {
            }
        };
    }

    @Override
    public <T> void publishAuthorizationEvent(Supplier<org.springframework.security.core.Authentication> authentication,
                                               T securedObject,
                                               AuthorizationResult result) {
        if (!enabled || result == null) return;
        boolean granted = result.isGranted();
        boolean selected = isSelected(securedObject);
        if (granted && !selected) {
            return;
        }
        selectedGrant.set(!granted || selected);
        try {
            if (granted) {
                eventPublisher.publishEvent(new org.springframework.security.authorization.event.AuthorizationGrantedEvent<>(authentication, securedObject, result));
            } else {
                delegate.publishAuthorizationEvent(authentication, securedObject, result);
            }
        } finally {
            selectedGrant.remove();
        }
    }

    private boolean isSelected(Object securedObject) {
        if (!(securedObject instanceof MethodInvocation invocation)) {
            return false;
        }
        Method method = invocation.getMethod();
        return LegacyPermissionMappingCatalog.find(SecuredMethodIdentity.of(method))
                .map(LegacyPermissionMapping::selectedForGrantedEvents)
                .orElse(false);
    }
}
