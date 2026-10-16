package com.gyeongsan.cabinet.domain.alarm.port.out;

import java.util.List;

public interface ReportRecipientPort {

    List<String> findRecipientIntraIds();
}
