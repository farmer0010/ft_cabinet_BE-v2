package com.gyeongsan.cabinet.domain.admin.service;

import com.gyeongsan.cabinet.adapter.in.web.admin.dto.*;
import com.gyeongsan.cabinet.domain.admin.model.AdminActionLog;
import com.gyeongsan.cabinet.domain.admin.model.AdminActionLogItem;
import com.gyeongsan.cabinet.domain.admin.model.AdminActionTargetType;
import com.gyeongsan.cabinet.domain.admin.model.AdminActionType;
import com.gyeongsan.cabinet.domain.admin.model.AdminActor;
import com.gyeongsan.cabinet.domain.admin.port.in.AdminCabinetUseCase;
import com.gyeongsan.cabinet.domain.admin.port.out.AdminActionLogPort;
import com.gyeongsan.cabinet.domain.cabinet.model.Cabinet;
import com.gyeongsan.cabinet.domain.cabinet.model.CabinetStatus;
import com.gyeongsan.cabinet.domain.cabinet.model.LentType;
import com.gyeongsan.cabinet.domain.cabinet.port.out.CabinetRepositoryPort;
import com.gyeongsan.cabinet.domain.lent.model.LentHistory;
import com.gyeongsan.cabinet.domain.lent.port.out.LentRepositoryPort;
import com.gyeongsan.cabinet.global.exception.BulkStatusUpdateRejectedException;
import com.gyeongsan.cabinet.global.exception.ErrorCode;
import com.gyeongsan.cabinet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Log4j2
public class AdminCabinetService implements AdminCabinetUseCase {

    private static final int STATUS_NOTE_MAX_LENGTH = 64;

    private static final Set<CabinetStatus> LENT_ENDING_STATUSES =
            EnumSet.of(
                    CabinetStatus.AVAILABLE,
                    CabinetStatus.BROKEN,
                    CabinetStatus.DISABLED,
                    CabinetStatus.PENDING);

    private final CabinetRepositoryPort cabinetRepository;
    private final LentRepositoryPort lentRepository;
    private final AdminActionLogPort adminActionLogPort;

    @Override
    @Transactional(readOnly = true)
    public Page<CabinetHistoryResponse> getCabinetHistory(Integer visibleNum, Pageable pageable) {
        return lentRepository
                .findHistoryByCabinet(visibleNum, pageable)
                .map(CabinetHistoryResponse::from);
    }

    @Override
    public void updateCabinetStatus(Integer visibleNum, CabinetStatusRequest request) {
        Cabinet cabinet =
                cabinetRepository
                        .findByVisibleNumWithLock(visibleNum)
                        .orElseThrow(() -> new ServiceException(ErrorCode.CABINET_NOT_FOUND));

        cabinet.updateStatus(request.status(), request.lentType(), request.statusNote());
    }

    @Override
    public void forceReturn(Integer visibleNum) {
        Cabinet cabinet =
                cabinetRepository
                        .findByVisibleNumWithLock(visibleNum)
                        .orElseThrow(() -> new ServiceException(ErrorCode.CABINET_NOT_FOUND));

        LentHistory activeLent =
                lentRepository.findByCabinetIdAndEndedAtIsNull(cabinet.getId()).orElse(null);

        if (activeLent != null) {
            activeLent.endLent(LocalDateTime.now(), "관리자 강제 반납");
        }

        cabinet.updateStatus(CabinetStatus.PENDING, cabinet.getLentType(), "강제 반납: 물품 수거 및 청소 필요");
    }

