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

/**
 * 감사 H-11 재현: 이사(useSwap)는 새 사물함 엔티티를 <b>트랜잭션 밖에서</b> 먼저 읽는다({@code swapWithLocks} 의 {@code
 * findByVisibleNum}). 웹 요청에서는 Open-Session-In-View(OSIV, Boot 기본값 켜짐)가 요청 전체에 걸쳐 영속성 컨텍스트 하나를 열어
 * 두므로 그 엔티티가 그대로 남고, 이후 트랜잭션이 같은 컨텍스트를 이어 쓴다. 락을 잡은 뒤 {@code findByIdWithLock} 이 같은 행을 다시 읽어도
 * Hibernate 는 이미 관리 중인 인스턴스의 상태를 갱신하지 않는다면, 다른 사람이 그 사이 사물함을 FULL 로 바꿨는데도 이사가 'AVAILABLE' 로 보고 진행해
 * 한 사물함에 활성 대여가 2건 생긴다.
 *
 * <p>실제 MySQL 8.0/8.4(운영 MariaDB 와 같은 드라이버) + 실제 Redis + 실제 서비스 코드로 돌린다. OSIV 는 웹 계층이 없으므로 {@code
 * OpenEntityManagerInViewInterceptor} 가 하는 일(요청 스레드에 EntityManager 를 바인딩)을 그대로 흉내 낸다. "읽은 뒤 락 전"
 * 구간은 읽기 직후에 멈추는 테스트 전용 훅으로 결정적으로 만든다. Docker 가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class LentSwapOsivMysqlTest {

    /** 훅이 멈출 스레드에만 설정한다. */
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

    /** 번호로 사물함을 읽은 직후, 이 스레드에 게이트가 있으면 풀어 줄 때까지 멈춘다(읽은 뒤 락 전 구간). */
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

    /** 서비스 호출 결과를 "OK" 또는 ErrorCode 이름(그 외 예외는 클래스명)으로 바꾼다. */
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

    /**
     * 요청 스레드에서 서비스를 실행한다. osiv 가 true 이면 {@code OpenEntityManagerInViewInterceptor} 와 같이 이 스레드에
     * EntityManager 를 요청 범위로 바인딩한다. 읽은 직후 멈추는 게이트를 걸어, 게이트에 도달했다는 신호를 {@code gate.reached} 로 준다.
     */
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

    /**
     * 이사하려는 사람(swapper)이 새 사물함을 읽고 멈춘 사이 다른 사람이 같은 사물함을 차지(커밋)한다. 멈춘 요청을 풀어 준 결과와 그 사물함의 활성 대여 수를
     * 돌려준다.
     */
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

            // 멈춘 사이 다른 사람이 같은 사물함을 차지한다(별도 요청이므로 OSIV 컨텍스트가 비어 있다 = 깨끗한 읽기).
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

    /** 같은 사물함으로 두 사람이 동시에 이사하는 경합을 20번 돌려, 활성 대여가 2건이 된 횟수를 돌려준다. */
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

    /** 배리어로 동시에 출발하는 요청 하나. osiv 면 요청 범위 EntityManager 를 바인딩한다. */
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
