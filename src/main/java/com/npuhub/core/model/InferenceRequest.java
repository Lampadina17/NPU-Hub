package com.npuhub.core.model;

import java.util.List;

public record InferenceRequest(
        String requestId,
        String modelName,
        String prompt,
        List<String> messages,
        double temperature,
        double topP,
        int maxTokens,
        int topK,
        double minP,
        long seed,
        int repeatLastN,
        double repeatPenalty,
        double frequencyPenalty,
        double presencePenalty,
        boolean stream
) {}
