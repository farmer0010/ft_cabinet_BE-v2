package com.gyeongsan.cabinet.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulerPoolConfigTest {

    private static final Path MAIN_YML = Path.of("src/main/resources/application.yml");

    static class Probe {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final AtomicInteger healthyRuns = new AtomicInteger();

        @Scheduled(fixedDelay = 50, initialDelay = 0)
        void stuckJob() throws InterruptedException {
            blockerStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
        }

        @Scheduled(fixedDelay = 50, initialDelay = 100)
        void healthyJob() {
            healthyRuns.incrementAndGet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class ProbeConfig {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(ProbeConfig.class)
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
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
    }

    @Test
    @DisplayName("운영 application.yml 의 스케줄러 풀 크기는 기본 1 이 아니라 5 이다")
    void defaultPoolSizeIsMoreThanOne() {
        runner().run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            ThreadPoolTaskScheduler scheduler =
                                    context.getBean(ThreadPoolTaskScheduler.class);
                            assertThat(scheduler.getPoolSize())
                                    .as("Boot 기본값 1 이면 한 작업의 정체가 전체를 멈춘다")
                                    .isGreaterThan(1);
                            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                                    .isEqualTo(5);
                            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
                        });
    }

    @Test
    @DisplayName("SCHEDULER_POOL_SIZE 환경변수로 풀 크기를 바꿀 수 있다")
    void poolSizeIsConfigurable() {
        runner().withPropertyValues("SCHEDULER_POOL_SIZE=8")
                .run(
                        context ->
                                assertThat(
                                                context.getBean(ThreadPoolTaskScheduler.class)
                                                        .getScheduledThreadPoolExecutor()
                                                        .getCorePoolSize())
                                        .isEqualTo(8));
    }

    @Test
    @DisplayName("한 정기 작업이 멈춰 있어도 다른 정기 작업은 계속 실행된다")
    void stuckJobDoesNotStarveOthers() throws Exception {
        runner().run(
                        context -> {
                            Probe probe = context.getBean(Probe.class);
                            assertThat(probe.blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();

                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            while (probe.healthyRuns.get() < 3 && System.nanoTime() < deadline) {
                                Thread.sleep(20);
                            }
                            probe.release.countDown();

                            assertThat(probe.healthyRuns.get())
                                    .as("멈춘 작업이 있어도 다른 작업은 반복 실행되어야 한다")
                                    .isGreaterThanOrEqualTo(3);
                        });
    }

    @Test
    @DisplayName("대조군: 풀 크기를 1 로 두면 멈춘 작업 하나가 다른 작업을 굶긴다 (수정 전 동작)")
    void singleThreadPoolStarvesOtherJobs() {
        runner().withPropertyValues("SCHEDULER_POOL_SIZE=1")
                .run(
                        context -> {
                            Probe probe = context.getBean(Probe.class);
                            assertThat(probe.blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();

                            Thread.sleep(800);
                            int runsWhileStuck = probe.healthyRuns.get();
                            probe.release.countDown();

                            assertThat(runsWhileStuck)
                                    .as("풀이 1개면 멈춘 작업이 스레드를 쥐고 있어 다른 작업이 돌지 못한다")
                                    .isZero();
                        });
    }
}
