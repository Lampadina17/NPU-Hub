package com.npuhub.core.driver.impl;

import com.npuhub.core.driver.NpuDriver;
import com.npuhub.core.model.*;
import com.npuhub.jni.rockchip.RockchipNativeBridge;
import com.npuhub.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class RockchipNpuDriver implements NpuDriver {
    private static final Logger log = LoggerFactory.getLogger(RockchipNpuDriver.class);
    private boolean modelLoaded = false;

    public RockchipNpuDriver(LogService logService) {
        if (RockchipNativeBridge.isLibraryLoaded()) {
            RockchipNativeBridge.nativeSetLogCallback((level, message) -> {
                if (message == null || message.isBlank()) {
                    return;
                }
                String severity = level >= 4 ? "ERROR" : level == 3 ? "WARN" : "SYSTEM";
                logService.addLog(severity, "[llama.cpp] " + message.strip());
            });
        }
    }

    @Override
    public BackendType getBackendType() {
        return BackendType.ROCKCHIP;
    }

    @Override
    public HardwareInfo probeHardware() {
        boolean loaded = RockchipNativeBridge.isLibraryLoaded();
        boolean accel0Present = new File("/dev/accel/accel0").exists();
        boolean accelAvailable = loaded && (accel0Present || RockchipNativeBridge.nativeCheckAccel0Available());
        String version = accelAvailable ? RockchipNativeBridge.nativeGetRknnVersion() : "N/A";
        String statusDetails = accelAvailable
                ? "Ready for compatible GGUF models through Rocket; hardware acceleration is available"
                : (!accel0Present
                        ? "Rockchip NPU not detected; /dev/accel/accel0 is missing"
                        : (!loaded
                                ? "Rockchip NPU detected, but the Rocket JNI runtime is not loaded"
                                : "Rockchip NPU detected, but the accelerator health check failed"));
        
        return new HardwareInfo(
                BackendType.ROCKCHIP,
                "Rockchip Rocket Mainline Tri-Core NPU",
                "/dev/accel/accel0",
                accelAvailable,
                3,
                version,
                statusDetails
        );
    }

    @Override
    public boolean isAvailable() {
        boolean accel0Present = new File("/dev/accel/accel0").exists();
        return RockchipNativeBridge.isLibraryLoaded() && (accel0Present || RockchipNativeBridge.nativeCheckAccel0Available());
    }

    @Override
    public synchronized boolean loadModel(String modelPath, int contextWindow) {
        if (!isAvailable()) {
            log.error("Cannot load model: Rocket NPU device /dev/accel/accel0 is unavailable");
            throw new IllegalStateException(
                    "Mainline Rocket NPU device /dev/accel/accel0 is unreachable"
            );
        }
        log.info("Loading Rockchip Rocket Mainline model: {}", modelPath);
        modelLoaded = RockchipNativeBridge.nativeLoadRocketModel(modelPath, contextWindow);
        return modelLoaded;
    }

    @Override
    public synchronized boolean unloadModel() {
        if (!modelLoaded) return true;
        boolean ok = RockchipNativeBridge.nativeUnloadRocketModel();
        modelLoaded = false;
        return ok;
    }

    @Override
    public synchronized InferenceResponse generate(InferenceRequest request) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Mainline Rocket NPU device /dev/accel/accel0 is not ready"
            );
        }
        long start = System.currentTimeMillis();
        String text = RockchipNativeBridge.nativeGenerateRocket(
                request.prompt(),
                request.temperature(),
                request.topP(),
                request.maxTokens(),
                request.topK(),
                request.minP(),
                request.seed(),
                request.repeatLastN(),
                request.repeatPenalty(),
                request.frequencyPenalty(),
                request.presencePenalty()
        );
        long elapsed = System.currentTimeMillis() - start;
        int promptTokens = estimateTokens(request.prompt());
        int completionTokens = estimateTokens(text);
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
                BackendType.ROCKCHIP
        );
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        boolean insideToken = false;
        for (int index = 0; index < text.length(); index++) {
            boolean whitespace = Character.isWhitespace(text.charAt(index));
            if (!whitespace && !insideToken) {
                count++;
            }
            insideToken = !whitespace;
        }
        return count;
    }

    @Override
    public synchronized void generateStream(InferenceRequest request, Consumer<TokenChunk> tokenConsumer) {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Mainline Rocket NPU device /dev/accel/accel0 is not ready"
            );
        }
        String reqId = request.requestId() != null ? request.requestId() : UUID.randomUUID().toString();
        RockchipNativeBridge.nativeGenerateRocketStream(
                request.prompt(),
                request.temperature(),
                request.topP(),
                request.maxTokens(),
                request.topK(),
                request.minP(),
                request.seed(),
                request.repeatLastN(),
                request.repeatPenalty(),
                request.frequencyPenalty(),
                request.presencePenalty(),
                (token, done) -> tokenConsumer.accept(new TokenChunk(reqId, token, done, 30.0))
        );
    }
}
