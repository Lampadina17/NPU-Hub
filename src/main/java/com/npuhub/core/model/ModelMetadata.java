package com.npuhub.core.model;

public record ModelMetadata(
        String id,
        String name,
        String path,
        String architecture,
        String quantization,
        Long parameterCount,
        Integer contextWindow,
        BackendType compatibleBackend,
        boolean loaded,
        boolean downloaded,
        String downloadStatus,
        Double downloadProgress
) {}
