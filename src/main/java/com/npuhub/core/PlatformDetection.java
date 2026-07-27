package com.npuhub.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PlatformDetection {
    private PlatformDetection() {
    }

    public static boolean isRadxaBoard() {
        String override = System.getenv("NPU_HUB_BOARD");
        String model = override != null && !override.isBlank() ? override : readDeviceTreeModel();
        String normalized = model.toLowerCase();
        return normalized.contains("radxa") || normalized.contains("qualcomm");
    }

    private static String readDeviceTreeModel() {
        for (String candidate : new String[]{
                "/proc/device-tree/model",
                "/sys/firmware/devicetree/base/model"
        }) {
            try {
                Path path = Path.of(candidate);
                if (Files.isReadable(path)) {
                    return Files.readString(path).replace("\0", "");
                }
            } catch (IOException | RuntimeException ignored) {
                // Try the next device-tree location.
            }
        }
        return "";
    }
}
