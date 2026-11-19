package com.dju.labflow.run.run.application;

import com.dju.labflow.run.run.domain.Run;
import com.dju.labflow.run.run.domain.RunConnectionStatus;
import com.dju.labflow.run.run.domain.RunStatus;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 조회 시 서버 시각으로 연결 상태만 계산한다. 통신 장애로 Run을 종료하지 않는다. */
@Component
public class RunHeartbeatPolicy {
    private final Clock clock;
    private final Duration timeout;

    public RunHeartbeatPolicy(
            Clock clock, @Value("${labflow.runs.heartbeat.timeout:2m}") Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Heartbeat timeout must be positive");
        this.clock = clock;
        this.timeout = timeout;
    }

    public RunConnectionStatus status(Run run) {
        if (run.getStatus() != RunStatus.RUNNING) return RunConnectionStatus.INACTIVE;
        var heartbeat = run.getLastHeartbeatAt();
        var lastSeen = heartbeat == null ? run.getStartedAt() : heartbeat;
        if (!lastSeen.isAfter(clock.instant().minus(timeout)))
            return RunConnectionStatus.DISCONNECTED;
        return heartbeat == null ? RunConnectionStatus.WAITING : RunConnectionStatus.CONNECTED;
    }
}
