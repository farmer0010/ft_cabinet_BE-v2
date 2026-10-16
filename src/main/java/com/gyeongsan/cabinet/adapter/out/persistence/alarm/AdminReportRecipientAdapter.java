package com.gyeongsan.cabinet.adapter.out.persistence.alarm;

import com.gyeongsan.cabinet.domain.alarm.port.out.ReportRecipientPort;
import com.gyeongsan.cabinet.domain.user.model.User;
import com.gyeongsan.cabinet.domain.user.model.UserRole;
import com.gyeongsan.cabinet.domain.user.port.out.UserRepositoryPort;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminReportRecipientAdapter implements ReportRecipientPort {

    private static final Set<UserRole> RECIPIENT_ROLES = Set.of(UserRole.ADMIN, UserRole.MASTER);

    private final UserRepositoryPort userRepository;

    @Override
    public List<String> findRecipientIntraIds() {
        return userRepository.findAllActiveByRoleIn(RECIPIENT_ROLES).stream()
                .map(User::getName)
                .distinct()
                .toList();
    }
}
