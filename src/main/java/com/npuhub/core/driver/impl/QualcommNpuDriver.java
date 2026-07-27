package com.npuhub.core.driver.impl;

import com.npuhub.core.PlatformDetection;
import com.npuhub.core.driver.NpuDriver;
import com.npuhub.core.model.*;
import com.npuhub.jni.qualcomm.QualcommNativeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class QualcommNpuDriver implements NpuDriver {
    private static final Logger log = LoggerFactory.getLogger(QualcommNpuDriver.class);
    private static final String[] QAIRT_DEVICE_PATHS = {
            "/dev/kgsl-3d0",
            "/dev/adsprpc-smd",
            "/dev/fastrpc-adsp",
            "/dev/fastrpc-cdsp"
    };
    private boolean modelLoaded = false;

    @Override
    public BackendType getBackendType() {
        return BackendType.QUALCOMM;
    }

    @Override
    public HardwareInfo probeHardware() {
        boolean deviceExists = hasQairtDevice() || PlatformDetection.isRadxaBoard();
        boolean loaded = deviceExists && QualcommNativeBridge.isLibraryLoaded();
        boolean avail = loaded && QualcommNativeBridge.nativeCheckGenieAvailable();
        String statusDetails = avail
                ? "Ready for inference through native QAIRT/Genie on the Hexagon NPU"
                : (!deviceExists
                ? "Qualcomm accelerator not detected; requires a supported Radxa platform and QAIRT"
                : (!loaded
                ? "Qualcomm accelerator detected, but the QAIRT JNI runtime is not loaded"
                : "Qualcomm accelerator detected, but QAIRT/Genie could not initialize the Hexagon NPU"));
        return new HardwareInfo(
                BackendType.QUALCOMM,
                "Qualcomm QAIRT Hexagon NPU",
                qairtDevicePath(),
                avail,
                avail ? 1 : 0,
                avail ? "2.18.0" : "N/A",
                statusDetails
        );
    }

    @Override
    public boolean isAvailable() {
        return (hasQairtDevice() || PlatformDetection.isRadxaBoard())
                && QualcommNativeBridge.isLibraryLoaded()
                && QualcommNativeBridge.nativeCheckGenieAvailable();
    }

    private boolean hasQairtDevice() {
        for (String path : QAIRT_DEVICE_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    private String qairtDevicePath() {
        for (String path : QAIRT_DEVICE_PATHS) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return PlatformDetection.isRadxaBoard() ? "Radxa device-tree / QAIRT" : "/dev/kgsl-3d0";
    }

    @Override
    public synchronized boolean loadModel(String modelPath, int contextWindow) {
        if (!isAvailable()) return false;
        Path modelDirectory = Path.of(modelPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(modelDirectory)
                || !Files.isRegularFile(modelDirectory.resolve("libGenie.so"))) {
            log.error("QAIRT model directory is incomplete: {}", modelDirectory);
            return false;
        }
        modelLoaded = QualcommNativeBridge.nativeLoadGenieModel(modelDirectory.toString(), contextWindow);
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
                request.prompt(), request.temperature(), request.topP(), request.maxTokens()
        );
        long elapsed = System.currentTimeMillis() - start;
        int promptTokens = request.prompt() != null ? request.prompt().split("\\s+").length : 0;
        int completionTokens = text != null ? text.split("\\s+").length : 0;
        double tps = elapsed > 0 ? (completionTokens * 1000.0 / elapsed) : 0.0;
        return new InferenceResponse(
                request.requestId() != null ? request.requestId() : UUID.randomUUID().toString(),
                request.modelName(), text != null ? text : "", promptTokens, completionTokens,
                tps, 0.0, elapsed, BackendType.QUALCOMM
        );
    }

    @Override
    public synchronized void generateStream(InferenceRequest request, Consumer<TokenChunk> tokenConsumer) {
        String reqId = request.requestId() != null ? request.requestId() : UUID.randomUUID().toString();
        QualcommNativeBridge.nativeGenerateGenieStream(
                request.prompt(), request.temperature(), request.topP(), request.maxTokens(),
                (token, done) -> tokenConsumer.accept(new TokenChunk(reqId, token, done, 40.0))
        );
    }
}
