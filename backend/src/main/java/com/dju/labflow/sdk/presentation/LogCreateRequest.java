package com.dju.labflow.sdk.presentation;

import java.time.Instant;
import java.util.Map;

public record LogCreateRequest(
        Integer epoch,
        Long step,
        Double elapsedTime,
        Map<String, String> metrics,
        Instant recordedAt) {}
