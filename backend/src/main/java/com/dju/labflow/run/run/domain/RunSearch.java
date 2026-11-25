package com.dju.labflow.run.run.domain;

import java.util.List;

/** Server-side filters; metric ordering always uses the latest record by time and ID. */
public record RunSearch(
        String name, List<RunStatus> statuses, String sort, String metricKey, List<Long> runIds) {
    public RunSearch {
        name = name == null ? "" : name.trim();
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        sort = sort == null ? "recent" : sort;
        metricKey = metricKey == null ? "" : metricKey;
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
        if (runIds.size() > 100
                || runIds.stream().anyMatch(id -> id <= 0)
                || runIds.stream().distinct().count() != runIds.size())
            throw new IllegalArgumentException("Invalid run IDs");
        if (name.length() > 100
                || metricKey.length() > 100
                || !List.of("recent", "high", "low", "duration").contains(sort))
            throw new IllegalArgumentException("Invalid run search");
        if ((sort.equals("high") || sort.equals("low")) && metricKey.isBlank())
            throw new IllegalArgumentException("Metric ordering requires a metric key");
    }

    public RunSearch(String name, List<RunStatus> statuses, String sort, String metricKey) {
        this(name, statuses, sort, metricKey, List.of());
    }

    public static RunSearch all() {
        return new RunSearch("", List.of(), "recent", "");
    }
}
