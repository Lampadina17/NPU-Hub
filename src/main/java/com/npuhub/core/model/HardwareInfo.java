package com.npuhub.core.model;

public record HardwareInfo(
        BackendType type,
        String deviceName,
        String devicePath,
        boolean available,
        int computeCores,
        String driverVersion,
        String statusDetails
) {}
