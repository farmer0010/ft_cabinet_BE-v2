package com.gyeongsan.cabinet.adapter.in.web.cabinet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongsan.cabinet.adapter.in.web.cabinet.dto.CabinetDetailResponseDto;
import com.gyeongsan.cabinet.adapter.in.web.cabinet.dto.CabinetListResponseDto;
import com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CabinetReservationJsonTest {

    private void withMapper(java.util.function.Consumer<ObjectMapper> body) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> body.accept(context.getBean(ObjectMapper.class)));
    }

    @Test
    @DisplayName("목록 JSON 에 isReserved, isReservedByMe 키가 boolean 으로 나가고 접두어 없는 키는 생기지 않는다")
    void listKeys() {
        withMapper(
                mapper -> {
                    JsonNode json =
                            mapper.valueToTree(
                                    CabinetListResponseDto.builder()
                                            .cabinetId(1L)
                                            .visibleNum(101)
                                            .status(CabinetStatus.AVAILABLE)
                                            .isReserved(true)
                                            .isReservedByMe(false)
                                            .build());

                    assertThat(json.get("isReserved").isBoolean()).isTrue();
                    assertThat(json.get("isReserved").asBoolean()).isTrue();
                    assertThat(json.get("isReservedByMe").isBoolean()).isTrue();
                    assertThat(json.get("isReservedByMe").asBoolean()).isFalse();
                    assertThat(json.has("reserved")).isFalse();
                    assertThat(json.has("reservedByMe")).isFalse();
                });
    }

    @Test
    @DisplayName(
            "상세 JSON 에 isReserved, isReservedByMe, reservationRemainingSeconds 키가 나간다 (기존 isReservedByMe 키 이름 유지)")
    void detailKeys() {
        withMapper(
                mapper -> {
                    JsonNode json =
                            mapper.valueToTree(
                                    CabinetDetailResponseDto.builder()
                                            .cabinetId(1L)
                                            .isReserved(true)
                                            .isReservedByMe(true)
                                            .reservationRemainingSeconds(840L)
                                            .build());

                    assertThat(json.get("isReserved").asBoolean()).isTrue();
                    assertThat(json.get("isReservedByMe").asBoolean()).isTrue();
                    assertThat(json.get("reservationRemainingSeconds").asLong()).isEqualTo(840L);
                    assertThat(json.has("reserved")).isFalse();
                    assertThat(json.has("reservedByMe")).isFalse();
                });
    }

    @Test
    @DisplayName("예약이 없으면 남은 시간은 null 로 나간다")
    void noReservationRemainingIsNull() {
        withMapper(
                mapper -> {
                    JsonNode json =
                            mapper.valueToTree(
                                    CabinetDetailResponseDto.builder()
                                            .isReserved(false)
                                            .isReservedByMe(false)
                                            .build());

                    assertThat(json.get("reservationRemainingSeconds").isNull()).isTrue();
                });
    }

    @Test
    @DisplayName("예약자를 알 수 있는 필드는 응답 구조에 없다 (필드를 늘리면 이 테스트를 고쳐야 한다)")
    void noReserverIdentityFields() {
        withMapper(
                mapper -> {
                    JsonNode list = mapper.valueToTree(CabinetListResponseDto.builder().build());
                    JsonNode detail =
                            mapper.valueToTree(CabinetDetailResponseDto.builder().build());

                    assertThat(list.fieldNames())
                            .toIterable()
                            .containsExactlyInAnyOrder(
                                    "cabinetId",
                                    "visibleNum",
                                    "floor",
                                    "section",
                                    "lentType",
                                    "status",
                                    "statusNote",
                                    "lentUserName",
                                    "lentStartedAt",
                                    "lentExpiredAt",
                                    "daysRemaining",
                                    "isReserved",
                                    "isReservedByMe");
                    assertThat(detail.fieldNames())
                            .toIterable()
                            .containsExactlyInAnyOrder(
                                    "cabinetId",
                                    "visibleNum",
                                    "floor",
                                    "section",
                                    "status",
                                    "statusNote",
                                    "lentUserName",
                                    "lentStartedAt",
                                    "lentExpiredAt",
                                    "daysRemaining",
                                    "previousUserName",
                                    "previousEndedAt",
                                    "isReserved",
                                    "isReservedByMe",
                                    "reservationRemainingSeconds");
                });
    }
}
