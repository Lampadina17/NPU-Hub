package com.npuhub.core.model;

public record TokenChunk(
        String requestId,
        String token,
        boolean done,
        double currentTokensPerSecond
) {
}
