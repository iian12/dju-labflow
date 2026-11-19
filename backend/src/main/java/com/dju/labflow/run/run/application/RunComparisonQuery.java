package com.dju.labflow.run.run.application;

import com.dju.labflow.global.validation.InputValidation;
import com.dju.labflow.run.run.domain.RunId;
import java.util.HashSet;
import java.util.List;

/** 선택 순서를 보존하며, 생략한 기준 Run은 첫 선택으로 정한다. */
public record RunComparisonQuery(
        List<RunId> runIds,
        String metricKey,
        RunId baselineRunId,
        int maxPoints,
        boolean differencesOnly) {
    public RunComparisonQuery {
        if (runIds == null
                || runIds.size() < 2
                || runIds.size() > 4
                || runIds.stream().anyMatch(id -> id == null)
                || new HashSet<>(runIds).size() != runIds.size())
            throw new IllegalArgumentException("Select 2 to 4 distinct runs");
        runIds = List.copyOf(runIds);
        metricKey = InputValidation.text(metricKey, "Metric key", 100);
        if (baselineRunId == null) baselineRunId = runIds.getFirst();
        if (!runIds.contains(baselineRunId))
            throw new IllegalArgumentException("Baseline must be one of the selected runs");
        if (maxPoints < 2 || maxPoints > 1000)
            throw new IllegalArgumentException("Max points must be between 2 and 1000");
    }
}
