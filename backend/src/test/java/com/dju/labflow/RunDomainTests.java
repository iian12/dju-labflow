package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.log.domain.*;
import com.dju.labflow.run.metric.domain.*;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.run.run.exception.RunStateConflictException;
import com.dju.labflow.run.systemmetric.domain.*;
import com.dju.labflow.run.text.domain.*;
import com.dju.labflow.user.domain.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 실행 불변식과 수치/텍스트 기록의 검증을 영속성 없이 확인한다. */
class RunDomainTests {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final RunId RUN_ID = RunId.of(1L);

    @Test
    void completionRetriesPreserveOriginalEndTimeAndRejectFailure() {
        Run run = run(Map.of());
        run.complete(NOW.plusSeconds(10));
        run.complete(NOW.plusSeconds(20));
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getEndedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThatThrownBy(() -> run.fail("error", NOW.plusSeconds(30)))
                .isInstanceOf(RunStateConflictException.class);
    }

    @Test
    void failureRetriesPreserveReasonAndRejectDifferentTerminalResults() {
        Run run = run(null);
        run.fail("out of memory", NOW.plusSeconds(10));
        run.fail("out of memory", NOW.plusSeconds(20));
        assertThat(run.getEndedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThatThrownBy(() -> run.fail("different", NOW.plusSeconds(30)))
                .isInstanceOf(RunStateConflictException.class);
        assertThatThrownBy(() -> run.complete(NOW.plusSeconds(30)))
                .isInstanceOf(RunStateConflictException.class);
        assertThat(run.getError()).isEqualTo("out of memory");
    }

    @Test
    void invalidTerminationDoesNotPartiallyMutateRun() {
        Run run = run(null);
        assertThatThrownBy(() -> run.complete(NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run.fail(" ", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getEndedAt()).isNull();
        assertThat(run.getError()).isNull();
    }

    @Test
    void restoreRejectsInconsistentTerminalState() {
        assertThatThrownBy(
                        () ->
                                Run.restore(
                                        RUN_ID,
                                        ProjectId.of(1L),
                                        UserId.of(1L),
                                        "test",
                                        Map.of(),
                                        RunStatus.RUNNING,
                                        NOW,
                                        NOW,
                                        null,
                                        0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                Run.restore(
                                        RUN_ID,
                                        ProjectId.of(1L),
                                        UserId.of(1L),
                                        "test",
                                        Map.of(),
                                        RunStatus.FAILED,
                                        NOW,
                                        NOW,
                                        null,
                                        0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataAndGpuListsAreDefensiveImmutableCopies() {
        var metadata = new HashMap<>(Map.of("framework", "pytorch"));
        Run run = run(metadata);
        metadata.put("framework", "changed");
        assertThat(run.getMetadata()).containsEntry("framework", "pytorch");
        assertThatThrownBy(() -> run.getMetadata().put("a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
        var gpus =
                new ArrayList<>(
                        List.of(new GpuMetric(1, 10.0, 1L, 10L), new GpuMetric(0, 20.0, 2L, 10L)));
        var snapshot =
                RunSystemMetric.create(
                        RunSystemMetricId.of(1L), RUN_ID, 10.0, 1L, 10L, gpus, 0.0, NOW);
        gpus.clear();
        assertThat(snapshot.getGpus()).extracting(GpuMetric::index).containsExactly(0, 1);
        assertThatThrownBy(() -> snapshot.getGpus().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metricsAllowFiniteNegativeValuesButRejectNonFiniteValuesAndNegativeCoordinates() {
        assertThat(
                        RunMetric.create(
                                        RunMetricId.of(1L), RUN_ID, "reward", -1.0, 0, 0L, 0.0, NOW)
                                .getValue())
                .isEqualTo(-1.0);
        for (double value :
                new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(
                            () ->
                                    RunMetric.create(
                                            RunMetricId.of(1L),
                                            RUN_ID,
                                            "loss",
                                            value,
                                            0,
                                            0L,
                                            0.0,
                                            NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                RunMetric.create(
                                        RunMetricId.of(1L), RUN_ID, "loss", 0.1, 0, -1L, 0.0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RunMetric.create(
                                        RunMetricId.of(1L),
                                        RUN_ID,
                                        "loss",
                                        0.1,
                                        0,
                                        0L,
                                        Double.NaN,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logAndTextPreserveWhitespaceAndSupportEmptyOutput() {
        String output = "  생성 결과\n두 번째 줄  ";
        assertThat(
                        RunLog.create(
                                        RunLogId.of(1L),
                                        RUN_ID,
                                        output,
                                        RunLogLevel.INFO,
                                        RunLogSource.STDOUT,
                                        NOW)
                                .getMessage())
                .isEqualTo(output);
        assertThat(RunText.create(RunTextId.of(1L), RUN_ID, "prediction", "", null, NOW).getText())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                RunLog.create(
                                        RunLogId.of(1L),
                                        RUN_ID,
                                        "x".repeat(16001),
                                        RunLogLevel.INFO,
                                        RunLogSource.USER,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RunText.create(
                                        RunTextId.of(1L),
                                        RUN_ID,
                                        "prediction",
                                        "x".repeat(65537),
                                        null,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemMetricsRejectInvalidPercentagesMemoryAndDuplicateGpuIndices() {
        assertThatThrownBy(() -> new GpuMetric(0, 100.1, 1L, 2L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GpuMetric(0, Double.NaN, 1L, 2L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GpuMetric(0, 10.0, 3L, 2L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RunSystemMetric.create(
                                        RunSystemMetricId.of(1L),
                                        RUN_ID,
                                        null,
                                        null,
                                        null,
                                        List.of(
                                                new GpuMetric(0, null, null, null),
                                                new GpuMetric(0, null, null, null)),
                                        null,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                RunSystemMetric.create(
                                        RunSystemMetricId.of(1L),
                                        RUN_ID,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Run run(Map<String, String> metadata) {
        return Run.create(RUN_ID, ProjectId.of(1L), UserId.of(1L), "test", metadata, NOW);
    }
}
