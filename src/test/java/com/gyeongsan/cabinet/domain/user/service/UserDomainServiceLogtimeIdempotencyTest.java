package com.gyeongsan.cabinet.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gyeongsan.cabinet.domain.coin.port.out.CoinHistoryRepositoryPort;
import com.gyeongsan.cabinet.domain.item.model.Item;
import com.gyeongsan.cabinet.domain.item.model.ItemHistory;
import com.gyeongsan.cabinet.domain.item.model.ItemType;
import com.gyeongsan.cabinet.domain.item.port.out.ItemHistoryRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.LentRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.port.out.ReservationPort;
import com.gyeongsan.cabinet.domain.user.model.LentTicketRewardPolicy;
import com.gyeongsan.cabinet.domain.user.model.User;
import com.gyeongsan.cabinet.domain.user.model.UserRole;
import com.gyeongsan.cabinet.domain.user.port.out.AttendanceRepositoryPort;
import com.gyeongsan.cabinet.domain.user.port.out.UserRepositoryPort;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 감사 H-7 후속: 방치된 메시지를 다시 처리하면(적어도 한 번 처리) 같은 로그타임 메시지가 두 번 처리될 수 있다. 그때 보상이 두 번 나가지 않는지, 어떤 경우에 중복이
 * 생기는지를 고정한다.
 */
class UserDomainServiceLogtimeIdempotencyTest {

    private static final Long USER_ID = 9L;

    private final AtomicInteger unusedTickets = new AtomicInteger();
    private final AtomicInteger granted = new AtomicInteger();
    private UserDomainService service;
    private Item lentTicket;
    private User user;

    @BeforeEach
    void setUp() {
        UserRepositoryPort userRepository = mock(UserRepositoryPort.class);
        ItemHistoryRepositoryPort itemHistoryRepository = mock(ItemHistoryRepositoryPort.class);
        service =
                new UserDomainService(
                        userRepository,
                        mock(LentRepositoryPort.class),
                        itemHistoryRepository,
                        mock(AttendanceRepositoryPort.class),
                        mock(CoinHistoryRepositoryPort.class),
                        mock(ReservationPort.class),
                        new LentTicketRewardPolicy(4800, 900));
        lentTicket = new Item("대여권", ItemType.LENT, 0L, "대여권");
        user = User.of("test-user", "test@example.com", UserRole.USER, false);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.updateFtGrade("Cadet");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(itemHistoryRepository.countByUserIdAndItemTypeAndUsedAtIsNull(USER_ID, ItemType.LENT))
                .thenAnswer(i -> unusedTickets.get());
        when(itemHistoryRepository.save(any(ItemHistory.class)))
                .thenAnswer(
                        i -> {
                            unusedTickets.incrementAndGet();
                            granted.incrementAndGet();
                            return i.getArgument(0);
                        });
    }

    @Test
    @DisplayName("일반 로그타임 메시지(지급일 아님)를 두 번 처리해도 결과는 같다 (누적 시간은 '설정'이다)")
    void duplicateRegularMessageIsIdempotent() {
        service.processLogtimeTransaction(USER_ID, lentTicket, 3000, false);
        service.processLogtimeTransaction(USER_ID, lentTicket, 3000, false);

        assertThat(user.getMonthlyLogtime()).isEqualTo(3000);
        assertThat(granted).hasValue(0);
    }

    @Test
    @DisplayName("지급일 메시지를 두 번 처리해도, 첫 번째에 받은 대여권을 아직 안 썼다면 대여권은 한 장만 나간다")
    void duplicatePayDayMessageGrantsOnceWhileTicketUnused() {
        service.processLogtimeTransaction(USER_ID, lentTicket, 5000, true);
        service.processLogtimeTransaction(USER_ID, lentTicket, 5000, true);

        assertThat(granted).hasValue(1);
        assertThat(user.getMonthlyLogtime()).isZero();
    }

    @Test
    @DisplayName("알려진 한계: 첫 처리 뒤 대여권을 쓰고 나서 같은 지급일 메시지가 재처리되면 한 장 더 나간다")
    void duplicatePayDayMessageAfterTicketUsedGrantsAgain() {
        service.processLogtimeTransaction(USER_ID, lentTicket, 5000, true);
        unusedTickets.set(0); // 사용자가 그 사이 대여권을 썼다.
        service.processLogtimeTransaction(USER_ID, lentTicket, 5000, true);

        assertThat(granted).as("월별 지급 기록이 없어 막지 못한다 (CLAUDE.md 트센 혜택의 알려진 한계 ①)").hasValue(2);
    }
}
