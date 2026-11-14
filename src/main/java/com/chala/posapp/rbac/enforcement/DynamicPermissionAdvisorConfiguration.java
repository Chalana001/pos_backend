package com.chala.posapp.rbac.enforcement;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;

import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;

@Configuration
public class DynamicPermissionAdvisorConfiguration {
    @Bean
    public AuthorizationManagerBeforeMethodInterceptor dynamicPermissionAdvisor(
            DynamicPermissionAuthorizationManager manager,
            com.chala.posapp.rbac.shadow.AuthorizationEventPublisherDecorator eventPublisher) {
        AuthorizationManagerBeforeMethodInterceptor advisor =
                new AuthorizationManagerBeforeMethodInterceptor(dynamicPermissionPointcut(), manager);
        advisor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() - 1);
        advisor.setAuthorizationEventPublisher(eventPublisher);
        return advisor;
    }

    private Pointcut dynamicPermissionPointcut() {
        return new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return AnnotatedElementUtils.findMergedAnnotation(method, DynamicPermission.class) != null
                        || AnnotatedElementUtils.findMergedAnnotation(targetClass, DynamicPermission.class) != null;
            }
        };
    }
}
