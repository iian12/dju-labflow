package com.dju.labflow.run.run.domain;

import java.util.List;

/** 프로젝트별 실행 페이지와 전체 상태별 집계. */
public record RunPage(
        List<Run> items,
        int page,
        int size,
        long totalElements,
        long running,
        long completed,
        long failed,
        List<String> availableMetrics) {
    public RunPage {
        items = List.copyOf(items);
        availableMetrics = List.copyOf(availableMetrics);
    }

    public long projectTotal() {
        return running + completed + failed;
    }

    public long totalPages() {
        return totalElements / size + (totalElements % size == 0 ? 0 : 1);
    }
}
