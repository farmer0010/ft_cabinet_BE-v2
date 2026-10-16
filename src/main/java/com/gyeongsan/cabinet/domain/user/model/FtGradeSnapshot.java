package com.gyeongsan.cabinet.domain.user.model;

public record FtGradeSnapshot(boolean parsed, String grade, boolean rejected) {

    private static final FtGradeSnapshot UNPARSED = new FtGradeSnapshot(false, null, false);

    public static FtGradeSnapshot unparsed() {
        return UNPARSED;
    }

    public static FtGradeSnapshot of(String grade) {
        return new FtGradeSnapshot(true, grade, false);
    }

    public static FtGradeSnapshot ofRejected() {
        return new FtGradeSnapshot(true, null, true);
    }

    public boolean shouldStore() {
        return parsed && !rejected;
    }
}
