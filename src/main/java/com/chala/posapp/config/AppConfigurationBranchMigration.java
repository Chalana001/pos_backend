package com.chala.posapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AppConfigurationBranchMigration implements ApplicationRunner {

    private static final String MIGRATION_KEY = "2026-05-29-app-configuration-branch-v1";
    private static final String TABLE_NAME = "app_configurations";
    private static final String INDEX_NAME = "uk_app_config_tenant_branch";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_migrations (
                    migration_key VARCHAR(120) PRIMARY KEY,
                    applied_at TIMESTAMP NOT NULL
                )
                """);

        Integer alreadyApplied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_migrations WHERE migration_key = ?",
                Integer.class,
                MIGRATION_KEY
        );
        if (alreadyApplied != null && alreadyApplied > 0) {
            return;
        }

        addColumnIfMissing("branch_id", "BIGINT NULL");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            dropLegacyTenantUnique(metaData, connection);
            createTenantBranchUnique(metaData, connection);
        }

        jdbcTemplate.update(
                "INSERT INTO app_migrations (migration_key, applied_at) VALUES (?, CURRENT_TIMESTAMP)",
                MIGRATION_KEY
        );
        log.info("Applied branch-wise app configuration migration");
    }

    private void addColumnIfMissing(String columnName, String definition) {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE LOWER(TABLE_NAME) = 'app_configurations'
                  AND LOWER(COLUMN_NAME) = ?
                """,
                Integer.class,
                columnName
        );
        if (columnCount == null || columnCount == 0) {
            jdbcTemplate.execute("ALTER TABLE app_configurations ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void dropLegacyTenantUnique(DatabaseMetaData metaData, Connection connection) throws SQLException {
        for (IndexInfo indexInfo : loadUniqueIndexes(metaData)) {
            if (indexInfo.columns.size() == 1 && "tenant_id".equals(indexInfo.columns.get(0))) {
                dropUniqueIndex(metaData, connection, indexInfo.name);
            }
        }
    }

    private void createTenantBranchUnique(DatabaseMetaData metaData, Connection connection) throws SQLException {
        boolean exists = loadUniqueIndexes(metaData).stream()
                .anyMatch(indexInfo -> INDEX_NAME.equalsIgnoreCase(indexInfo.name));
        if (exists) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX " + INDEX_NAME + " ON " + TABLE_NAME + " (tenant_id, branch_id)");
        }
    }

    private List<IndexInfo> loadUniqueIndexes(DatabaseMetaData metaData) throws SQLException {
        Map<String, List<ColumnInfo>> grouped = new HashMap<>();
        try (ResultSet resultSet = metaData.getIndexInfo(null, null, TABLE_NAME, true, false)) {
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                short ordinalPosition = resultSet.getShort("ORDINAL_POSITION");
                if (indexName == null || columnName == null || "PRIMARY".equalsIgnoreCase(indexName)) {
                    continue;
                }
                grouped.computeIfAbsent(indexName, ignored -> new ArrayList<>())
                        .add(new ColumnInfo(columnName.toLowerCase(Locale.ROOT), ordinalPosition));
            }
        }

        List<IndexInfo> indexes = new ArrayList<>();
        for (Map.Entry<String, List<ColumnInfo>> entry : grouped.entrySet()) {
            List<String> columns = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ColumnInfo::ordinalPosition))
                    .map(ColumnInfo::name)
                    .toList();
            indexes.add(new IndexInfo(entry.getKey(), columns));
        }
        return indexes;
    }

    private void dropUniqueIndex(DatabaseMetaData metaData, Connection connection, String indexName) throws SQLException {
        String database = metaData.getDatabaseProductName().toLowerCase(Locale.ROOT);
        try (Statement statement = connection.createStatement()) {
            if (database.contains("mysql")) {
                statement.execute("ALTER TABLE " + TABLE_NAME + " DROP INDEX " + indexName);
                return;
            }
            try {
                statement.execute("ALTER TABLE " + TABLE_NAME + " DROP CONSTRAINT " + indexName);
            } catch (SQLException constraintError) {
                statement.execute("DROP INDEX " + indexName);
            }
        }
    }

    private record ColumnInfo(String name, short ordinalPosition) {
    }

    private record IndexInfo(String name, List<String> columns) {
    }
}
