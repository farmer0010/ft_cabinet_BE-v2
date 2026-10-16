package com.gyeongsan.cabinet.domain.user.port.in;

import com.gyeongsan.cabinet.adapter.in.web.user.dto.MyProfileResponseDto;
import com.gyeongsan.cabinet.domain.item.model.Item;
import com.gyeongsan.cabinet.domain.user.model.FtGradeSnapshot;
import java.time.LocalDate;
import java.util.List;

public interface UserUseCase {

    MyProfileResponseDto getMyProfile(Long userId);

    void doAttendance(Long userId);

    List<LocalDate> getMyAttendanceDates(Long userId);

    void processLogtimeTransaction(
            Long userId, Item lentTicketItem, int totalMinutes, boolean isPayDay);

    boolean needsGradeRefresh(Long userId, int totalMinutes);

    void updateFtGrade(Long userId, FtGradeSnapshot snapshot);
}
