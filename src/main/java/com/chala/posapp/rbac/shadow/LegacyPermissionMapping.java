package com.chala.posapp.rbac.shadow;

import java.util.List;
import java.util.Objects;

public record LegacyPermissionMapping(
        SecuredMethodIdentity method,
        boolean controllerMethod,
        List<String> httpMethods,
        List<String> routeTemplates,
        String permissionKey,
        ShadowClassification classification,
        String constraintNotes,
        boolean selectedForGrantedEvents) {
    public LegacyPermissionMapping {
        Objects.requireNonNull(method, "method");
        httpMethods = List.copyOf(httpMethods);
        routeTemplates = List.copyOf(routeTemplates);
        Objects.requireNonNull(classification, "classification");
        constraintNotes = constraintNotes == null ? "" : constraintNotes;
        if (classification == ShadowClassification.MAPPED_CAPABILITY
                && (permissionKey == null || permissionKey.isBlank())) {
            throw new IllegalArgumentException("Mapped capability requires permission key");
        }
        if (classification != ShadowClassification.MAPPED_CAPABILITY && selectedForGrantedEvents) {
            throw new IllegalArgumentException("Only mapped capabilities may publish selected grants");
        }
    }
}
