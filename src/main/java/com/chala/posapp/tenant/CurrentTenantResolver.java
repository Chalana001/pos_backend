package com.chala.posapp.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class CurrentTenantResolver implements CurrentTenantIdentifierResolver<String> {

    public static final String MASTER_TENANT = "MASTER";
    public static final String LEGACY_TENANT = "LEGACY";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenant = TenantContext.getTenant();
        return tenant == null || tenant.isBlank() ? LEGACY_TENANT : tenant;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
