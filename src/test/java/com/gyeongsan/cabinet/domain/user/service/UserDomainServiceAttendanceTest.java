package com.gyeongsan.cabinet.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gyeongsan.cabinet.domain.coin.port.out.CoinHistoryRepositoryPort;
import com.gyeongsan.cabinet.domain.item.port.out.ItemHistoryRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.LentRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.ReservationPort;
import com.gyeongsan.cabinet.domain.user.model.Attendance;
import com.gyeongsan.cabinet.domain.user.model.LentTicketRewardPolicy;
import com.gyeongsan.cabinet.domain.user.model.User;
import com.gyeongsan.cabinet.domain.user.model.UserRole;
import com.gyeongsan.cabinet.domain.user.port.out.AttendanceRepositoryPort;
import com.gyeongsan.cabinet.domain.user.port.out.UserRepositoryPort;
import com.gyeongsan.cabinet.global.exception.ErrorCode;
import com.gyeongsan.cabinet.global.exception.ServiceException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class UserDomainServiceAttendanceTest {

    private static final Long USER_ID = 5L;

    private AttendanceRepositoryPort attendanceRepository;
    private CoinHistoryRepositoryPort coinHistoryRepository;
    private User user;
    private UserDomainService service;

    @BeforeEach
    void setUp() {
        UserRepositoryPort userRepository = mock(UserRepositoryPort.class);
        attendanceRepository = mock(AttendanceRepositoryPort.class);
        coinHistoryRepository = mock(CoinHistoryRepositoryPort.class);
        service =
                new UserDomainService(
                        userRepository,
                        mock(LentRepositoryPort.class),
                        mock(ItemHistoryRepositoryPort.class),
                        attendanceRepository,
                        coinHistoryRepository,
                        mock(ReservationPort.class),
                        new LentTicketRewardPolicy(4800, 900));
        user = User.of("intra05", "intra05@example.com", null, UserRole.USER);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName(
            "같은 날 두 번째 출석은 IllegalStateException(→500)이 아니라 ALREADY_ATTENDED(409)이고, 씨앗은 지급되지 않는다")
    void duplicateAttendanceIsABusinessError() {
        when(attendanceRepository.findTodayAttendance(any(), any(), any()))
                .thenReturn(Optional.of(new Attendance(user, LocalDate.now())));

        assertThatThrownBy(() -> service.doAttendance(USER_ID))
                .isInstanceOf(ServiceException.class)
                .satisfies(
                        e -> {
                            ServiceException se = (ServiceException) e;
                            assertThat(se.getErrorCode()).isEqualTo(ErrorCode.ALREADY_ATTENDED);
                            assertThat(se.getErrorCode().getStatus())
                                    .isEqualTo(HttpStatus.CONFLICT);
                        });

        assertThat(user.getCoin()).isZero();
        verify(attendanceRepository, never()).save(any());
        verify(coinHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("첫 출석은 정상 처리되어 100 씨앗이 지급된다 (기존 동작 유지)")
    void firstAttendanceStillWorks() {
        when(attendanceRepository.findTodayAttendance(any(), any(), any()))
                .thenReturn(Optional.empty());

        service.doAttendance(USER_ID);

        assertThat(user.getCoin()).isEqualTo(100L);
        verify(attendanceRepository).save(any(Attendance.class));
    }
}
