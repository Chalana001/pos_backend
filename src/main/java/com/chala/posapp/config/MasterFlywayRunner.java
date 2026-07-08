package com.chala.posapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Slf4j
@Component
@Order(0)
@Profile("!test")
@RequiredArgsConstructor
public class MasterFlywayRunner implements ApplicationRunner {

    private final DataSource dataSource;

    @Value("${app.datasource.master-db:pos_master}")
    private String masterDb;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + masterDb + "`");
        }

        Flyway.configure()
                .dataSource(dataSource)
                .schemas(masterDb)
                .defaultSchema(masterDb)
                .locations("classpath:db/migration/master")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        log.info("Master database schema is ready: {}", masterDb);
    }
}
