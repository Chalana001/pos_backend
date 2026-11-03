import org.flywaydb.core.Flyway;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Prepares disposable local MySQL databases for a real Spring Boot startup test.
 * Creates a tenant DB migrated through V69, a master DB migrated through V9,
 * and registers the tenant. The application's TenantFlywayRunner creates the
 * disposable legacy fallback DB and applies the tenant chain there.
 */
public class PreparePhase1MysqlStartup {
    public static void main(String[] args) throws Exception {
        String host = System.getProperty("phase1.mysql.host", "127.0.0.1");
        String port = System.getProperty("phase1.mysql.port", "3306");
        String user = System.getProperty("phase1.mysql.user", "root");
        String password = System.getProperty("phase1.mysql.password", "root");
        String tenant = System.getProperty("phase1.mysql.tenant");
        String master = System.getProperty("phase1.mysql.master");
        String legacy = System.getProperty("phase1.mysql.legacy");
        if (!host.equals("127.0.0.1") && !host.equals("localhost")) {
            throw new IllegalStateException("Refusing non-local MySQL host: " + host);
        }
        if (tenant == null || master == null || legacy == null) {
            throw new IllegalArgumentException("tenant, master and legacy database names are required");
        }

        String server = "jdbc:mysql://" + host + ":" + port
                + "/?allowPublicKeyRetrieval=true&useSSL=false";
        try (Connection connection = DriverManager.getConnection(server, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + tenant + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            statement.execute("CREATE DATABASE `" + master + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            statement.execute("CREATE DATABASE `" + legacy + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }

        String tenantUrl = "jdbc:mysql://" + host + ":" + port + "/" + tenant
                + "?allowPublicKeyRetrieval=true&useSSL=false";
        Flyway.configure()
                .dataSource(tenantUrl, user, password)
                .schemas(tenant)
                .defaultSchema(tenant)
                .locations("classpath:db/migration/tenant")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .mixed(true)
                .load()
                .migrate();

        String masterUrl = "jdbc:mysql://" + host + ":" + port + "/" + master
                + "?allowPublicKeyRetrieval=true&useSSL=false";
        Flyway.configure()
                .dataSource(masterUrl, user, password)
                .schemas(master)
                .defaultSchema(master)
                .locations("classpath:db/migration/master")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(masterUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO tenant_databases
                        (tenant_id, db_name, status, created_at, migrated_at)
                    VALUES
                        ('phase1-boot', %s, 'MIGRATED', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE
                        db_name = VALUES(db_name), status = 'MIGRATED', migrated_at = NOW(6)
                    """.formatted(sqlLiteral(tenant)));
        }
        System.out.println("READY_TENANT=" + tenant);
        System.out.println("READY_MASTER=" + master);
        System.out.println("READY_LEGACY=" + legacy);
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}