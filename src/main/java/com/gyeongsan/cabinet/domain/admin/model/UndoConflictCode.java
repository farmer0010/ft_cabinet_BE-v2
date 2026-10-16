package com.gyeongsan.cabinet.domain.admin.model;

public enum UndoConflictCode {
    CABINET_CHANGED,
    LENT_CHANGED,
    CABINET_OCCUPIED,
    USER_HAS_ACTIVE_LENT,
    LENT_EXPIRED,
    RESERVED,
    USER_INACTIVE,
    ALREADY_UNDONE
}
