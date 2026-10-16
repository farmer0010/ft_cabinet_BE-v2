package com.gyeongsan.cabinet.domain.user.model;

import java.util.List;
import java.util.Optional;

public final class FtGradeResolver {

    public static final int CURSUS_42_ID = 21;
    public static final String TRANSCENDER = "Transcender";
    public static final String CADET = "Cadet";

    static final int MAX_GRADE_LENGTH = 32;

    private FtGradeResolver() {}

    public static FtGradeSnapshot resolve(Optional<List<FtCursusEntry>> entries) {
        if (entries == null || entries.isEmpty()) {
            return FtGradeSnapshot.unparsed();
        }

        String firstGrade = null;
        boolean found = false;
        for (FtCursusEntry entry : entries.get()) {
            if (entry == null || entry.cursusId() != CURSUS_42_ID) {
                continue;
            }
            if (TRANSCENDER.equals(entry.grade())) {
                return FtGradeSnapshot.of(TRANSCENDER);
            }
            if (!found) {
                firstGrade = entry.grade();
                found = true;
            }
        }

        if (firstGrade == null || firstGrade.isEmpty()) {
            return FtGradeSnapshot.of(null);
        }
        if (!isStorable(firstGrade)) {
            return FtGradeSnapshot.ofRejected();
        }
        return FtGradeSnapshot.of(firstGrade);
    }

    public static boolean isRecognized(String grade) {
        return grade == null || CADET.equals(grade) || TRANSCENDER.equals(grade);
    }

    private static boolean isStorable(String grade) {
        if (grade.length() > MAX_GRADE_LENGTH || grade.isBlank()) {
            return false;
        }
        return grade.chars().noneMatch(Character::isISOControl);
    }
}
