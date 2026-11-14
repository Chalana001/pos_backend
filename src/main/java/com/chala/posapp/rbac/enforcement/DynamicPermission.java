package com.chala.posapp.rbac.enforcement;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks one exact method for opt-in tenant RBAC enforcement. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DynamicPermission {
    String permissionKey();

    String[] legacyRoles();

    boolean legacySuperAdmin() default false;
}
