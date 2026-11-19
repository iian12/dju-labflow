package com.dju.labflow.run.run.application;

import java.util.List;

/** 페이지 데이터와 양방향 탐색 위치. nextCursor는 현재 조회 방향의 다음 위치이다. */
public record RunRecordPage<T>(
        List<T> items,
        int limit,
        boolean hasMore,
        String beforeCursor,
        String afterCursor,
        String nextCursor) {}
