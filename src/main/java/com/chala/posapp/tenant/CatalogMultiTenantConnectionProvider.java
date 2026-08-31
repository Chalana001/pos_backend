package com.chala.posapp.tenant;

import com.chala.posapp.service.TenantDatabaseRegistry;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
@RequiredArgsConstructor
public class CatalogMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;
    private final TenantDatabaseRegistry registry;

    @Value("${app.datasource.legacy-db:pos_db}")
    private String legacyDb;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    @Override
    public Connection getAnyConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setCatalog(legacyDb);
        return connection;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.setCatalog(legacyDb);
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        Connection connection = getAnyConnection();
        if (CurrentTenantResolver.MASTER_TENANT.equalsIgnoreCase(tenantIdentifier)) {
            connection.setCatalog(masterDb);
        } else {
            connection.setCatalog(registry.resolveCatalog(tenantIdentifier));
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        releaseAnyConnection(connection);
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return unwrapType.isAssignableFrom(getClass());
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return unwrapType.cast(this);
        }
        return null;
    }
}
