package com.gyeongsan.cabinet.domain.admin.port.in;

import com.gyeongsan.cabinet.adapter.in.web.admin.dto.UndoRequest;
import com.gyeongsan.cabinet.adapter.in.web.admin.dto.UndoResponse;
import com.gyeongsan.cabinet.domain.admin.model.AdminActor;

public interface AdminUndoUseCase {

    UndoResponse undoBulkStatusUpdate(String batchId, UndoRequest request, AdminActor actor);
}
