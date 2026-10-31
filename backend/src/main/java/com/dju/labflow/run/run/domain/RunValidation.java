package com.dju.labflow.run.run.domain;

import com.dju.labflow.global.validation.InputValidation;

/** 실행 기록의 공통 수치 및 조회 범위 제약. */
public final class RunValidation {
    private RunValidation() {}

    public static Long nonNegative(Long value, String field) {
        if (value != null && value < 0)
            throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    public static Integer nonNegative(Integer value, String field) {
        if (value != null && value < 0)
            throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    public static Double finite(Double value, String field) {
        InputValidation.required(value, field);
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
        return value;
    }

    public static Double elapsed(Double value) {
        if (value != null && finite(value, "Elapsed time") < 0)
            throw new IllegalArgumentException("Elapsed time must not be negative");
        return value;
    }

    public static Double percentage(Double value, String field) {
        if (value != null && (finite(value, field) < 0 || value > 100))
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        return value;
    }

    public static void memory(Long used, Long total) {
        nonNegative(used, "Used memory");
        nonNegative(total, "Total memory");
        if (used != null && total != null && used > total)
            throw new IllegalArgumentException("Used memory must not exceed total memory");
    }

    public static int limit(int limit) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Limit must be between 1 and 1000");
        return limit;
    }
}
