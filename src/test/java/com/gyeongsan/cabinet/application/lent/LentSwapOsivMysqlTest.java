package com.gyeongsan.cabinet.application.lent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongsan.cabinet.adapter.out.cache.redis.ReservationRedisAdapter;
import com.gyeongsan.cabinet.common.lock.DistributedLockAop;
import com.gyeongsan.cabinet.domain.admin.port.out.AdminActionLogPort;
import com.gyeongsan.cabinet.domain.admin.service.AdminCabinetService;
import com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus;
import com.gyeongsan.cabinet.domain.cabinet.port.out.CabinetRepositoryPort;
import com.gyeongsan.cabinet.domain.item.port.out.ItemHistoryRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.AiCheckPort;
import com.gyeongsan.cabinet.domain.lent.port.out.ImageUploadPort;
import com.gyeongsan.cabinet.domain.lent.port.out.LentRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.ReservationPort;
import com.gyeongsan.cabinet.domain.user.port.out.UserRepositoryPort;
import com.gyeongsan.cabinet.global.exception.ErrorCode;
import com.gyeongsan.cabinet.global.exception.ServiceException;
import com.gyeongsan.cabinet.support.MariaDbDriverMySqlContainer;
import com.gyeongsan.cabinet.support.RedisTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LentSwapOsivMysqlTest {

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
        AdminCabinetService adminCabinetService(
                CabinetRepositoryPort cabinets, LentRepositoryPort lents) {
            return new AdminCabinetService(cabinets, lents, mock(AdminActionLogPort.class));
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
                    withPauseAfterRead(cabinets),
                    lents,
                    itemHistories,
                    reservations,
                    aiCheckPort,
                    imageUploadPort,
                    transactionTemplate);
        }
    }

    private static CabinetRepositoryPort withPauseAfterRead(CabinetRepositoryPort target) {
        return (CabinetRepositoryPort)
                Proxy.newProxyInstance(
                        CabinetRepositoryPort.class.getClassLoader(),
                        new Class<?>[] {CabinetRepositoryPort.class},
                        (proxy, method, args) -> {
                            Object result;
                            try {
                                result = method.invoke(target, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                            Gate gate = GATE.get();
                            if (gate != null
                                    && (method.getName().equals("findByVisibleNum")
                                            || method.getName().equals("findIdByVisibleNum"))) {
                                GATE.remove();
                                gate.reached.countDown();
                                if (!gate.release.await(30, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("게이트가 풀리지 않았다");
                                }
                            }
                            return result;
                        });
    }

    @FunctionalInterface
    private interface Body {
        void run(LentConcurrencyMysqlTest.Fixture f, EntityManagerFactory emf) throws Exception;
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
                                            context.getBean(EntityManagerFactory.class));
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

    private static MultipartFile file() {
        return mock(MultipartFile.class);
    }

    private static String outcome(Runnable call) {
        try {
            call.run();
            return "OK";
        } catch (ServiceException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private static int activeLentsOnCabinet(LentConcurrencyMysqlTest.Fixture f, int visibleNum) {
        return f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM lent_history lh JOIN cabinet c ON lh.cabinet_id = c.id"
                        + " WHERE c.visible_num = ? AND lh.ended_at IS NULL",
                Integer.class,
                visibleNum);
    }

    private static CompletableFuture<String> requestThread(
            ExecutorService pool,
            EntityManagerFactory emf,
            boolean osiv,
            Gate gate,
            Runnable call) {
        return CompletableFuture.supplyAsync(
                () -> {
                    EntityManager em = null;
                    if (osiv) {
                        em = emf.createEntityManager();
                        TransactionSynchronizationManager.bindResource(
                                emf, new EntityManagerHolder(em));
                    }
                    GATE.set(gate);
                    try {
                        return outcome(call);
                    } finally {
                        GATE.remove();
                        if (osiv) {
                            TransactionSynchronizationManager.unbindResource(emf);
                            EntityManagerFactoryUtils.closeEntityManager(em);
                        }
                    }
                },
                pool);
    }

    private static Object[] swapperPausedWhileSomeoneTakesCabinet(
            LentConcurrencyMysqlTest.Fixture f,
            EntityManagerFactory emf,
            boolean osiv,
            boolean competitorUsesSwap)
            throws Exception {
        Long swapper = f.newUser(f.swapItem);
        int swapperOld = f.newCabinet(CabinetStatus.FULL);
        f.rent(swapper, swapperOld, LocalDateTime.now().plusDays(10));

        Long competitor = competitorUsesSwap ? f.newUser(f.swapItem) : f.newUser(f.lentItem);
        if (competitorUsesSwap) {
            int competitorOld = f.newCabinet(CabinetStatus.FULL);
            f.rent(competitor, competitorOld, LocalDateTime.now().plusDays(10));
        }
        int target = f.newCabinet(CabinetStatus.AVAILABLE);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Gate gate = new Gate();
            CompletableFuture<String> swapperResult =
                    requestThread(
                            pool,
                            emf,
                            osiv,
                            gate,
                            () -> f.service.useSwap(swapper, target, "1234", file(), false, null));
            if (!gate.reached.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("이사 요청이 사물함을 읽는 지점에 도달하지 못했다");
            }

            String competitorResult =
                    outcome(
                            () -> {
                                if (competitorUsesSwap) {
                                    f.service.useSwap(
                                            competitor, target, "1234", file(), false, null);
                                } else {
                                    f.service.startLent(competitor, target);
                                }
                            });
            assertThat(competitorResult).as("먼저 끝난 경쟁자 요청").isEqualTo("OK");
            assertThat(f.cabinetStatus(target)).isEqualTo("FULL");

            gate.release.countDown();
            String result;
            try {
                result = swapperResult.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e);
            }
            return new Object[] {result, activeLentsOnCabinet(f, target), f.activeLents(swapper)};
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.0", "mysql:8.4"})
    @DisplayName("[OSIV 켜짐] 이사가 사물함을 읽은 뒤 다른 사람이 같은 사물함으로 이사하면, 늦은 이사는 거부되고 활성 대여는 1건이다")
    void swapVsSwap_osivOn(String image) throws Exception {
        run(
                image,
                (f, emf) -> {
                    Object[] r = swapperPausedWhileSomeoneTakesCabinet(f, emf, true, true);
                    assertThat(r[0])
                            .as("늦은 이사의 결과(활성 대여 %s건)", r[1])
                            .isEqualTo(ErrorCode.INVALID_CABINET_STATUS.name());
                    assertThat(r[1]).as("대상 사물함의 활성 대여 수").isEqualTo(1);
                });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.0", "mysql:8.4"})
    @DisplayName("[OSIV 켜짐] 이사가 사물함을 읽은 뒤 다른 사람이 같은 사물함을 대여하면, 늦은 이사는 거부되고 활성 대여는 1건이다")
    void swapVsStartLent_osivOn(String image) throws Exception {
        run(
                image,
                (f, emf) -> {
                    Object[] r = swapperPausedWhileSomeoneTakesCabinet(f, emf, true, false);
                    assertThat(r[0])
                            .as("늦은 이사의 결과(활성 대여 %s건)", r[1])
                            .isEqualTo(ErrorCode.INVALID_CABINET_STATUS.name());
                    assertThat(r[1]).as("대상 사물함의 활성 대여 수").isEqualTo(1);
                });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.0", "mysql:8.4"})
    @DisplayName("[대조군: OSIV 꺼짐] 같은 상황에서 늦은 이사는 거부되고 활성 대여는 1건이다")
    void swapVsSwap_osivOff(String image) throws Exception {
        run(
                image,
                (f, emf) -> {
                    Object[] r = swapperPausedWhileSomeoneTakesCabinet(f, emf, false, true);
                    assertThat(r[0]).isEqualTo(ErrorCode.INVALID_CABINET_STATUS.name());
                    assertThat(r[1]).isEqualTo(1);
                });
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.0", "mysql:8.4"})
    @DisplayName("[OSIV 켜짐, 훅 없음] 두 사람이 같은 사물함으로 동시에 이사해도 활성 대여는 1건이다 (자연 경합 20회)")
    void swapVsSwap_naturalRace_osivOn(String image) throws Exception {
        run(
                image,
                (f, emf) ->
                        assertThat(naturalRaceDoubleBookings(f, emf, true))
                                .as("20회 중 한 사물함에 활성 대여가 2건 생긴 횟수")
                                .isZero());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mysql:8.4"})
    @DisplayName("[대조군: OSIV 꺼짐, 훅 없음] 같은 자연 경합 20회에서 활성 대여는 항상 1건이다")
    void swapVsSwap_naturalRace_osivOff(String image) throws Exception {
        run(
                image,
                (f, emf) ->
                        assertThat(naturalRaceDoubleBookings(f, emf, false))
                                .as("20회 중 한 사물함에 활성 대여가 2건 생긴 횟수")
                                .isZero());
    }

    private static int naturalRaceDoubleBookings(
            LentConcurrencyMysqlTest.Fixture f, EntityManagerFactory emf, boolean osiv)
            throws Exception {
        int doubleBooked = 0;
        for (int n = 0; n < 20; n++) {
            Long a = f.newUser(f.swapItem);
            Long b = f.newUser(f.swapItem);
            f.rent(a, f.newCabinet(CabinetStatus.FULL), LocalDateTime.now().plusDays(10));
            f.rent(b, f.newCabinet(CabinetStatus.FULL), LocalDateTime.now().plusDays(10));
            int target = f.newCabinet(CabinetStatus.AVAILABLE);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                CompletableFuture<String> ra =
                        CompletableFuture.supplyAsync(
                                () ->
                                        request(
                                                emf,
                                                osiv,
                                                barrier,
                                                () ->
                                                        f.service.useSwap(
                                                                a, target, "1234", file(), false,
                                                                null)),
                                pool);
                CompletableFuture<String> rb =
                        CompletableFuture.supplyAsync(
                                () ->
                                        request(
                                                emf,
                                                osiv,
                                                barrier,
                                                () ->
                                                        f.service.useSwap(
                                                                b, target, "1234", file(), false,
                                                                null)),
                                pool);
                ra.get(30, TimeUnit.SECONDS);
                rb.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            if (activeLentsOnCabinet(f, target) > 1) {
                doubleBooked++;
            }
        }
        return doubleBooked;
    }

    private static String request(
            EntityManagerFactory emf, boolean osiv, CyclicBarrier barrier, Runnable call) {
        EntityManager em = null;
        if (osiv) {
            em = emf.createEntityManager();
            TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(em));
        }
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return outcome(call);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (osiv) {
                TransactionSynchronizationManager.unbindResource(emf);
                EntityManagerFactoryUtils.closeEntityManager(em);
            }
        }
    }
}
