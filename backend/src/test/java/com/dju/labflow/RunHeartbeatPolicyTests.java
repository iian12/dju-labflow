package com.dju.labflow;

import static org.assertj.core.api.Assertions.*;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.run.run.application.RunHeartbeatPolicy;
import com.dju.labflow.run.run.domain.*;
import com.dju.labflow.user.domain.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunHeartbeatPolicyTests {
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RunHeartbeatPolicy policy = new RunHeartbeatPolicy(clock, Duration.ofMinutes(2));

    @Test
    void waitsForFirstHeartbeatAndDisconnectsAtTheExactBoundary() {
        assertThat(policy.status(run(NOW.minusSeconds(119))))
                .isEqualTo(RunConnectionStatus.WAITING);
        var expired = run(NOW.minusSeconds(120));
        assertThat(policy.status(expired)).isEqualTo(RunConnectionStatus.DISCONNECTED);
        assertThat(expired.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(expired.getEndedAt()).isNull();
        assertThat(expired.getError()).isNull();
    }

    @Test
    void usesLastHeartbeatAndAutomaticallyRecoversWithoutEndingRun() {
        var run = run(NOW.minusSeconds(500));
        run.heartbeat(NOW.minusSeconds(121));
        assertThat(policy.status(run)).isEqualTo(RunConnectionStatus.DISCONNECTED);
        run.heartbeat(NOW.minusSeconds(119));
        assertThat(policy.status(run)).isEqualTo(RunConnectionStatus.CONNECTED);
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void evenLongDisconnectionNeverChangesRunState() {
        var run = run(NOW.minus(Duration.ofDays(30)));
        assertThat(policy.status(run)).isEqualTo(RunConnectionStatus.DISCONNECTED);
        assertThat(policy.status(run)).isEqualTo(RunConnectionStatus.DISCONNECTED);
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void terminalRunsAreInactiveRegardlessOfOldHeartbeat() {
        var completed = run(NOW.minusSeconds(500));
        completed.complete(NOW.minusSeconds(200));
        assertThat(policy.status(completed)).isEqualTo(RunConnectionStatus.INACTIVE);
        var failed = run(NOW.minusSeconds(500));
        failed.fail("training error", NOW.minusSeconds(200));
        assertThat(policy.status(failed)).isEqualTo(RunConnectionStatus.INACTIVE);
    }

    @Test
    void timeoutIsConfigurableAndMustBePositive() {
        var custom = new RunHeartbeatPolicy(clock, Duration.ofMinutes(5));
        assertThat(custom.status(run(NOW.minusSeconds(120))))
                .isEqualTo(RunConnectionStatus.WAITING);
        assertThatThrownBy(() -> new RunHeartbeatPolicy(clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunHeartbeatPolicy(clock, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Run run(Instant start) {
        return Run.create(RunId.of(1L), ProjectId.of(1L), UserId.of(1L), "test", Map.of(), start);
    }
}
