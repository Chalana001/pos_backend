package com.chala.posapp.rbac.shadow;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** Stable identity for one secured controller or service method invocation. */
public record SecuredMethodIdentity(
        String declaringClass,
        String methodName,
        List<String> parameterTypes) {
    public SecuredMethodIdentity {
        if (declaringClass == null || declaringClass.isBlank()) throw new IllegalArgumentException("declaringClass");
        if (methodName == null || methodName.isBlank()) throw new IllegalArgumentException("methodName");
        parameterTypes = List.copyOf(parameterTypes);
    }

    public static SecuredMethodIdentity of(Method method) {
        return new SecuredMethodIdentity(
                method.getDeclaringClass().getName(),
                method.getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList());
    }

    public String compact() {
        return declaringClass + "#" + methodName + "(" + String.join(",", parameterTypes) + ")";
    }
}
