package com.gyeongsan.cabinet.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.gyeongsan.cabinet.support.MariaDbDriverMySqlContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 로컬/데모용 가짜 시드({@code scripts/db/seed/demo-seed.sql})가 운영과 같은 Flyway 스키마(V1~V6)에 실제로 들어가는지, 여러 번
 * 실행해도 같은지 확인한다. 시드가 스키마와 어긋나면 데모 스택이 조용히 빈 채로 뜨는 대신 이 테스트가 실패한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class DemoSeedMysqlTest {

    private static final Path SEED = Path.of("scripts/db/seed/demo-seed.sql");

    private static List<String> statements() throws Exception {
        String sql =
                Files.readAllLines(SEED, StandardCharsets.UTF_8).stream()
                        .filter(l -> !l.stripLeading().startsWith("--"))
                        .reduce("", (a, b) -> a + b + "\n");
        return Arrays.stream(sql.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static long count(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.0", "mysql:8.4"})
    @DisplayName("가짜 데모 시드가 Flyway 스키마에 들어가고(사물함 90개, 유저 4명), 두 번 실행해도 같다")
    void demoSeedLoadsAndIsIdempotent(String image) throws Exception {
        try (MariaDbDriverMySqlContainer mysql = new MariaDbDriverMySqlContainer(image)) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection c =
                    DriverManager.getConnection(
                            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                for (int run = 1; run <= 2; run++) {
                    try (Statement s = c.createStatement()) {
                        for (String statement : statements()) {
                            s.execute(statement);
                        }
                    }
                    assertThat(count(c, "SELECT COUNT(*) FROM cabinet"))
                            .as("run " + run)
                            .isEqualTo(90);
                    assertThat(count(c, "SELECT COUNT(*) FROM `user`"))
                            .as("run " + run)
                            .isEqualTo(4);
                }

                assertThat(count(c, "SELECT COUNT(DISTINCT floor) FROM cabinet")).isEqualTo(3);
                assertThat(count(c, "SELECT COUNT(DISTINCT visible_num) FROM cabinet"))
                        .isEqualTo(90);
                assertThat(count(c, "SELECT COUNT(*) FROM `user` WHERE name NOT LIKE 'demo-%'"))
                        .as("실제 이름이 섞이지 않았다")
                        .isZero();
                assertThat(
                                count(
                                        c,
                                        "SELECT COUNT(*) FROM `user` WHERE email NOT LIKE '%@example.test'"))
                        .isZero();
                assertThat(count(c, "SELECT COUNT(*) FROM `user` WHERE role = 'ADMIN'"))
                        .isEqualTo(1);
                assertThat(count(c, "SELECT COUNT(*) FROM `user` WHERE is_pisciner = 1"))
                        .isEqualTo(1);
            }
        }
    }
}
