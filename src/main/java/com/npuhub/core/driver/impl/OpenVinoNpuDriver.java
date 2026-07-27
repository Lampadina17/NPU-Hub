package com.npuhub.core.driver.impl;

import com.npuhub.core.driver.NpuDriver;
import com.npuhub.core.model.*;
import com.npuhub.jni.openvino.OpenVinoNativeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class OpenVinoNpuDriver implements NpuDriver {
    private static final Logger log = LoggerFactory.getLogger(OpenVinoNpuDriver.class);
    private static final Path INTEL_RENDER_VENDOR = Path.of("/sys/class/drm/renderD128/device/vendor");
    private volatile boolean modelLoaded = false;
    private volatile String currentModelPath = null;

    @Override
    public BackendType getBackendType() {
        return BackendType.OPENVINO;
    }

    @Override
    public HardwareInfo probeHardware() {
        boolean deviceExists = isIntelOpenVinoDevicePresent();
        boolean loaded = deviceExists && OpenVinoNativeBridge.isLibraryLoaded();
        boolean avail = loaded && OpenVinoNativeBridge.nativeCheckDeviceAvailable();
        String deviceName = avail ? OpenVinoNativeBridge.nativeGetDeviceName() : "Intel NPU (OpenVINO)";
        String statusDetails = avail
                ? "Ready for inference through OpenVINO GenAI; Intel NPU acceleration is available"
                : (!deviceExists
                   ? "Intel NPU not detected; requires compatible Intel hardware and OpenVINO GenAI"
                   : (!loaded
                      ? "Intel NPU detected, but the OpenVINO JNI runtime is not loaded"
                      : "Intel NPU detected, but OpenVINO could not initialize it"));
        return new HardwareInfo(
                BackendType.OPENVINO,
                deviceName,
                "/dev/intel_npu",
                avail,
                avail ? 4 : 0,
                avail ? "2024.3.0" : "N/A",
                statusDetails
        );
    }

    @Override
    public boolean isAvailable() {
        boolean deviceExists = isIntelOpenVinoDevicePresent();
        return deviceExists && OpenVinoNativeBridge.isLibraryLoaded() && OpenVinoNativeBridge.nativeCheckDeviceAvailable();
    }

    private boolean isIntelOpenVinoDevicePresent() {
        boolean hasRender = new java.io.File("/dev/dri/renderD128").exists();
        boolean hasAccel = new java.io.File("/dev/accel/accel0").exists();
        if (!hasRender && !hasAccel) {
            return false;
        }

        try {
            if (Files.isReadable(INTEL_RENDER_VENDOR)) {
                String vendorId = Files.readString(INTEL_RENDER_VENDOR).trim().toLowerCase();
                if ("0x8086".equals(vendorId)) return true;
            }
            Path accelVendor = Path.of("/sys/class/accel/accel0/device/vendor");
            if (Files.isReadable(accelVendor)) {
                String vendorId = Files.readString(accelVendor).trim().toLowerCase();
                if ("0x8086".equals(vendorId)) return true;
            }
            return true;
        } catch (Exception error) {
            log.debug("Unable to inspect DRM vendor for OpenVINO selection: {}", error.getMessage());
            return true;
        }
    }

    @Override
    public synchronized boolean loadModel(String modelPath, int contextWindow) {
        if (!isAvailable()) {
            log.error("Cannot load OpenVINO model: Intel NPU device or JNI runtime is unavailable");
            return false;
        }
        String absolutePath = Path.of(modelPath).toAbsolutePath().normalize().toString();
        log.info("Loading OpenVINO model from absolute path {}", absolutePath);
        modelLoaded = OpenVinoNativeBridge.nativeLoadModel(absolutePath, contextWindow);
        if (modelLoaded) {
            currentModelPath = absolutePath;
        } else {
            log.error("OpenVINO nativeLoadModel failed for path {}", absolutePath);
        }
        return modelLoaded;
    }

    @Override
    public synchronized boolean unloadModel() {
        if (!modelLoaded) return true;
        log.info("Unloading OpenVINO model");
        boolean ok = OpenVinoNativeBridge.nativeUnloadModel();
        modelLoaded = false;
        currentModelPath = null;
        return ok;
    }

    @Override
    public InferenceResponse generate(InferenceRequest request) {
        long start = System.currentTimeMillis();
        String text = OpenVinoNativeBridge.nativeGenerate(
                request.prompt(),
                request.temperature(),
                request.topP(),
                request.maxTokens()
        );
        long elapsed = System.currentTimeMillis() - start;
        int promptTokens = request.prompt() != null ? request.prompt().split("\\s+").length : 0;
        int completionTokens = text != null ? text.split("\\s+").length : 0;
        double tps = elapsed > 0 ? (completionTokens * 1000.0 / elapsed) : 0.0;

        return new InferenceResponse(
                request.requestId() != null ? request.requestId() : UUID.randomUUID().toString(),
                request.modelName(),
                text != null ? text : "",
                promptTokens,
                completionTokens,
                tps,
                0.0,
                elapsed,
                BackendType.OPENVINO
        );
    }

    @Override
    public void generateStream(InferenceRequest request, Consumer<TokenChunk> tokenConsumer) {
        String reqId = request.requestId() != null ? request.requestId() : UUID.randomUUID().toString();
        OpenVinoNativeBridge.nativeGenerateStream(
                request.prompt(),
                request.temperature(),
                request.topP(),
                request.maxTokens(),
                (token, done) -> tokenConsumer.accept(new TokenChunk(reqId, token, done, 25.0))
        );
    }
}
