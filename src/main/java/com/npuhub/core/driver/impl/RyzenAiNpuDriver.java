package com.npuhub.core.driver.impl;

import com.npuhub.core.driver.NpuDriver;
import com.npuhub.core.model.*;
import com.npuhub.jni.ryzenai.RyzenAiNativeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

@Component
public class RyzenAiNpuDriver implements NpuDriver {
    private static final Logger log = LoggerFactory.getLogger(RyzenAiNpuDriver.class);
    private boolean modelLoaded = false;

    @Override
    public BackendType getBackendType() {
        return BackendType.RYZENAI;
    }

    @Override
    public HardwareInfo probeHardware() {
        boolean deviceExists = new java.io.File("/dev/amdxdna").exists();
        boolean loaded = deviceExists && RyzenAiNativeBridge.isLibraryLoaded();
        boolean avail = loaded && RyzenAiNativeBridge.nativeCheckXdnaAvailable();
        String statusDetails = avail
                ? "Ready for inference through Ryzen AI; XDNA acceleration is available"
                : (!deviceExists
                   ? "AMD XDNA NPU not detected; requires a Ryzen AI system and the XDNA driver/runtime"
                   : (!loaded
                      ? "AMD XDNA NPU detected, but the Ryzen AI JNI runtime is not loaded"
                      : "AMD XDNA NPU detected, but the Ryzen AI runtime could not initialize it"));
        return new HardwareInfo(
                BackendType.RYZENAI,
                "AMD Ryzen AI XDNA NPU",
                "/dev/amdxdna",
                avail,
                avail ? 16 : 0,
                avail ? "1.2.0" : "N/A",
                statusDetails
        );
    }

    @Override
    public boolean isAvailable() {
        return new java.io.File("/dev/amdxdna").exists() &&
                RyzenAiNativeBridge.isLibraryLoaded() && RyzenAiNativeBridge.nativeCheckXdnaAvailable();
    }

    @Override
    public synchronized boolean loadModel(String modelPath, int contextWindow) {
        if (!isAvailable()) return false;
        modelLoaded = RyzenAiNativeBridge.nativeLoadOnnxGenieModel(modelPath, contextWindow);
        return modelLoaded;
    }

    @Override
    public synchronized boolean unloadModel() {
        if (!modelLoaded) return true;
        boolean ok = RyzenAiNativeBridge.nativeUnloadOnnxGenieModel();
        modelLoaded = false;
        return ok;
    }

    @Override
    public synchronized InferenceResponse generate(InferenceRequest request) {
        long start = System.currentTimeMillis();
        String text = RyzenAiNativeBridge.nativeGenerateOnnxGenie(
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
                BackendType.RYZENAI
        );
    }

    @Override
    public synchronized void generateStream(InferenceRequest request, Consumer<TokenChunk> tokenConsumer) {
        String reqId = request.requestId() != null ? request.requestId() : UUID.randomUUID().toString();
        RyzenAiNativeBridge.nativeGenerateOnnxGenieStream(
                request.prompt(),
                request.temperature(),
                request.topP(),
                request.maxTokens(),
                (token, done) -> tokenConsumer.accept(new TokenChunk(reqId, token, done, 35.0))
        );
    }
}