    @Override
    @Transactional(readOnly = true)
    public List<CabinetPendingResponseDto> getPendingCabinets() {
        List<LentHistory> pendingLentHistories =
                lentRepository.findAllLatestLentForPendingCabinets();

        return pendingLentHistories.stream()
                .map(
                        lh ->
                                new CabinetPendingResponseDto(
                                        lh.getCabinet().getVisibleNum(),
                                        lh.getCabinet().getStatusNote(),
                                        lh.getCabinet().getLentType(),
                                        lh.getPhotoUrl(),
                                        lh.getUser().getName()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReturnPhotoResponseDto> getReturnPhotos(Pageable pageable) {
        return lentRepository
                .findAllReturnedWithPhoto(pageable)
                .map(
                        lh ->
                                new ReturnPhotoResponseDto(
                                        lh.getId(),
                                        lh.getCabinet().getVisibleNum(),
                                        lh.getUser().getName(),
                                        lh.getPhotoUrl(),
                                        lh.getEndedAt(),
                                        lh.getReturnMemo()));
    }

    @Override
    public void approveManualReturn(Integer visibleNum) {
        Cabinet cabinet =
                cabinetRepository
                        .findByVisibleNumWithLock(visibleNum)
                        .orElseThrow(() -> new ServiceException(ErrorCode.CABINET_NOT_FOUND));

        if (cabinet.getStatus() != CabinetStatus.PENDING) {
            throw new ServiceException(ErrorCode.INVALID_CABINET_STATUS);
        }

        cabinet.updateStatus(CabinetStatus.AVAILABLE, cabinet.getLentType(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public CabinetDetailResponse getCabinetDetail(Integer visibleNum) {
        Cabinet cabinet =
                cabinetRepository
                        .findByVisibleNum(visibleNum)
                        .orElseThrow(() -> new ServiceException(ErrorCode.CABINET_NOT_FOUND));

        LentHistory activeLent =
                lentRepository.findByCabinetIdAndEndedAtIsNull(cabinet.getId()).orElse(null);

        return CabinetDetailResponse.of(cabinet, activeLent);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BrokenCabinetResponse> getBrokenCabinets() {
        return cabinetRepository.findAllByStatus(CabinetStatus.BROKEN).stream()
                .map(BrokenCabinetResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    public BulkStatusUpdateResponse bulkUpdateCabinetStatus(
            BulkStatusUpdateRequest request, AdminActor actor) {
        String batchId = UUID.randomUUID().toString();
        log.info(
                "[BULK:{}] 일괄 변경 요청 - 관리자: {}(id={}), 대상: {}건, status: {}, lentType: {}, endActiveLents: {}, 사유: {}, 작업 사유: {}",
                batchId,
                actor.name(),
                actor.id(),
                request.cabinetIds() == null ? 0 : request.cabinetIds().size(),
                request.status(),
                request.lentType(),
                request.endActiveLents(),
                sanitizeForLog(request.statusNote()),
                sanitizeForLog(request.reason()));

        validateBulkRequest(request);

        List<Long> sortedIds = request.cabinetIds().stream().distinct().sorted().toList();
        List<Cabinet> cabinets = new ArrayList<>();
        List<Long> missingIds = new ArrayList<>();
        for (Long id : sortedIds) {
            cabinetRepository
                    .findByIdWithLock(id)
                    .ifPresentOrElse(cabinets::add, () -> missingIds.add(id));
        }

        Map<Long, List<LentHistory>> activeLentsByCabinetId =
                loadActiveLents(cabinets, request.status());

        List<BulkStatusRejection.OccupiedCabinet> occupiedCabinets =
                request.endActiveLents()
                        ? List.of()
                        : findOccupiedCabinets(cabinets, activeLentsByCabinetId);
        if (!missingIds.isEmpty() || !occupiedCabinets.isEmpty()) {
            log.warn(
                    "[BULK:{}] 거부 - 존재하지 않는 ID: {}, 대여 중인 사물함: {}",
                    batchId,
                    missingIds,
                    occupiedCabinets.stream().map(o -> o.visibleNum()).distinct().toList());
            throw new BulkStatusUpdateRejectedException(
                    new BulkStatusRejection(missingIds, occupiedCabinets));
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        List<BulkStatusUpdateResponse.UpdatedCabinet> updatedCabinets = new ArrayList<>();
        List<BulkStatusUpdateResponse.EndedLent> endedLents = new ArrayList<>();
        List<AdminActionLogItem> logItems = new ArrayList<>();

        for (Cabinet cabinet : cabinets) {
            if (request.endActiveLents()) {
                for (LentHistory lent :
                        activeLentsByCabinetId.getOrDefault(cabinet.getId(), List.of())) {
                    LocalDateTime previousEndedAt = lent.getEndedAt();
                    lent.endLent(now);
                    logItems.add(lentLogItem(cabinet, lent, previousEndedAt));
                    endedLents.add(
                            new BulkStatusUpdateResponse.EndedLent(
                                    lent.getId(),
                                    cabinet.getId(),
                                    cabinet.getVisibleNum(),
                                    lent.getUser().getId(),
                                    lent.getUser().getName()));
                    log.warn(
                            "[BULK:{}] 대여 종료 - 대여 ID: {}, 사용자: {}(id={}), 사물함: {}",
                            batchId,
                            lent.getId(),
                            lent.getUser().getName(),
                            lent.getUser().getId(),
                            cabinet.getVisibleNum());
                }
            }

            CabinetStatus previousStatus = cabinet.getStatus();
            LentType previousLentType = cabinet.getLentType();
            String previousNote = cabinet.getStatusNote();

            CabinetStatus newStatus = request.status() != null ? request.status() : previousStatus;
            LentType newLentType =
                    request.lentType() != null ? request.lentType() : previousLentType;
            String newNote =
                    (request.statusNote() != null && !request.statusNote().isBlank())
                            ? request.statusNote()
                            : previousNote;
            cabinet.updateStatus(newStatus, newLentType, newNote);

            logItems.add(
                    new AdminActionLogItem(
                            AdminActionTargetType.CABINET,
                            cabinet.getId(),
                            String.valueOf(cabinet.getVisibleNum()),
                            cabinetSnapshot(previousStatus, previousLentType, previousNote),
                            cabinetSnapshot(newStatus, newLentType, newNote)));
            updatedCabinets.add(
                    new BulkStatusUpdateResponse.UpdatedCabinet(
                            cabinet.getId(), cabinet.getVisibleNum(), previousStatus, newStatus));
            log.info(
                    "[BULK:{}] 사물함 {}(id={}) 변경 - status: {} -> {}, lentType: {} -> {}, 사유: '{}' -> '{}'",
                    batchId,
                    cabinet.getVisibleNum(),
                    cabinet.getId(),
                    previousStatus,
                    newStatus,
                    previousLentType,
                    newLentType,
                    sanitizeForLog(previousNote),
                    sanitizeForLog(newNote));
        }

        adminActionLogPort.save(
                new AdminActionLog(
                        batchId,
                        AdminActionType.CABINET_BULK_STATUS_UPDATE,
                        actor,
                        normalizedReason(request),
                        requestSnapshot(request),
                        now,
                        logItems));

        log.info(
                "[BULK:{}] 완료 - 관리자: {}, 변경 {}개, 종료된 대여 {}건",
                batchId,
                actor.name(),
                updatedCabinets.size(),
                endedLents.size());
        return new BulkStatusUpdateResponse(batchId, updatedCabinets, endedLents);
    }

    private void validateBulkRequest(BulkStatusUpdateRequest request) {
        List<Long> cabinetIds = request.cabinetIds();
        CabinetStatus targetStatus = request.status();
        String statusNote = request.statusNote();

        if (cabinetIds == null || cabinetIds.isEmpty()) {
            throw new IllegalArgumentException("사물함 ID 목록이 비어있습니다.");
        }

        if (cabinetIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("사물함 ID 목록에 null 이 포함되어 있습니다.");
        }

        if (targetStatus == CabinetStatus.FULL || targetStatus == CabinetStatus.OVERDUE) {
            throw new IllegalArgumentException(
                    "FULL/OVERDUE 는 대여 상태에 따라 자동으로 정해지는 값이라 일괄 변경으로 지정할 수 없습니다.");
        }

        if (targetStatus == CabinetStatus.BROKEN && (statusNote == null || statusNote.isBlank())) {
            throw new IllegalArgumentException("고장 상태로 변경 시 사유(statusNote)는 필수입니다.");
        }

        if (statusNote != null && statusNote.length() > STATUS_NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "사유(statusNote)는 " + STATUS_NOTE_MAX_LENGTH + "자 이하여야 합니다.");
        }

        if (request.endActiveLents() && !LENT_ENDING_STATUSES.contains(targetStatus)) {
            throw new IllegalArgumentException(
                    "endActiveLents=true 는 status 를 AVAILABLE/BROKEN/DISABLED/PENDING 중 하나로 함께 지정해야 합니다.");
        }

        String reason = normalizedReason(request);
        if (request.endActiveLents() && reason == null) {
            throw new IllegalArgumentException(
                    "대여를 종료(endActiveLents=true)하려면 작업 사유(reason)가 필요합니다.");
        }
        if (reason != null && reason.length() > AdminActionLog.REASON_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "작업 사유(reason)는 " + AdminActionLog.REASON_MAX_LENGTH + "자 이하여야 합니다.");
        }
    }

    private static String normalizedReason(BulkStatusUpdateRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            return null;
        }
        return request.reason().strip();
    }

    private static Map<String, Object> requestSnapshot(BulkStatusUpdateRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("cabinetIds", request.cabinetIds());
        snapshot.put("status", request.status() == null ? null : request.status().name());
        snapshot.put("lentType", request.lentType() == null ? null : request.lentType().name());
        snapshot.put("statusNote", request.statusNote());
        snapshot.put("endActiveLents", request.endActiveLents());
        snapshot.put("reason", normalizedReason(request));
        return snapshot;
    }

    private static Map<String, Object> cabinetSnapshot(
            CabinetStatus status, LentType lentType, String statusNote) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status == null ? null : status.name());
        snapshot.put("lentType", lentType == null ? null : lentType.name());
        snapshot.put("statusNote", statusNote);
        return snapshot;
    }

    private static AdminActionLogItem lentLogItem(
            Cabinet cabinet, LentHistory lent, LocalDateTime previousEndedAt) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("endedAt", previousEndedAt == null ? null : previousEndedAt.toString());
        before.put("returnMemo", lent.getReturnMemo());
        before.put("cabinetId", cabinet.getId());
        before.put("userId", lent.getUser().getId());

        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("endedAt", lent.getEndedAt() == null ? null : lent.getEndedAt().toString());

        return new AdminActionLogItem(
                AdminActionTargetType.LENT_HISTORY,
                lent.getId(),
                String.valueOf(cabinet.getVisibleNum()),
                before,
                after);
    }

    private Map<Long, List<LentHistory>> loadActiveLents(
            List<Cabinet> cabinets, CabinetStatus targetStatus) {
        if (targetStatus == null || cabinets.isEmpty()) {
            return Map.of();
        }
        List<Long> cabinetIds = cabinets.stream().map(Cabinet::getId).toList();
        return lentRepository.findAllActiveLentByCabinetIds(cabinetIds).stream()
                .collect(Collectors.groupingBy(lent -> lent.getCabinet().getId()));
    }

    private List<BulkStatusRejection.OccupiedCabinet> findOccupiedCabinets(
            List<Cabinet> cabinets, Map<Long, List<LentHistory>> activeLentsByCabinetId) {
        return cabinets.stream()
                .flatMap(
                        cabinet ->
                                activeLentsByCabinetId
                                        .getOrDefault(cabinet.getId(), List.of())
                                        .stream()
                                        .map(
                                                lent ->
                                                        new BulkStatusRejection.OccupiedCabinet(
                                                                cabinet.getId(),
                                                                cabinet.getVisibleNum(),
                                                                lent.getUser().getId(),
                                                                lent.getUser().getName())))
                .toList();
    }

    private static String sanitizeForLog(String value) {
        return value == null ? null : value.replaceAll("\\p{Cntrl}", " ");
    }
}
