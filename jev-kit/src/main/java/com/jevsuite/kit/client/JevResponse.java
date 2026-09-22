package com.jevsuite.kit.client;

import java.util.Map;

/** 一次 systemone 调用的完整结果。 */
public record JevResponse(
        Map<String, Answer> answers,
        long inputTokens,
        long latencyMs,
        boolean retried
) {}
