package com.dju.labflow.run.run.application;

import com.dju.labflow.run.log.domain.RunLog;
import com.dju.labflow.run.metric.domain.RunMetric;
import com.dju.labflow.run.parameter.domain.RunParameter;
import com.dju.labflow.run.systemmetric.domain.RunSystemMetric;
import com.dju.labflow.run.text.domain.RunText;
import java.util.List;

/** 접근 확인을 마친 한 실행의 기록 묶음. 각 시계열은 최대 limit개이다. */
public record RunRecords(
        List<RunParameter> parameters,
        List<RunMetric> metrics,
        List<RunLog> logs,
        List<RunText> texts,
        List<RunSystemMetric> systemMetrics,
        int limit) {}
