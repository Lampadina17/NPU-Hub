package com.npuhub.core.model;

public enum BackendType {
    ROCKCHIP("Rockchip Rocket Mainline", "Rockchip RK3588/RK3588S NPU (/dev/accel/accel0)"),
    OPENVINO("Intel OpenVINO GenAI", "Intel NPU"),
    QUALCOMM("Qualcomm QAIRT / Genie", "Radxa / Qualcomm Hexagon NPU"),
    RYZENAI("AMD Ryzen AI XDNA", "AMD XDNA NPU");

    private final String displayName;
    private final String targetHardware;

    BackendType(String displayName, String targetHardware) {
        this.displayName = displayName;
        this.targetHardware = targetHardware;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getTargetHardware() {
        return targetHardware;
    }
}
