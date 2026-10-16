package com.gyeongsan.cabinet.application.lent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongsan.cabinet.adapter.in.web.admin.dto.BulkStatusUpdateRequest;
import com.gyeongsan.cabinet.adapter.in.web.admin.dto.BulkStatusUpdateResponse;
import com.gyeongsan.cabinet.adapter.in.web.admin.dto.UndoRequest;
import com.gyeongsan.cabinet.adapter.out.cache.redis.ReservationRedisAdapter;
import com.gyeongsan.cabinet.adapter.out.persistence.admin.AdminActionLogJpaRepository;
import com.gyeongsan.cabinet.adapter.out.persistence.admin.AdminActionLogPersistenceAdapter;
import com.gyeongsan.cabinet.common.lock.DistributedLockAop;
import com.gyeongsan.cabinet.domain.admin.model.AdminActor;
import com.gyeongsan.cabinet.domain.admin.model.UndoConflictCode;
import com.gyeongsan.cabinet.domain.admin.port.in.AdminCabinetUseCase;
import com.gyeongsan.cabinet.domain.admin.port.in.AdminUndoUseCase;
import com.gyeongsan.cabinet.domain.admin.port.out.AdminActionLogPort;
import com.gyeongsan.cabinet.domain.admin.service.AdminCabinetService;
import com.gyeongsan.cabinet.domain.admin.service.AdminUndoService;
import com.gyeongsan.cabinet.domain.cabinet.model.Cabinet;
import com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus;
import com.gyeongsan.cabinet.domain.cabinet.port.out.CabinetRepositoryPort;
import com.gyeongsan.cabinet.domain.item.port.out.ItemHistoryRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.model.LentHistory;
import com.gyeongsan.cabinet.domain.lent.port.out.AiCheckPort;
import com.gyeongsan.cabinet.domain.lent.port.out.ImageUploadPort;
import com.gyeongsan.cabinet.domain.lent.port.out.LentRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.ReservationPort;
import com.gyeongsan.cabinet.domain.user.model.User;
import com.gyeongsan.cabinet.domain.user.port.out.UserRepositoryPort;
import com.gyeongsan.cabinet.global.exception.UndoRejectedException;
import com.gyeongsan.cabinet.support.MariaDbDriverMySqlContainer;
import com.gyeongsan.cabinet.support.RedisTestSupport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AdminUndoSnapshotMysqlTest {

    private static final AdminActor ADMIN = new AdminActor(1L, "admin01");

    private static final ThreadLocal<Gate> GATE = new ThreadLocal<>();

    static final class Gate {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startRedis() {
        redis = RedisTestSupport.newRedis();
        redis.start();
        redisFactory = RedisTestSupport.connectionFactory(redis);
        redisTemplate = RedisTestSupport.template(redisFactory);
    }

    @AfterAll
    static void stopRedis() {
        if (redisFactory != null) {
            redisFactory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    @EnableTransactionManagement
    @EntityScan(basePackages = "com.gyeongsan.cabinet")
    @EnableJpaRepositories(basePackages = "com.gyeongsan.cabinet.adapter.out.persistence")
    @ComponentScan(
            basePackages = {
                "com.gyeongsan.cabinet.adapter.out.persistence.user",
                "com.gyeongsan.cabinet.adapter.out.persistence.cabinet",
                "com.gyeongsan.cabinet.adapter.out.persistence.lent",
                "com.gyeongsan.cabinet.adapter.out.persistence.item"
            })
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        StringRedisTemplate redisTemplate() {
            return redisTemplate;
        }

        @Bean
        DistributedLockAop distributedLockAop(StringRedisTemplate template) {
            return new DistributedLockAop(template);
        }

        @Bean
        ReservationPort reservationPort(StringRedisTemplate template) {
            return new ReservationRedisAdapter(template);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
        }

        @Bean
        AiCheckPort aiCheckPort() {
            AiCheckPort port = mock(AiCheckPort.class);
            when(port.checkItem(any())).thenReturn(true);
            return port;
        }

        @Bean
        ImageUploadPort imageUploadPort() {
            ImageUploadPort port = mock(ImageUploadPort.class);
            when(port.uploadImage(any(), any())).thenReturn("https://photo.example/test.jpg");
            return port;
        }

        @Bean
        AdminActionLogPort adminActionLogPort(
                AdminActionLogJpaRepository repository, ObjectMapper objectMapper) {
            AdminActionLogPort real =
                    new AdminActionLogPersistenceAdapter(repository, objectMapper);
            return (AdminActionLogPort)
                    Proxy.newProxyInstance(
                            AdminActionLogPort.class.getClassLoader(),
                            new Class<?>[] {AdminActionLogPort.class},
                            (proxy, method, args) -> {
                                Object result;
                                try {
                                    result = method.invoke(real, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                Gate gate = GATE.get();
                                if (gate != null && method.getName().equals("findByBatchId")) {
                                    GATE.remove();
                                    gate.reached.countDown();
                                    if (!gate.release.await(30, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("게이트가 풀리지 않았다");
                                    }
                                }
                                return result;
                            });
        }

        @Bean
        AdminCabinetService adminCabinetService(
                CabinetRepositoryPort cabinets, LentRepositoryPort lents, AdminActionLogPort logs) {
            return new AdminCabinetService(cabinets, lents, logs);
        }

        @Bean
        AdminUndoService adminUndoService(
                AdminActionLogPort logs,
                CabinetRepositoryPort cabinets,
                LentRepositoryPort lents,
                UserRepositoryPort users,
                ReservationPort reservations,
                TransactionTemplate transactionTemplate) {
            return new AdminUndoService(
                    logs, cabinets, lents, users, reservations, transactionTemplate);
        }

        @Bean
        LentApplicationService lentApplicationService(
                UserRepositoryPort users,
                CabinetRepositoryPort cabinets,
                LentRepositoryPort lents,
                ItemHistoryRepositoryPort itemHistories,
                ReservationPort reservations,
                AiCheckPort aiCheckPort,
                ImageUploadPort imageUploadPort,
                TransactionTemplate transactionTemplate) {
            return new LentApplicationService(
                    users,
                    cabinets,
                    lents,
                    itemHistories,
                    reservations,
                    aiCheckPort,
                    imageUploadPort,
                    transactionTemplate);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(
                LentConcurrencyMysqlTest.Fixture f, AdminCabinetUseCase bulk, AdminUndoUseCase undo)
                throws Exception;
    }

    private static void run(String image, Body body) throws Exception {
        try (MariaDbDriverMySqlContainer mysql = new MariaDbDriverMySqlContainer(image)) {
            mysql.start();
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
            Throwable[] failure = new Throwable[1];
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    DataSourceAutoConfiguration.class,
                                    DataSourceTransactionManagerAutoConfiguration.class,
                                    JdbcTemplateAutoConfiguration.class,
                                    TransactionAutoConfiguration.class,
                                    HibernateJpaAutoConfiguration.class))
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "spring.datasource.url=" + mysql.getJdbcUrl(),
                            "spring.datasource.username=" + mysql.getUsername(),
                            "spring.datasource.password=" + mysql.getPassword(),
                            "spring.datasource.driver-class-name=org.mariadb.jdbc.Driver",
                            "spring.jpa.hibernate.ddl-auto=create",
                            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MariaDBDialect",
                            "cabinet.policy.lent-term=31",
                            "cabinet.policy.extension-term=3")
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                try {
                                    body.run(
                                            new LentConcurrencyMysqlTest.Fixture(context),
                                            context.getBean(AdminCabinetUseCase.class),
                                            context.getBean(AdminUndoUseCase.class));
                                } catch (Throwable t) {
                                    failure[0] = t;
                                }
                            });
            if (failure[0] != null) {
                if (failure[0] instanceof Error e) {
                    throw e;
                }
                throw new RuntimeException(failure[0]);
            }
        }
    }

    private static Object[] bulkReturnedUser(
            LentConcurrencyMysqlTest.Fixture f, AdminCabinetUseCase bulk) {
        Long userId = f.newUser(f.lentItem);
        int cabinetNum = f.newCabinet(CabinetStatus.FULL);
        f.rent(userId, cabinetNum, LocalDateTime.now().plusDays(10));
        Long cabinetId =
                f.jdbc.queryForObject(
                        "SELECT id FROM cabinet WHERE visible_num = ?", Long.class, cabinetNum);
        BulkStatusUpdateResponse result =
                bulk.bulkUpdateCabinetStatus(
                        new BulkStatusUpdateRequest(
                                List.of(cabinetId),
                                CabinetStatus.PENDING,
                                null,
                                null,
                                true,
                                "월말 일괄 반납"),
                        ADMIN);
        return new Object[] {userId, result.batchId()};
    }

    private static String undoOutcome(AdminUndoUseCase undo, String batchId) {
        try {
            undo.undoBulkStatusUpdate(batchId, new UndoRequest("되돌림"), ADMIN);
            return "OK";
        } catch (UndoRejectedException e) {
            return e.getRejection().conflicts().stream()
                    .map(c -> c.code().name())
                    .sorted()
                    .toList()
                    .toString();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.0", "mysql:8.4"})
    @DisplayName("[결정적] Undo 가 감사 로그를 읽은 뒤 같은 사용자가 다른 사물함을 대여하면, Undo 는 거부되고 활성 대여는 1건이다")
    void undoRejectedWhenUserRentsAfterSnapshot_hook(String image) throws Exception {
        run(
                image,
                (f, bulk, undo) -> {
                    Object[] setup = bulkReturnedUser(f, bulk);
                    Long userId = (Long) setup[0];
                    String batchId = (String) setup[1];
                    int target = f.newCabinet(CabinetStatus.AVAILABLE);

                    Gate gate = new Gate();
                    CompletableFuture<String> undoResult =
                            CompletableFuture.supplyAsync(
                                    () -> {
                                        GATE.set(gate);
                                        try {
                                            return undoOutcome(undo, batchId);
                                        } finally {
                                            GATE.remove();
                                        }
                                    });
                    assertThat(gate.reached.await(30, TimeUnit.SECONDS)).isTrue();

                    f.service.startLent(userId, target);

                    gate.release.countDown();
                    String result = undoResult.get(30, TimeUnit.SECONDS);

                    assertThat(result)
                            .as("활성 대여 %s건", f.activeLents(userId))
                            .isEqualTo("[" + UndoConflictCode.USER_HAS_ACTIVE_LENT + "]");
                    assertThat(f.activeLents(userId)).isEqualTo(1);
                });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.0", "mysql:8.4"})
    @DisplayName("[훅 없음] 동시 대여가 사용자 락을 쥔 채 커밋하는 동안 Undo 가 락을 기다렸다면, Undo 는 거부되고 활성 대여는 1건이다")
    void undoRejectedWhenConcurrentRentCommitsWhileUndoWaitsForLock(String image) throws Exception {
        run(
                image,
                (f, bulk, undo) -> {
                    Object[] setup = bulkReturnedUser(f, bulk);
                    Long userId = (Long) setup[0];
                    String batchId = (String) setup[1];
                    int targetNum = f.newCabinet(CabinetStatus.AVAILABLE);

                    CountDownLatch holding = new CountDownLatch(1);
                    CountDownLatch undoStarted = new CountDownLatch(1);
                    CompletableFuture<String> undoResult = new CompletableFuture<>();

                    f.tx.execute(
                            status -> {
                                User locked = f.users.findByIdWithLock(userId).orElseThrow();
                                holding.countDown();
                                CompletableFuture.runAsync(
                                        () -> {
                                            undoStarted.countDown();
                                            undoResult.complete(undoOutcome(undo, batchId));
                                        });
                                try {
                                    undoStarted.await();
                                    Thread.sleep(2_000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                Cabinet target =
                                        f.cabinets
                                                .findByVisibleNumWithLock(targetNum)
                                                .orElseThrow();
                                target.updateStatus(CabinetStatus.FULL);
                                f.lents.save(
                                        LentHistory.of(
                                                locked,
                                                target,
                                                LocalDateTime.now(),
                                                LocalDateTime.now().plusDays(31)));
                                return null;
                            });

                    String result = undoResult.get(30, TimeUnit.SECONDS);
                    assertThat(result)
                            .as("활성 대여 %s건", f.activeLents(userId))
                            .isEqualTo("[" + UndoConflictCode.USER_HAS_ACTIVE_LENT + "]");
                    assertThat(f.activeLents(userId)).isEqualTo(1);
                });
    }
}
