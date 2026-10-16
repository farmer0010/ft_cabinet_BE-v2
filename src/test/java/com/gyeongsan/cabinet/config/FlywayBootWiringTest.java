package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gyeongsan.cabinet.adapter.out.persistence.admin.AdminActionLogEntity;
import com.gyeongsan.cabinet.support.BaselinedSchema;
import com.gyeongsan.cabinet.support.MariaDbDriverMySqlContainer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FlywayBootWiringTest {

    private static final Path MAIN_YML = Path.of("src/main/resources/application.yml");

    @Configuration
    @EntityScan(basePackageClasses = AdminActionLogEntity.class)
    static class EntityScanConfig {}

    private static ApplicationContextRunner runner(MariaDbDriverMySqlContainer mysql) {
        return new ApplicationContextRunner()
                .withInitializer(
                        context -> {
                            try {
                                for (PropertySource<?> source :
                                        new YamlPropertySourceLoader()
                                                .load(
                                                        "main-application-yml",
                                                        new FileSystemResource(MAIN_YML))) {
                                    context.getEnvironment().getPropertySources().addLast(source);
                                }
                            } catch (java.io.IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                .withConfiguration(
                        AutoConfigurations.of(
                                DataSourceAutoConfiguration.class,
                                DataSourceTransactionManagerAutoConfiguration.class,
                                JdbcTemplateAutoConfiguration.class,
                                SqlInitializationAutoConfiguration.class,
                                FlywayAutoConfiguration.class,
                                HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EntityScanConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=" + mysql.getJdbcUrl(),
                        "spring.datasource.username=" + mysql.getUsername(),
                        "spring.datasource.password=" + mysql.getPassword(),
                        "FLYWAY_ENABLED=true");
    }

    private static void prepareBaselinedExistingSchema(MariaDbDriverMySqlContainer mysql)
            throws Exception {
        BaselinedSchema.prepare(mysql);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.0", "mysql:8.4"})
    @DisplayName("FLYWAY_ENABLED=true 와 운영 yml 설정으로 부팅하면 Flyway 가 V2~V6 를 적용한 뒤 JPA(validate)가 뜬다")
    void bootsWithFlywayEnabled_usingRealApplicationYml(String image) throws Exception {
        try (MariaDbDriverMySqlContainer mysql = new MariaDbDriverMySqlContainer(image)) {
            mysql.start();
            prepareBaselinedExistingSchema(mysql);

            runner(mysql)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertThat(
                                                context.getEnvironment()
                                                        .getProperty(
                                                                "spring.jpa.hibernate.ddl-auto"))
                                        .isEqualTo("validate");

                                DataSource dataSource = context.getBean(DataSource.class);
                                try (Connection c = dataSource.getConnection();
                                        Statement s = c.createStatement();
                                        ResultSet rs =
                                                s.executeQuery(
                                                        "SELECT version, success FROM flyway_schema_history"
                                                                + " ORDER BY installed_rank")) {
                                    List<String> applied = new java.util.ArrayList<>();
                                    while (rs.next()) {
                                        applied.add(
                                                rs.getString("version")
                                                        + ":"
                                                        + rs.getInt("success"));
                                    }
                                    assertThat(applied)
                                            .containsExactly(
                                                    "1:1", "2:1", "3:1", "4:1", "5:1", "6:1");
                                }
                            });
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.4"})
    @DisplayName(
            "기준선: defer-datasource-initialization=true 를 켜면 Flyway 와 순환 의존으로 부팅이 실패한다(이 테스트가 문제를 실제로 잡는다는 증거)")
    void deferDatasourceInitializationBreaksFlyway(String image) throws Exception {
        try (MariaDbDriverMySqlContainer mysql = new MariaDbDriverMySqlContainer(image)) {
            mysql.start();
            prepareBaselinedExistingSchema(mysql);

            runner(mysql)
                    .withPropertyValues("spring.jpa.defer-datasource-initialization=true")
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                assertThat(context.getStartupFailure())
                                        .hasStackTraceContaining(
                                                "Circular depends-on relationship");
                            });
        }
    }
}
