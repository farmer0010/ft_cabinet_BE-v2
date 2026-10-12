package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gyeongsan.cabinet.support.BaselinedSchema;
import com.gyeongsan.cabinet.support.MariaDbDriverMySqlContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V5·V6 마이그레이션 리허설(운영 이행 전 검증). 코드/스키마를 바꾸는 테스트가 아니라, 운영 이행 때 일어날 수 있는 일을 실제 DB 에서 미리 돌려 본다.
 *
 * <ol>
 *   <li>운영 경로: V1 스키마가 이미 있는 DB 에 baseline(1)만 찍고 V2~V6 만 올라가는지, 데이터가 보존되는지, 엔티티가 validate 를 통과하는지.
 *   <li>중간 실패: V5·V6 는 DDL 이 여러 문장이고 MySQL/MariaDB 의 DDL 은 롤백되지 않는다. 중간에 실패하면 무엇이 반쯤 남는지, Flyway 가
 *       어떤 상태가 되는지, 복구 절차가 실제로 통하는지.
 *   <li>잠금: user 테이블 ALTER(V4, V6)가 큰 테이블에서도 메타데이터만 바꾸는지(ALGORITHM=INSTANT), 그리고 열린 트랜잭션이 있을 때
 *       ALTER 가 어떻게 막히는지(메타데이터 락).
 * </ol>
 *
 * 관찰 결과는 {@code build/migration-rehearsal.txt} 에도 남긴다. Docker 가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class MigrationRehearsalMysqlTest {

    private static final Path REPORT = Path.of("build/migration-rehearsal.txt");

    @Configuration
    @EntityScan("com.gyeongsan.cabinet")
    static class AllEntitiesConfig {}

    private static void note(String line) {
        System.out.println("[REHEARSAL] " + line);
        try {
            Files.createDirectories(REPORT.getParent());
            Files.writeString(
                    REPORT,
                    line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 보고용 부가 기록이다.
        }
    }

    private static Flyway flyway(MariaDbDriverMySqlContainer db) {
        return flyway(db, null);
    }

    private static Flyway flyway(MariaDbDriverMySqlContainer db, String initSql) {
        var config =
                Flyway.configure()
                        .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                        .locations("classpath:db/migration")
                        .baselineVersion("1")
                        .baselineOnMigrate(false)
                        .cleanDisabled(true)
                        .validateOnMigrate(true);
        if (initSql != null) {
            config.initSql(initSql);
        }
        return config.load();
    }

    private static Connection connect(MariaDbDriverMySqlContainer db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static void exec(MariaDbDriverMySqlContainer db, String... statements)
            throws SQLException {
        try (Connection c = connect(db);
                Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static List<String> history(MariaDbDriverMySqlContainer db) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(db);
                Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT version, type, success FROM flyway_schema_history"
                                        + " ORDER BY installed_rank")) {
            while (rs.next()) {
                rows.add(rs.getString(1) + ":" + rs.getString(2) + ":" + rs.getInt(3));
            }
        }
        return rows;
    }

    private static boolean tableExists(MariaDbDriverMySqlContainer db, String table)
            throws SQLException {
        return count(
                        db,
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema ="
                                + " DATABASE() AND table_name = '"
                                + table
                                + "'")
                > 0;
    }

    private static boolean columnExists(MariaDbDriverMySqlContainer db, String table, String column)
            throws SQLException {
        return count(
                        db,
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema ="
                                + " DATABASE() AND table_name = '"
                                + table
                                + "' AND column_name = '"
                                + column
                                + "'")
                > 0;
    }

    private static long count(MariaDbDriverMySqlContainer db, String sql) throws SQLException {
        try (Connection c = connect(db);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void assertEntitiesValidate(MariaDbDriverMySqlContainer db) {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                DataSourceAutoConfiguration.class,
                                DataSourceTransactionManagerAutoConfiguration.class,
                                HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(AllEntitiesConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=" + db.getJdbcUrl(),
                        "spring.datasource.username=" + db.getUsername(),
                        "spring.datasource.password=" + db.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.jpa.open-in-view=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mariadb:10.6", "mysql:8.0"})
    @DisplayName(
            "운영 경로: V1 만 있는 DB 에 baseline(1) 후 migrate 하면 V2~V6 만 올라가고 데이터가 보존되며 validate 를 통과한다")
    void productionPath(String image) throws Exception {
        try (MariaDbDriverMySqlContainer db = new MariaDbDriverMySqlContainer(image)) {
            db.start();
            BaselinedSchema.createExistingTables(db);
            exec(db, "INSERT INTO `user` (name, is_pisciner) VALUES ('existing-user', b'0')");

            Flyway flyway = flyway(db);
            flyway.baseline();
            MigrateResult result = flyway.migrate();

            assertThat(result.migrationsExecuted).isEqualTo(5);
            assertThat(history(db))
                    .containsExactly(
                            "1:BASELINE:1", "2:SQL:1", "3:SQL:1", "4:SQL:1", "5:SQL:1", "6:SQL:1");
            assertThat(count(db, "SELECT COUNT(*) FROM `user`")).isEqualTo(1);
            // 기존 사용자의 새 컬럼 값: kakao_alarm 은 꺼짐(0), ft_grade 는 NULL(일반 취급).
            assertThat(count(db, "SELECT kakao_alarm FROM `user`")).isZero();
            assertThat(count(db, "SELECT COUNT(*) FROM `user` WHERE ft_grade IS NULL"))
                    .isEqualTo(1);
            flyway.validate();
            assertEntitiesValidate(db);
            note(image + " 운영 경로: baseline 후 V2~V6 5개 실행, 데이터 보존, validate 통과");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mariadb:10.6", "mysql:8.0"})
    @DisplayName(
            "V5 가 두 번째 CREATE 에서 실패하면: 첫 테이블은 남고 Flyway 는 실패 상태가 되며, 반쯤 남은 것을 치우고 repair 하면 이어서 적용된다")
    void v5PartialFailureAndRecovery(String image) throws Exception {
        try (MariaDbDriverMySqlContainer db = new MariaDbDriverMySqlContainer(image)) {
            db.start();
            BaselinedSchema.prepare(db);
            // 누군가 같은 이름의 테이블을 먼저 만들어 둔 상황 → V5 의 첫 CREATE(faq)는 성공하고 둘째(faq_question)가 실패한다.
            exec(db, "CREATE TABLE faq_question (x INT)");

            Flyway flyway = flyway(db);
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);

            List<String> afterFailure = history(db);
            note(
                    image
                            + " V5 중간 실패 후: history="
                            + afterFailure
                            + ", faq 테이블 존재="
                            + tableExists(db, "faq")
                            + ", V6 컬럼(kakao_alarm) 존재="
                            + columnExists(db, "user", "kakao_alarm"));
            assertThat(tableExists(db, "faq")).as("DDL 은 롤백되지 않아 첫 CREATE 의 결과가 남는다").isTrue();
            assertThat(afterFailure).contains("2:SQL:1", "3:SQL:1", "4:SQL:1");
            assertThat(afterFailure.stream().filter(r -> r.startsWith("5:")).toList())
                    .as("실패한 V5 는 success=0 으로 기록된다")
                    .containsExactly("5:SQL:0");
            assertThat(columnExists(db, "user", "kakao_alarm")).isFalse();

            // 그대로 다시 migrate 하면 '실패한 마이그레이션이 있다'며 거부한다.
            assertThatThrownBy(flyway::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("failed migration");

            // repair 만 하고 반쯤 남은 faq 를 안 치우면 같은 곳에서 또 실패한다(CREATE TABLE faq 가 이미 있음).
            flyway.repair();
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
            note(image + " repair 만 하고 재시도: 다시 실패(반쯤 남은 faq 때문)");

            // 올바른 복구: 반쯤 남은 것과 충돌 원인을 치우고, 실패 기록을 repair 로 지운 뒤 migrate.
            exec(db, "DROP TABLE IF EXISTS faq_question", "DROP TABLE IF EXISTS faq");
            flyway.repair();
            MigrateResult retry = flyway.migrate();

            assertThat(retry.migrationsExecuted).isEqualTo(2); // V5, V6
            assertThat(history(db))
                    .containsExactly(
                            "1:BASELINE:1", "2:SQL:1", "3:SQL:1", "4:SQL:1", "5:SQL:1", "6:SQL:1");
            flyway.validate();
            assertEntitiesValidate(db);
            note(image + " V5 복구 절차(남은 테이블 DROP → repair → migrate) 통과, validate 통과");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mariadb:10.6", "mysql:8.0"})
    @DisplayName(
            "V6 가 CREATE TABLE 에서 실패하면: 이미 추가된 user.kakao_alarm 이 남아 repair 만으로는 부족하고, 컬럼을 되돌린 뒤에야 다시 적용된다")
    void v6PartialFailureAndRecovery(String image) throws Exception {
        try (MariaDbDriverMySqlContainer db = new MariaDbDriverMySqlContainer(image)) {
            db.start();
            BaselinedSchema.prepare(db);
            // V6 는 ALTER(컬럼 추가) → CREATE TABLE 순서다. 같은 이름의 테이블이 있으면 컬럼은 추가된 채로 둘째에서 실패한다.
            exec(db, "CREATE TABLE kakao_notify_consent (x INT)");

            Flyway flyway = flyway(db);
            assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);

            List<String> afterFailure = history(db);
            note(
                    image
                            + " V6 중간 실패 후: history="
                            + afterFailure
                            + ", user.kakao_alarm 존재="
                            + columnExists(db, "user", "kakao_alarm"));
            assertThat(afterFailure).contains("5:SQL:1");
            assertThat(afterFailure.stream().filter(r -> r.startsWith("6:")).toList())
                    .containsExactly("6:SQL:0");
            assertThat(columnExists(db, "user", "kakao_alarm")).as("ALTER 는 이미 적용돼 남는다").isTrue();

            flyway.repair();
            assertThatThrownBy(flyway::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("kakao_alarm"); // Duplicate column name
            note(image + " repair 만 하고 재시도: 다시 실패(Duplicate column kakao_alarm)");

            exec(
                    db,
                    "DROP TABLE IF EXISTS kakao_notify_consent",
                    "ALTER TABLE `user` DROP COLUMN kakao_alarm");
            flyway.repair();
            MigrateResult retry = flyway.migrate();

            assertThat(retry.migrationsExecuted).isEqualTo(1);
            assertThat(history(db).get(history(db).size() - 1)).isEqualTo("6:SQL:1");
            flyway.validate();
            assertEntitiesValidate(db);
            note(image + " V6 복구 절차(컬럼 DROP + 남은 테이블 DROP → repair → migrate) 통과, validate 통과");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mariadb:10.6", "mysql:8.0"})
    @DisplayName("user 테이블 ALTER(V4, V6)는 행이 많아도 ALGORITHM=INSTANT 로 가능하다(테이블 재구성 없음)")
    void userAlterIsInstantOnLargeTable(String image) throws Exception {
        try (MariaDbDriverMySqlContainer db = new MariaDbDriverMySqlContainer(image)) {
            db.start();
            BaselinedSchema.createExistingTables(db);
            // 운영 규모(수천 행)보다 훨씬 큰 20만 행을 넣어, 재구성이 일어난다면 눈에 띄게 한다.
            try (Connection c = connect(db);
                    Statement s = c.createStatement()) {
                s.execute("INSERT INTO `user` (name, is_pisciner) VALUES ('seed', b'0')");
                for (int i = 0; i < 18; i++) {
                    s.execute(
                            "INSERT INTO `user` (name, is_pisciner)"
                                    + " SELECT CONCAT('r"
                                    + i
                                    + "_', id), b'0'"
                                    + " FROM `user`");
                }
            }
            long rows = count(db, "SELECT COUNT(*) FROM `user`");
            assertThat(rows).isGreaterThan(200_000);

            long t4 =
                    timed(
                            db,
                            "ALTER TABLE `user` ADD COLUMN ft_grade VARCHAR(32) NULL, ALGORITHM=INSTANT");
            long t6 =
                    timed(
                            db,
                            "ALTER TABLE `user` ADD COLUMN kakao_alarm BIT(1) NOT NULL DEFAULT b'0', ALGORITHM=INSTANT");
            note(
                    image
                            + " user "
                            + rows
                            + "행: V4 ADD COLUMN INSTANT "
                            + t4
                            + "ms, V6 ADD COLUMN INSTANT "
                            + t6
                            + "ms");
            assertThat(columnExists(db, "user", "ft_grade")).isTrue();
            assertThat(columnExists(db, "user", "kakao_alarm")).isTrue();
        }
    }

    private static long timed(MariaDbDriverMySqlContainer db, String sql) throws SQLException {
        long start = System.nanoTime();
        exec(db, sql);
        return (System.nanoTime() - start) / 1_000_000;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mariadb:10.6", "mysql:8.0"})
    @DisplayName(
            "열린 트랜잭션이 user 를 쥐고 있으면 ALTER 는 메타데이터 락을 기다리고, 그동안 새 조회도 줄 서서 막힌다 (lock_wait_timeout 으로 한도를 둘 수 있다)")
    void metadataLockBlocksAlterAndQueuesReaders(String image) throws Exception {
        try (MariaDbDriverMySqlContainer db = new MariaDbDriverMySqlContainer(image)) {
            db.start();
            BaselinedSchema.prepare(db);

            try (Connection longTx = connect(db);
                    Statement tx = longTx.createStatement()) {
                longTx.setAutoCommit(false);
                tx.executeQuery("SELECT COUNT(*) FROM `user`").close(); // 트랜잭션이 user 의 메타데이터 락을 쥔다.

                // Flyway 세션에 lock_wait_timeout 을 줘서 무한정 기다리지 않게 한다(initSql).
                Flyway guarded = flyway(db, "SET SESSION lock_wait_timeout = 3");
                var readerWait = new long[1];
                Thread reader =
                        new Thread(
                                () -> {
                                    try {
                                        Thread.sleep(1_000); // ALTER 가 줄을 선 뒤에 읽기를 시도한다.
                                        long start = System.nanoTime();
                                        try (Connection c = connect(db);
                                                Statement s = c.createStatement()) {
                                            s.execute("SET SESSION lock_wait_timeout = 10");
                                            s.executeQuery("SELECT 1 FROM `user` LIMIT 1").close();
                                        }
                                        readerWait[0] = (System.nanoTime() - start) / 1_000_000;
                                    } catch (Exception e) {
                                        readerWait[0] = -1;
                                    }
                                });
                reader.start();
                long start = System.nanoTime();
                assertThatThrownBy(guarded::migrate).isInstanceOf(FlywayException.class);
                long alterWait = (System.nanoTime() - start) / 1_000_000;
                reader.join();

                note(
                        image
                                + " 열린 트랜잭션이 user 를 쥔 채 migrate: "
                                + alterWait
                                + "ms 기다린 뒤 lock wait timeout 으로 실패, 그 사이 들어온 SELECT 대기="
                                + readerWait[0]
                                + "ms, history="
                                + history(db));
                assertThat(alterWait).as("ALTER 가 락을 기다렸다").isGreaterThanOrEqualTo(2_500);
                assertThat(readerWait[0])
                        .as("ALTER 뒤에 줄 선 일반 조회도 함께 막혔다")
                        .isGreaterThanOrEqualTo(1_000);
            }

            // 트랜잭션이 끝나면(커밋/롤백) 같은 migrate 가 정상 적용된다.
            Flyway flyway = flyway(db);
            flyway.repair();
            flyway.migrate();
            flyway.validate();
            note(image + " 열린 트랜잭션 해소 후 repair → migrate 로 정상 적용");
        }
    }
}
