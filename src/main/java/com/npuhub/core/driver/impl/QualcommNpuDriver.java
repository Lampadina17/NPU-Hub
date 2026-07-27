package com.npuhub.core.driver.impl;

import com.npuhub.core.driver.NpuDriver;
import com.npuhub.core.model.*;
import com.npuhub.jni.qualcomm.QualcommNativeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

@Component
public class QualcommNpuDriver implements NpuDriver {
    private static final Logger log = LoggerFactory.getLogger(QualcommNpuDriver.class);
    private boolean modelLoaded = false;

    @Override
    public BackendType getBackendType() {
        return BackendType.QUALCOMM;
    }

    @Override
    public HardwareInfo probeHardware() {
        boolean deviceExists = new java.io.File("/dev/kgsl-3d0").exists();
        boolean loaded = deviceExists && QualcommNativeBridge.isLibraryLoaded();
        boolean avail = loaded && QualcommNativeBridge.nativeCheckGenieAvailable();
        String statusDetails = avail
                ? "Ready for inference through QAIRT/Genie on the Hexagon NPU"
                : (!deviceExists
                   ? "Qualcomm accelerator not detected; requires a supported Snapdragon/Dragonwing platform and QAIRT"
                   : (!loaded
                      ? "Qualcomm accelerator detected, but the QAIRT/Genie JNI runtime is not loaded"
                      : "Qualcomm accelerator detected, but QAIRT/Genie could not initialize the Hexagon NPU"));
        return new HardwareInfo(
                BackendType.QUALCOMM,
                "Qualcomm QAIRT Hexagon NPU",
                "/dev/kgsl-3d0",
                avail,
                avail ? 1 : 0,
                avail ? "2.18.0" : "N/A",
                statusDetails
        );
    }

    @Override
    public boolean isAvailable() {
        return new java.io.File("/dev/kgsl-3d0").exists() &&
                QualcommNativeBridge.isLibraryLoaded() && QualcommNativeBridge.nativeCheckGenieAvailable();
    }

    @Override
    public synchronized boolean loadModel(String modelPath, int contextWindow) {
        if (!isAvailable()) return false;
        modelLoaded = QualcommNativeBridge.nativeLoadGenieModel(modelPath, contextWindow);
        return modelLoaded;
    }

    @Override
    public synchronized boolean unloadModel() {
        if (!modelLoaded) return true;
        boolean ok = QualcommNativeBridge.nativeUnloadGenieModel();
        modelLoaded = false;
        return ok;
    }

    @Override
    public synchronized InferenceResponse generate(InferenceRequest request) {
        long start = System.currentTimeMillis();
        String text = QualcommNativeBridge.nativeGenerateGenie(
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
                BackendType.QUALCOMM
        );
    }

    @Override
    public synchronized void generateStream(InferenceRequest request, Consumer<TokenChunk> tokenConsumer) {
        String reqId = request.requestId() != null ? request.requestId() : UUID.randomUUID().toString();
        QualcommNativeBridge.nativeGenerateGenieStream(
                request.prompt(),
                request.temperature(),
                request.topP(),
                request.maxTokens(),
                (token, done) -> tokenConsumer.accept(new TokenChunk(reqId, token, done, 40.0))
        );
    }
}
