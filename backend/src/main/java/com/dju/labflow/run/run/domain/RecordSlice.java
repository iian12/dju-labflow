package com.dju.labflow.run.run.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** limit+1건으로 다음 페이지 존재 여부를 확인한 기록 묶음. 항상 시간/ID 오름차순이다. */
public record RecordSlice<T>(List<T> items, boolean hasMore) {
    public RecordSlice {
        items = List.copyOf(items);
    }

    public static <T> RecordSlice<T> from(List<T> fetched, RecordPageQuery query) {
        ArrayList<T> items =
                new ArrayList<>(fetched.subList(0, Math.min(query.limit(), fetched.size())));
        if (query.backwards()) Collections.reverse(items);
        return new RecordSlice<>(items, fetched.size() > query.limit());
    }
}
