package com.dju.labflow.project.project.domain;

import java.util.List;

/** 영속성 프레임워크에 종속되지 않는 개인 프로젝트 목록 결과. */
public record ProjectPage(List<Project> items, int page, int size, long totalElements) {
    public ProjectPage {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return totalElements / size + (totalElements % size == 0 ? 0 : 1);
    }
}
