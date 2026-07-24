package com.npuhub.core.driver.impl;

import com.npuhub.core.driver.NpuDriver;
import com.npuhub.core.model.*;
import com.npuhub.jni.openvino.OpenVinoNativeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

@Component
public class OpenVinoNpuDriver implements NpuDriver {
    private static final Logger log = LoggerFactory.getLogger(OpenVinoNpuDriver.class);
    private boolean modelLoaded = false;
    private String currentModelPath = null;

    @Override
    public BackendType getBackendType() {
        return BackendType.OPENVINO;
    }

    @Override
    public HardwareInfo probeHardware() {
        // Intel NPU uses /dev/accel/accel* but NOT on ARM (that's Rockchip)
        boolean deviceExists = new java.io.File("/dev/dri/renderD128").exists() && System.getProperty("os.arch", "").contains("amd64");
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
        boolean deviceExists = new java.io.File("/dev/dri/renderD128").exists() && System.getProperty("os.arch", "").contains("amd64");
        return deviceExists && OpenVinoNativeBridge.isLibraryLoaded() && OpenVinoNativeBridge.nativeCheckDeviceAvailable();
    }

    @Override
    public synchronized boolean loadModel(String modelPath, int contextWindow) {
        if (!isAvailable()) return false;
        log.info("Loading OpenVINO model from {}", modelPath);
        modelLoaded = OpenVinoNativeBridge.nativeLoadModel(modelPath, contextWindow);
        if (modelLoaded) {
            currentModelPath = modelPath;
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
    public synchronized InferenceResponse generate(InferenceRequest request) {
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
    public synchronized void generateStream(InferenceRequest request, Consumer<TokenChunk> tokenConsumer) {
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
