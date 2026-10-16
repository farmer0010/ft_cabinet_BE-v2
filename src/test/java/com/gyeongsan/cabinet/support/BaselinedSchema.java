package com.gyeongsan.cabinet.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

public final class BaselinedSchema {

    private BaselinedSchema() {}

    public static void createExistingTables(MySQLContainer<?> mysql) throws SQLException {
        try (Connection c =
                DriverManager.getConnection(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V1__baseline.sql"));
        }
    }

    public static void prepare(MySQLContainer<?> mysql) throws SQLException {
        createExistingTables(mysql);
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .baselineVersion("1")
                .baselineOnMigrate(false)
                .load()
                .baseline();
    }
}
