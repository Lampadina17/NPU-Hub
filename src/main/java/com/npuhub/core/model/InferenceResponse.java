package com.npuhub.core.model;

public record InferenceResponse(
        String requestId,
        String modelName,
        String text,
        int promptTokens,
        int completionTokens,
        double tokensPerSecond,
        double timeToFirstTokenMs,
        long totalExecutionTimeMs,
        BackendType backendUsed
) {
}
