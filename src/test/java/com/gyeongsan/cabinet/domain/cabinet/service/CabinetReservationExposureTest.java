package com.gyeongsan.cabinet.domain.cabinet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gyeongsan.cabinet.adapter.in.web.cabinet.dto.CabinetDetailResponseDto;
import com.gyeongsan.cabinet.adapter.in.web.cabinet.dto.CabinetListResponseDto;
import com.gyeongsan.cabinet.auth.domain.UserPrincipal;
import com.gyeongsan.cabinet.domain.cabinet.model.Cabinet;
import com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus;
import com.gyeongsan.cabinet.domain.cabinet.model.LentType;
import com.gyeongsan.cabinet.domain.cabinet.port.out.CabinetRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.LentRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.ReservationPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class CabinetReservationExposureTest {

    private static final long ME = 7L;
    private static final long OTHER = 8L;

    private CabinetRepositoryPort cabinetRepository;
    private ReservationPort reservationPort;
    private CabinetDomainService service;
    private UserPrincipal me;

    @BeforeEach
    void setUp() {
        cabinetRepository = mock(CabinetRepositoryPort.class);
        reservationPort = mock(ReservationPort.class);
        service =
                new CabinetDomainService(
                        cabinetRepository, mock(LentRepositoryPort.class), reservationPort);
        me = mock(UserPrincipal.class);
        when(me.getUserId()).thenReturn(ME);
    }

    private Cabinet newCabinet(long id, int visibleNum, CabinetStatus status) {
        Cabinet cabinet = Cabinet.of(visibleNum, status, LentType.PRIVATE, 1, null, 1, "A", 1, 1);
        ReflectionTestUtils.setField(cabinet, "id", id);
        return cabinet;
    }

    private CabinetListResponseDto byNum(List<CabinetListResponseDto> list, int visibleNum) {
        return list.stream()
                .filter(dto -> dto.getVisibleNum() == visibleNum)
                .findFirst()
                .orElseThrow();
    }

    @Nested
    @DisplayName("목록")
    class ListCase {

        @BeforeEach
        void cabinets() {
            when(cabinetRepository.findAllByFloor(1))
                    .thenReturn(
                            List.of(
                                    newCabinet(1, 101, CabinetStatus.AVAILABLE),
                                    newCabinet(2, 102, CabinetStatus.AVAILABLE),
                                    newCabinet(3, 103, CabinetStatus.AVAILABLE),
                                    newCabinet(4, 104, CabinetStatus.FULL),
                                    newCabinet(5, 105, CabinetStatus.BROKEN)));
        }

        @Test
        @DisplayName("예약이 없으면 둘 다 false 다")
        void noReservation() {
            when(reservationPort.getReservedUserIds(any())).thenReturn(Map.of());

            List<CabinetListResponseDto> list = service.getCabinetList(1, me);

            assertThat(list)
                    .allSatisfy(
                            dto -> {
                                assertThat(dto.getIsReserved()).isFalse();
                                assertThat(dto.getIsReservedByMe()).isFalse();
                            });
        }

        @Test
        @DisplayName(
                "내가 예약한 사물함은 isReserved, isReservedByMe 가 모두 true, 남이 예약한 것은 isReserved 만 true 다")
        void mineAndOthers() {
            when(reservationPort.getReservedUserIds(any())).thenReturn(Map.of(101, ME, 102, OTHER));

            List<CabinetListResponseDto> list = service.getCabinetList(1, me);

            assertThat(byNum(list, 101).getIsReserved()).isTrue();
            assertThat(byNum(list, 101).getIsReservedByMe()).isTrue();
            assertThat(byNum(list, 102).getIsReserved()).isTrue();
            assertThat(byNum(list, 102).getIsReservedByMe()).isFalse();
            assertThat(byNum(list, 103).getIsReserved()).isFalse();
            assertThat(byNum(list, 103).getIsReservedByMe()).isFalse();
        }

        @Test
        @DisplayName("비로그인이면 남의 예약도 isReserved 만 true 이고 isReservedByMe 는 항상 false 다")
        void anonymous() {
            when(reservationPort.getReservedUserIds(any())).thenReturn(Map.of(101, ME, 102, OTHER));

            List<CabinetListResponseDto> list = service.getCabinetList(1, null);

            assertThat(byNum(list, 101).getIsReserved()).isTrue();
            assertThat(list).allSatisfy(dto -> assertThat(dto.getIsReservedByMe()).isFalse());
        }

        @Test
        @DisplayName("Redis 조회가 실패해도 목록은 내려가고 예약은 없는 것으로 본다")
        void redisFailure() {
            when(reservationPort.getReservedUserIds(any()))
                    .thenThrow(new IllegalStateException("redis down"));

            List<CabinetListResponseDto> list = service.getCabinetList(1, me);

            assertThat(list).hasSize(5);
            assertThat(list)
                    .allSatisfy(
                            dto -> {
                                assertThat(dto.getIsReserved()).isFalse();
                                assertThat(dto.getIsReservedByMe()).isFalse();
                            });
        }

        @Test
        @DisplayName("대여 가능한 사물함만 조회하고, 비AVAILABLE 사물함은 예약이 있어도 false 다")
        @SuppressWarnings("unchecked")
        void onlyAvailableCabinetsAreQueried() {
            Map<Integer, Long> redis = Map.of(101, OTHER, 104, OTHER, 105, OTHER);
            // 실제 어댑터처럼 요청받은 번호의 예약만 돌려준다.
            when(reservationPort.getReservedUserIds(any()))
                    .thenAnswer(
                            call -> {
                                Collection<Integer> asked = call.getArgument(0);
                                return redis.entrySet().stream()
                                        .filter(e -> asked.contains(e.getKey()))
                                        .collect(
                                                Collectors.toMap(
                                                        Map.Entry::getKey, Map.Entry::getValue));
                            });

            List<CabinetListResponseDto> list = service.getCabinetList(1, me);

            ArgumentCaptor<Collection<Integer>> asked = ArgumentCaptor.forClass(Collection.class);
            verify(reservationPort).getReservedUserIds(asked.capture());
            assertThat(asked.getValue()).containsExactlyInAnyOrder(101, 102, 103);
            assertThat(byNum(list, 104).getIsReserved()).isFalse();
            assertThat(byNum(list, 105).getIsReserved()).isFalse();
        }

        @Test
        @DisplayName("Redis 는 사물함 수와 상관없이 한 번만 호출한다 (N+1 없음)")
        void singleRedisCall() {
            when(reservationPort.getReservedUserIds(any())).thenReturn(Map.of());

            service.getCabinetList(1, me);

            verify(reservationPort, times(1)).getReservedUserIds(any());
            verify(reservationPort, never()).getReservedUserId(anyInt());
            verify(reservationPort, never()).getCabinetReservationTtlSeconds(anyInt());
        }

        @Test
        @DisplayName("대여 가능한 사물함이 하나도 없으면 Redis 를 부르지 않는다")
        void noAvailableCabinetSkipsRedis() {
            when(cabinetRepository.findAllByFloor(2))
                    .thenReturn(List.of(newCabinet(4, 104, CabinetStatus.FULL)));

            service.getCabinetList(2, me);

            verifyNoInteractions(reservationPort);
        }
    }

    @Nested
    @DisplayName("상세")
    class DetailCase {

        private static final long CABINET_ID = 1L;

        @BeforeEach
        void cabinet() {
            when(cabinetRepository.findById(CABINET_ID))
                    .thenReturn(Optional.of(newCabinet(CABINET_ID, 101, CabinetStatus.AVAILABLE)));
        }

        @Test
        @DisplayName("예약이 없으면 false, false, null 이다")
        void noReservation() {
            when(reservationPort.getReservedUserId(101)).thenReturn(Optional.empty());

            CabinetDetailResponseDto detail = service.getCabinetDetail(CABINET_ID, me);

            assertThat(detail.getIsReserved()).isFalse();
            assertThat(detail.getIsReservedByMe()).isFalse();
            assertThat(detail.getReservationRemainingSeconds()).isNull();
            verify(reservationPort, never()).getCabinetReservationTtlSeconds(anyInt());
        }

        @Test
        @DisplayName("내가 예약했으면 isReservedByMe 가 true 이고 남은 시간이 담긴다")
        void mine() {
            when(reservationPort.getReservedUserId(101)).thenReturn(Optional.of(ME));
            when(reservationPort.getCabinetReservationTtlSeconds(101))
                    .thenReturn(Optional.of(840L));

            CabinetDetailResponseDto detail = service.getCabinetDetail(CABINET_ID, me);

            assertThat(detail.getIsReserved()).isTrue();
            assertThat(detail.getIsReservedByMe()).isTrue();
            assertThat(detail.getReservationRemainingSeconds()).isEqualTo(840L);
        }

        @Test
        @DisplayName("남이 예약했으면 isReserved 만 true 이고 남은 시간이 담긴다")
        void others() {
            when(reservationPort.getReservedUserId(101)).thenReturn(Optional.of(OTHER));
            when(reservationPort.getCabinetReservationTtlSeconds(101))
                    .thenReturn(Optional.of(300L));

            CabinetDetailResponseDto detail = service.getCabinetDetail(CABINET_ID, me);

            assertThat(detail.getIsReserved()).isTrue();
            assertThat(detail.getIsReservedByMe()).isFalse();
            assertThat(detail.getReservationRemainingSeconds()).isEqualTo(300L);
        }

        @Test
        @DisplayName("비로그인이면 isReservedByMe 는 false 이고 isReserved 와 남은 시간은 그대로 나간다")
        void anonymous() {
            when(reservationPort.getReservedUserId(101)).thenReturn(Optional.of(ME));
            when(reservationPort.getCabinetReservationTtlSeconds(101))
                    .thenReturn(Optional.of(300L));

            CabinetDetailResponseDto detail = service.getCabinetDetail(CABINET_ID, null);

            assertThat(detail.getIsReserved()).isTrue();
            assertThat(detail.getIsReservedByMe()).isFalse();
            assertThat(detail.getReservationRemainingSeconds()).isEqualTo(300L);
        }

        @Test
        @DisplayName("남은 시간을 알 수 없으면(만료 직전 등) 예약은 true 로 두고 시간만 null 이다")
        void ttlUnknown() {
            when(reservationPort.getReservedUserId(101)).thenReturn(Optional.of(OTHER));
            when(reservationPort.getCabinetReservationTtlSeconds(101)).thenReturn(Optional.empty());

            CabinetDetailResponseDto detail = service.getCabinetDetail(CABINET_ID, me);

            assertThat(detail.getIsReserved()).isTrue();
            assertThat(detail.getReservationRemainingSeconds()).isNull();
        }

        @Test
        @DisplayName("예약자 조회에서 Redis 예외가 나도 상세는 내려가고 false, false, null 이다")
        void redisFailureOnOwnerLookup() {
            when(reservationPort.getReservedUserId(101)).thenThrow(new IllegalStateException("x"));

            CabinetDetailResponseDto detail = service.getCabinetDetail(CABINET_ID, me);

            assertThat(detail.getCabinetId()).isEqualTo(CABINET_ID);
            assertThat(detail.getIsReserved()).isFalse();
            assertThat(detail.getIsReservedByMe()).isFalse();
            assertThat(detail.getReservationRemainingSeconds()).isNull();
        }

        @Test
        @DisplayName("남은 시간 조회에서 Redis 예외가 나도 일부만 채워지지 않고 모두 false, false, null 이다")
        void redisFailureOnTtlLookup() {
            when(reservationPort.getReservedUserId(101)).thenReturn(Optional.of(ME));
            when(reservationPort.getCabinetReservationTtlSeconds(101))
                    .thenThrow(new IllegalStateException("x"));

            CabinetDetailResponseDto detail = service.getCabinetDetail(CABINET_ID, me);

            assertThat(detail.getIsReserved()).isFalse();
            assertThat(detail.getIsReservedByMe()).isFalse();
            assertThat(detail.getReservationRemainingSeconds()).isNull();
        }
    }
}
