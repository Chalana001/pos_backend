package com.chala.posapp.config;

import com.chala.posapp.tenant.CatalogMultiTenantConnectionProvider;
import com.chala.posapp.tenant.CurrentTenantResolver;
import lombok.RequiredArgsConstructor;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class HibernateMultiTenancyConfig implements HibernatePropertiesCustomizer {

    private final CatalogMultiTenantConnectionProvider connectionProvider;
    private final CurrentTenantResolver tenantResolver;

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantResolver);
    }
}
