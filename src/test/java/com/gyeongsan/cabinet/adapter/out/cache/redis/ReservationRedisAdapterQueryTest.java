package com.gyeongsan.cabinet.adapter.out.cache.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 사물함 예약 조회의 값 변환을 Redis 없이 확인한다. 실제 Redis 에서의 동작은 {@link ReservationRedisAdapterTest} 가 본다. */
class ReservationRedisAdapterQueryTest {

    private StringRedisTemplate template;
    private ValueOperations<String, String> values;
    private ReservationRedisAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        template = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        adapter = new ReservationRedisAdapter(template);
    }

    @Test
    @DisplayName("MGET 한 번으로 읽고, 값이 있는 사물함만 번호 -> 예약자 맵으로 만든다")
    void multiGetMapsOnlyExistingReservations() {
        when(values.multiGet(
                        List.of(
                                "cabinet:reservation:101",
                                "cabinet:reservation:102",
                                "cabinet:reservation:103")))
                .thenReturn(Arrays.asList("7", null, "9"));

        Map<Integer, Long> reserved = adapter.getReservedUserIds(List.of(101, 102, 103));

        assertThat(reserved).containsOnly(Map.entry(101, 7L), Map.entry(103, 9L));
        verify(values, times(1)).multiGet(any());
        verify(values, never()).get(anyString());
    }

    @Test
    @DisplayName("모두 예약이 없으면 빈 맵이다")
    void noReservations() {
        when(values.multiGet(any())).thenReturn(Arrays.asList(null, null));

        assertThat(adapter.getReservedUserIds(List.of(101, 102))).isEmpty();
    }

    @Test
    @DisplayName("Redis 가 null 을 돌려줘도(파이프라인/트랜잭션 안에서) 빈 맵이다")
    void nullResult() {
        when(values.multiGet(any())).thenReturn(null);

        assertThat(adapter.getReservedUserIds(List.of(101))).isEmpty();
    }

    @Test
    @DisplayName("입력이 비어 있으면 Redis 를 부르지 않는다")
    void emptyInputSkipsRedis() {
        assertThat(adapter.getReservedUserIds(List.of())).isEmpty();
        assertThat(adapter.getReservedUserIds(null)).isEmpty();

        verifyNoInteractions(template, values);
    }

    @Test
    @DisplayName("TTL 은 0 이상이면 초 단위 그대로, 키 없음(-2)·만료 없음(-1)·null 은 비어 있다")
    void ttlConversion() {
        when(template.getExpire("cabinet:reservation:101", TimeUnit.SECONDS)).thenReturn(840L);
        when(template.getExpire("cabinet:reservation:102", TimeUnit.SECONDS)).thenReturn(0L);
        when(template.getExpire("cabinet:reservation:103", TimeUnit.SECONDS)).thenReturn(-1L);
        when(template.getExpire("cabinet:reservation:104", TimeUnit.SECONDS)).thenReturn(-2L);
        when(template.getExpire("cabinet:reservation:105", TimeUnit.SECONDS)).thenReturn(null);

        assertThat(adapter.getCabinetReservationTtlSeconds(101)).contains(840L);
        assertThat(adapter.getCabinetReservationTtlSeconds(102)).contains(0L);
        assertThat(adapter.getCabinetReservationTtlSeconds(103)).isEmpty();
        assertThat(adapter.getCabinetReservationTtlSeconds(104)).isEmpty();
        assertThat(adapter.getCabinetReservationTtlSeconds(105)).isEmpty();
    }
}
