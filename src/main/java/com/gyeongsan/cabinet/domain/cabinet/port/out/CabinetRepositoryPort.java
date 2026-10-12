package com.gyeongsan.cabinet.domain.cabinet.port.out;

import com.gyeongsan.cabinet.adapter.out.persistence.cabinet.CabinetRepository.FloorStatProjection;
import com.gyeongsan.cabinet.domain.cabinet.model.Cabinet;
import com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus;
import java.util.List;
import java.util.Optional;

public interface CabinetRepositoryPort {

    Optional<Cabinet> findById(Long id);

    Optional<Cabinet> findByIdWithLock(Long id);

    Optional<Cabinet> findByVisibleNum(Integer visibleNum);

    /**
     * 번호로 사물함 ID 만 읽는다(엔티티를 영속성 컨텍스트에 올리지 않는다). 락 전에 엔티티를 읽으면 OSIV 가 켜진 웹 요청에서 낡은 상태가 락 뒤에도 남는다.
     */
    Optional<Long> findIdByVisibleNum(Integer visibleNum);

    Optional<Cabinet> findByVisibleNumWithLock(Integer visibleNum);

    List<Cabinet> findAll();

    List<Cabinet> findAllById(List<Long> ids);

    List<Cabinet> findAllByFloor(Integer floor);

    List<Cabinet> findAllByStatus(CabinetStatus status);

    long count();

    long countByStatus(CabinetStatus status);

    List<FloorStatProjection> findFloorStatistics();
}
