package com.dju.labflow.run.run.domain;

import com.dju.labflow.run.metric.domain.RunMetric;
import java.util.List;

/** 실행 목록의 마지막 Step과 지표별 최신 기록. */
public record RunSummary(Long runId, Long lastStep, List<RunMetric> metrics) {}
