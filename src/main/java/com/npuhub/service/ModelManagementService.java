package com.npuhub.service;

import com.npuhub.core.driver.NpuDriver;
import com.npuhub.core.driver.NpuDriverRegistry;
import com.npuhub.core.model.BackendType;
import com.npuhub.core.model.ModelMetadata;
import com.npuhub.util.GgufMetadataReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelManagementService {
    private static final Logger log = LoggerFactory.getLogger(ModelManagementService.class);
    private static final String RECOMMENDED_ROCKCHIP_QUANTIZATION = "Q4_K_M";
    private static final List<String> ROCKCHIP_QUANTIZATIONS = List.of(
            "Q4_K_M",
            "Q4_K_S",
            "Q4_0",
            "Q4_1",
            "Q3_K_M",
            "Q3_K_S",
            "Q3_K_L",
            "Q5_K_M",
            "Q5_K_S",
            "Q6_K",
            "Q8_0",
            "Q2_K",
            "Q2_K_L",
            "BF16",
            "F16"
    );
    private final NpuDriverRegistry driverRegistry;
    private final ModelScopeDownloaderService downloaderService;
    private final Map<String, ModelMetadata> registeredModels = new ConcurrentHashMap<>();
    private volatile String currentlyLoadedModelId = null;
    private volatile String currentlyLoadedModelPath = null;
    private volatile String currentlyLoadedQuantization = null;
    private volatile BackendType currentlyLoadedBackend = null;
    private volatile int currentlyLoadedContextWindow = 0;
    private volatile long currentlyLoadedAtEpochMs = 0L;
    private volatile long currentlyExpiresAtEpochMs = 0L;

    @Value("${npu.models.directory:models}")
    private String modelsDirectoryPath;

    @Value("${npu.ollama.minimum-loaded-context:4096}")
    private int minimumLoadedContextWindow;

    public ModelManagementService(NpuDriverRegistry driverRegistry, @Lazy ModelScopeDownloaderService downloaderService) {
        this.driverRegistry = driverRegistry;
        this.downloaderService = downloaderService;
        initCatalogModels();
    }

    private void initCatalogModels() {
        registeredModels.clear();

        // 1. Radxa Qualcomm QAIRT v68 NPU Models
        registerModel(new ModelMetadata(
                "radxa/Llama3.2-1B-1024-qairt-v68",
                "radxa/Llama3.2-1B-1024-qairt-v68",
                "models/Llama3.2-1B-1024-qairt-v68",
                "Llama3",
                null,
                null,
                null,
                BackendType.QUALCOMM,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "radxa/Llama3.2-1B-4096-qairt-v68",
                "radxa/Llama3.2-1B-4096-qairt-v68",
                "models/Llama3.2-1B-4096-qairt-v68",
                "Llama3",
                null,
                null,
                null,
                BackendType.QUALCOMM,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "radxa/Qwen2.5-0.5B-v68",
                "radxa/Qwen2.5-0.5B-v68",
                "models/Qwen2.5-0.5B-v68",
                "Qwen2",
                null,
                null,
                null,
                BackendType.QUALCOMM,
                false,
                false,
                "IDLE",
                0.0
        ));

        // 2. OpenVINO NPU Optimized Models (HuggingFace/OpenVINO NPU)
        registerModel(new ModelMetadata(
                "OpenVINO/gemma-3-4b-it-int4-cw-ov",
                "OpenVINO/gemma-3-4b-it-int4-cw-ov",
                "models/gemma-3-4b-it-int4-cw-ov",
                "Gemma3",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/Qwen3-8B-int4-cw-ov",
                "OpenVINO/Qwen3-8B-int4-cw-ov",
                "models/Qwen3-8B-int4-cw-ov",
                "Qwen3",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/Phi-3.5-mini-instruct-int4-cw-ov",
                "OpenVINO/Phi-3.5-mini-instruct-int4-cw-ov",
                "models/Phi-3.5-mini-instruct-int4-cw-ov",
                "Phi3",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/Mistral-7B-Instruct-v0.2-int4-cw-ov",
                "OpenVINO/Mistral-7B-Instruct-v0.2-int4-cw-ov",
                "models/Mistral-7B-Instruct-v0.2-int4-cw-ov",
                "Mistral",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/Phi-3-mini-4k-instruct-int4-cw-ov",
                "OpenVINO/Phi-3-mini-4k-instruct-int4-cw-ov",
                "models/Phi-3-mini-4k-instruct-int4-cw-ov",
                "Phi3",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/Mistral-7B-Instruct-v0.3-int4-cw-ov",
                "OpenVINO/Mistral-7B-Instruct-v0.3-int4-cw-ov",
                "models/Mistral-7B-Instruct-v0.3-int4-cw-ov",
                "Mistral",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/DeepSeek-R1-Distill-Qwen-7B-nf4-ov",
                "OpenVINO/DeepSeek-R1-Distill-Qwen-7B-nf4-ov",
                "models/DeepSeek-R1-Distill-Qwen-7B-nf4-ov",
                "Qwen2",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/DeepSeek-R1-Distill-Qwen-1.5B-int4-gq-ov",
                "OpenVINO/DeepSeek-R1-Distill-Qwen-1.5B-int4-gq-ov",
                "models/DeepSeek-R1-Distill-Qwen-1.5B-int4-gq-ov",
                "Qwen2",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/Phi-3.5-mini-instruct-int4-gq-ov",
                "OpenVINO/Phi-3.5-mini-instruct-int4-gq-ov",
                "models/Phi-3.5-mini-instruct-int4-gq-ov",
                "Phi3",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "OpenVINO/Phi-3-mini-4k-instruct-int4-gq-ov",
                "OpenVINO/Phi-3-mini-4k-instruct-int4-gq-ov",
                "models/Phi-3-mini-4k-instruct-int4-gq-ov",
                "Phi3",
                null,
                null,
                null,
                BackendType.OPENVINO,
                false,
                false,
                "IDLE",
                0.0
        ));

        // 3. Rockchip NPU Models (HuggingFace/unsloth)
        registerModel(new ModelMetadata(
                "unsloth/gemma-4-E4B-it-GGUF",
                "unsloth/gemma-4-E4B-it-GGUF",
                "models/gemma-4-E4B-it-GGUF",
                "Gemma",
                RECOMMENDED_ROCKCHIP_QUANTIZATION,
                null,
                131_072,
                BackendType.ROCKCHIP,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "unsloth/gemma-4-E2B-it-GGUF",
                "unsloth/gemma-4-E2B-it-GGUF",
                "models/gemma-4-E2B-it-GGUF",
                "Gemma",
                RECOMMENDED_ROCKCHIP_QUANTIZATION,
                null,
                131_072,
                BackendType.ROCKCHIP,
                false,
                false,
                "IDLE",
                0.0
        ));

        registerModel(new ModelMetadata(
                "unsloth/Phi-4-mini-instruct-GGUF",
                "unsloth/Phi-4-mini-instruct-GGUF",
                "models/Phi-4-mini-instruct-GGUF/Phi-4-mini-instruct-Q4_K_M.gguf",
                "Phi",
                RECOMMENDED_ROCKCHIP_QUANTIZATION,
                3_840_000_000L,
                131_072,
                BackendType.ROCKCHIP,
                false,
                false,
                "IDLE",
                0.0
        ));

        log.info("Initialized Radxa Qualcomm & OpenVINO NPU catalog models");
    }

    public void registerModel(ModelMetadata model) {
        registeredModels.put(model.id(), model);
    }

    private String getFolderName(String modelId) {
        return modelId.contains("/") ? modelId.substring(modelId.lastIndexOf('/') + 1) : modelId;
    }

    private boolean checkDownloaded(String modelId) {
        ModelMetadata model = findModel(modelId);
        if (downloaderService == null || model == null) {
            return false;
        }

        String quantization = model.compatibleBackend() == BackendType.ROCKCHIP
                ? normalizeRockchipQuantization(model.quantization())
                : null;
        return downloaderService.isDownloaded(model.id(), modelsDirectoryPath, quantization);
    }

    public List<String> getRockchipQuantizations() {
        return ROCKCHIP_QUANTIZATIONS;
    }

    public String normalizeRockchipQuantization(String quantization) {
        String normalized = quantization == null || quantization.isBlank()
                ? RECOMMENDED_ROCKCHIP_QUANTIZATION
                : quantization.trim().toUpperCase(Locale.ROOT);
        if (!ROCKCHIP_QUANTIZATIONS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported Rockchip GGUF quantization '" + quantization
                            + "'. Choose one of: " + String.join(", ", ROCKCHIP_QUANTIZATIONS)
            );
        }
        return normalized;
    }

    private boolean matchesQuantization(File file, String quantization) {
        String name = file.getName().toUpperCase(Locale.ROOT);
        int start = name.indexOf(quantization);
        while (start >= 0) {
            int end = start + quantization.length();
            boolean leftBoundary = start == 0 || Character.isLetterOrDigit(name.charAt(start - 1)) == false;
            boolean rightBoundary = end == name.length()
                    || (Character.isLetterOrDigit(name.charAt(end)) == false && name.charAt(end) != '_');
            if (leftBoundary && rightBoundary) {
                return true;
            }
            start = name.indexOf(quantization, start + 1);
        }
        return false;
    }

    private File[] findQuantizedGgufFiles(File directory, String quantization) {
        File[] candidates = directory.listFiles(
                file -> file.isFile()
                        && file.getName().toLowerCase(Locale.ROOT).endsWith(".gguf")
                        && matchesQuantization(file, quantization)
        );
        if (candidates == null) {
            return new File[0];
        }
        Arrays.sort(candidates, Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        return candidates;
    }

    private String stripOllamaTag(String modelId) {
        if (modelId == null) {
            return null;
        }
        String trimmed = modelId.trim();
        int tagSeparator = trimmed.lastIndexOf(':');
        if (tagSeparator > trimmed.lastIndexOf('/')) {
            String tag = trimmed.substring(tagSeparator + 1);
            if ("latest".equalsIgnoreCase(tag) || ROCKCHIP_QUANTIZATIONS.contains(tag.toUpperCase(Locale.ROOT))) {
                return trimmed.substring(0, tagSeparator);
            }
        }
        return trimmed;
    }

    private ModelMetadata findModel(String modelId) {
        String normalizedId = stripOllamaTag(modelId);
        ModelMetadata metadata = registeredModels.get(normalizedId);
        if (metadata != null) {
            return metadata;
        }

        String folderName = getFolderName(normalizedId);
        return registeredModels.values().stream()
                .filter(model -> model.id().equalsIgnoreCase(normalizedId)
                        || getFolderName(model.id()).equalsIgnoreCase(folderName))
                .findFirst()
                .orElse(null);
    }

    public Optional<ModelMetadata> getModel(String modelId) {
        ModelMetadata model = findModel(modelId);
        if (model == null) {
            return Optional.empty();
        }
        boolean loaded = model.id().equals(currentlyLoadedModelId);
        boolean downloaded = checkDownloaded(model.id());
        String status = downloaderService != null ? downloaderService.getStatus(model.id()) : "IDLE";
        Double progress = downloaderService != null ? downloaderService.getProgress(model.id()) : 0.0;
        return Optional.of(new ModelMetadata(
                model.id(),
                model.name(),
                model.path(),
                model.architecture(),
                model.quantization(),
                model.parameterCount(),
                resolveModelContextWindow(model),
                model.compatibleBackend(),
                loaded,
                downloaded,
                status,
                progress
        ));
    }

    private Integer resolveModelContextWindow(ModelMetadata model) {
        if (model.compatibleBackend() != BackendType.ROCKCHIP) {
            return model.contextWindow();
        }

        File configured = resolveConfiguredPath(model.path());
        File gguf = configured.isFile() ? configured : null;
        if (gguf == null && configured.isDirectory()) {
            File[] preferred = findQuantizedGgufFiles(
                    configured,
                    normalizeRockchipQuantization(model.quantization())
            );
            if (preferred.length > 0) {
                gguf = preferred[0];
            }
        }

        OptionalInt detected = GgufMetadataReader.readContextLength(gguf);
        return detected.isPresent() ? detected.getAsInt() : model.contextWindow();
    }

    private String resolveRockchipModelPath(String configuredPath) {
        return resolveRockchipModelPath(configuredPath, RECOMMENDED_ROCKCHIP_QUANTIZATION);
    }

    private String resolveRockchipModelPath(String configuredPath, String requestedQuantization) {
        String quantization = normalizeRockchipQuantization(requestedQuantization);
        File configured = resolveConfiguredPath(configuredPath);
        if (configured.isFile()) {
            if (!configured.getName().toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                throw new IllegalArgumentException(
                        "Rockchip models must be GGUF files, received: " + configured.getAbsolutePath()
                );
            }
            if (matchesQuantization(configured, quantization)) {
                return configured.getAbsolutePath();
            }
            configured = configured.getParentFile();
        } else if (configured.getName().toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            // The catalog may point at its recommended file before that variant is
            // downloaded. Search the containing repository directory for the user's
            // selected quantization.
            configured = configured.getParentFile();
        }

        if (configured == null || !configured.isDirectory()) {
            throw new IllegalArgumentException(
                    "Rockchip model directory does not exist for: " + configuredPath
            );
        }

        File[] candidates = findQuantizedGgufFiles(configured, quantization);
        if (candidates.length == 0) {
            File[] availableFiles = configured.listFiles(
                    file -> file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".gguf")
            );
            String available = availableFiles == null
                    ? "none"
                    : Arrays.stream(availableFiles)
                      .map(File::getName)
                      .sorted()
                      .reduce((left, right) -> left + ", " + right)
                      .orElse("none");
            throw new IllegalArgumentException(
                    "GGUF quantization " + quantization + " is not downloaded in "
                            + configured.getAbsolutePath() + ". Available: " + available
            );
        }

        log.info("Selected Rockchip GGUF {}: {}", quantization, candidates[0].getAbsolutePath());
        return candidates[0].getAbsolutePath();
    }

    public File resolveLocalModelFile(String modelId, String requestedQuantization) {
        ModelMetadata metadata = findModel(modelId);
        if (metadata == null) {
            throw new IllegalArgumentException("model '" + modelId + "' not found");
        }

        if (metadata.compatibleBackend() == BackendType.ROCKCHIP) {
            String quantization = requestedQuantization;
            if ((quantization == null || quantization.isBlank()) && modelId != null) {
                int separator = modelId.lastIndexOf(':');
                if (separator > modelId.lastIndexOf('/')) {
                    String possibleQuantization = modelId.substring(separator + 1).toUpperCase(Locale.ROOT);
                    if (ROCKCHIP_QUANTIZATIONS.contains(possibleQuantization)) {
                        quantization = possibleQuantization;
                    }
                }
            }
            return new File(resolveRockchipModelPath(
                    metadata.path(),
                    quantization == null ? metadata.quantization() : quantization
            ));
        }

        File configured = resolveConfiguredPath(metadata.path());
        if (!configured.exists()) {
            throw new IllegalArgumentException("model '" + modelId + "' is not downloaded");
        }
        return configured;
    }

    public List<String> getDownloadedQuantizations(String modelId) {
        ModelMetadata metadata = findModel(modelId);
        if (metadata == null || metadata.compatibleBackend() != BackendType.ROCKCHIP) {
            return List.of();
        }

        File configured = resolveConfiguredPath(metadata.path());
        File directory = configured.isDirectory() ? configured : configured.getParentFile();
        if (directory == null || !directory.isDirectory()) {
            return List.of();
        }

        List<String> downloaded = new ArrayList<>();
        for (String quantization : ROCKCHIP_QUANTIZATIONS) {
            if (findQuantizedGgufFiles(directory, quantization).length > 0) {
                downloaded.add(quantization);
            }
        }
        return downloaded;
    }

    private File resolveConfiguredPath(String configuredPath) {
        File configured = new File(configuredPath);
        if (configured.isAbsolute()) {
            return configured;
        }

        String normalized = configuredPath.replace('\\', '/');
        if (normalized.equals("models")) {
            return new File(modelsDirectoryPath);
        }
        if (normalized.startsWith("models/")) {
            return new File(modelsDirectoryPath, normalized.substring("models/".length()));
        }
        return configured;
    }

    public List<ModelMetadata> listModelsForActiveNpu() {
        NpuDriver activeDriver = driverRegistry.selectActiveDriver("auto");
        BackendType activeBackend = activeDriver.getBackendType();

        List<ModelMetadata> filtered = new ArrayList<>();
        for (ModelMetadata m : registeredModels.values()) {
            if (m.compatibleBackend() == activeBackend) {
                boolean loaded = m.id().equals(currentlyLoadedModelId);
                boolean downloaded = checkDownloaded(m.id());
                String status = downloaderService != null ? downloaderService.getStatus(m.id()) : "IDLE";
                Double progress = downloaderService != null ? downloaderService.getProgress(m.id()) : 0.0;

                filtered.add(new ModelMetadata(m.id(), m.name(), m.path(), m.architecture(), m.quantization(), m.parameterCount(), resolveModelContextWindow(m), m.compatibleBackend(), loaded, downloaded, status, progress));
            }
        }
        if (filtered.isEmpty()) {
            return listAllModels();
        }
        filtered.sort(Comparator.comparing(ModelMetadata::name));
        return filtered;
    }

    public List<ModelMetadata> listAllModels() {
        File dir = new File(modelsDirectoryPath);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        String folderName = f.getName();
                        boolean alreadyRegistered = registeredModels.keySet().stream()
                                .anyMatch(id -> getFolderName(id).equals(folderName));

                        if (!alreadyRegistered) {
                            String id = folderName.contains("-ov") ? "OpenVINO/" + folderName : "radxa/" + folderName;
                            registeredModels.put(id, new ModelMetadata(
                                    id,
                                    id,
                                    f.getAbsolutePath(),
                                    "NPU-Native",
                                    null,
                                    null,
                                    null,
                                    folderName.contains("-ov") ? BackendType.OPENVINO : BackendType.QUALCOMM,
                                    id.equals(currentlyLoadedModelId),
                                    true,
                                    "IDLE",
                                    100.0
                            ));
                        }
                    }
                }
            }
        }

        List<ModelMetadata> list = new ArrayList<>();
        for (ModelMetadata m : registeredModels.values()) {
            boolean loaded = m.id().equals(currentlyLoadedModelId);
            boolean downloaded = checkDownloaded(m.id());
            String status = downloaderService != null ? downloaderService.getStatus(m.id()) : "IDLE";
            Double progress = downloaderService != null ? downloaderService.getProgress(m.id()) : 0.0;

            list.add(new ModelMetadata(m.id(), m.name(), m.path(), m.architecture(), m.quantization(), m.parameterCount(), resolveModelContextWindow(m), m.compatibleBackend(), loaded, downloaded, status, progress));
        }
        list.sort(Comparator.comparing(ModelMetadata::name));
        return list;
    }

    public boolean loadModel(String modelId, String preferredBackend) {
        return loadModel(modelId, preferredBackend, null);
    }

    public synchronized boolean loadModel(String modelId, String preferredBackend, String requestedQuantization) {
        return loadModel(modelId, preferredBackend, requestedQuantization, 4096);
    }

    public synchronized boolean loadModel(
            String modelId,
            String preferredBackend,
            String requestedQuantization,
            int requestedContextWindow
    ) {
        ModelMetadata metadata = findModel(modelId);
        if (metadata == null) {
            log.warn("Model ID '{}' not found in registry", modelId);
            return false;
        }

        NpuDriver driver = driverRegistry.selectActiveDriver(preferredBackend != null ? preferredBackend : metadata.compatibleBackend().name());
        String quantization = driver.getBackendType() == BackendType.ROCKCHIP
                ? normalizeRockchipQuantization(
                requestedQuantization == null ? metadata.quantization() : requestedQuantization)
                : null;
        String modelPath = quantization == null
                ? metadata.path()
                : resolveRockchipModelPath(metadata.path(), quantization);
        log.info("Resolved model '{}' to '{}'", metadata.id(), modelPath);

        Integer modelContextWindow = resolveModelContextWindow(metadata);
        int contextWindow = requestedContextWindow > 0 ? requestedContextWindow : 4096;
        if (driver.getBackendType() == BackendType.ROCKCHIP) {
            contextWindow = Math.max(contextWindow, minimumLoadedContextWindow);
        }
        if (modelContextWindow != null && modelContextWindow > 0) {
            contextWindow = Math.min(contextWindow, modelContextWindow);
        }
        contextWindow = Math.max(512, contextWindow);

        if (metadata.id().equals(currentlyLoadedModelId)
                && modelPath.equals(currentlyLoadedModelPath)
                && contextWindow <= currentlyLoadedContextWindow) {
            return true;
        }

        if (currentlyLoadedModelId != null) {
            log.info("Auto-unloading currently active model '{}' before loading '{}'", currentlyLoadedModelId, metadata.id());
            boolean unloaded = unloadCurrentModel();
            if (!unloaded) {
                log.warn("Failed to auto-unload currently active model '{}'", currentlyLoadedModelId);
            }
        }

        boolean success = driver.loadModel(modelPath, contextWindow);
        if (success) {
            currentlyLoadedModelId = metadata.id();
            currentlyLoadedModelPath = modelPath;
            currentlyLoadedQuantization = quantization;
            currentlyLoadedBackend = driver.getBackendType();
            currentlyLoadedContextWindow = contextWindow;
            currentlyLoadedAtEpochMs = System.currentTimeMillis();
            // Model residency is explicit: it remains loaded until the user
            // presses Unload or loads another model.
            currentlyExpiresAtEpochMs = Long.MAX_VALUE;
            log.info("Model '{}' loaded onto NPU driver {}", metadata.id(), driver.getBackendType());
        }
        return success;
    }

    public synchronized boolean unloadCurrentModel() {
        if (currentlyLoadedModelId == null) {
            return true;
        }
        NpuDriver driver = currentlyLoadedBackend == null
                ? driverRegistry.selectActiveDriver("auto")
                : driverRegistry.getDriver(currentlyLoadedBackend)
                  .orElseThrow(() -> new IllegalStateException(
                          "Loaded NPU backend " + currentlyLoadedBackend + " is no longer registered"
                  ));
        boolean success = driver.unloadModel();
        if (success) {
            clearLoadedState();
        }
        return success;
    }

    private void clearLoadedState() {
        currentlyLoadedModelId = null;
        currentlyLoadedModelPath = null;
        currentlyLoadedQuantization = null;
        currentlyLoadedBackend = null;
        currentlyLoadedContextWindow = 0;
        currentlyLoadedAtEpochMs = 0L;
        currentlyExpiresAtEpochMs = 0L;
    }

    public synchronized void updateKeepAlive(long keepAliveMs) {
        if (currentlyLoadedModelId == null) {
            return;
        }
        if (keepAliveMs < 0) {
            currentlyExpiresAtEpochMs = Long.MAX_VALUE;
        } else {
            currentlyExpiresAtEpochMs = System.currentTimeMillis() + keepAliveMs;
        }
    }

    public synchronized Optional<LoadedModelState> getLoadedModelState() {
        if (currentlyLoadedModelId == null) {
            return Optional.empty();
        }
        if (currentlyExpiresAtEpochMs != Long.MAX_VALUE
                && currentlyExpiresAtEpochMs > 0
                && System.currentTimeMillis() >= currentlyExpiresAtEpochMs) {
            unloadCurrentModel();
            return Optional.empty();
        }
        return Optional.of(new LoadedModelState(
                currentlyLoadedModelId,
                currentlyLoadedModelPath,
                currentlyLoadedQuantization,
                currentlyLoadedBackend,
                currentlyLoadedContextWindow,
                currentlyLoadedAtEpochMs,
                currentlyExpiresAtEpochMs
        ));
    }

    @Scheduled(fixedDelayString = "${npu.ollama.keep-alive-scan-ms:1000}")
    public void unloadExpiredModel() {
        getLoadedModelState();
    }

    public boolean deleteModel(String modelId) {
        return deleteModel(modelId, null);
    }

    public boolean deleteModel(String modelId, String requestedQuantization) {
        ModelMetadata metadata = findModel(modelId);
        if (metadata != null
                && metadata.compatibleBackend() == BackendType.ROCKCHIP
                && requestedQuantization != null
                && !requestedQuantization.isBlank()) {
            String quantization = normalizeRockchipQuantization(requestedQuantization);
            if (modelId.equals(currentlyLoadedModelId)) {
                throw new IllegalStateException(
                        "Unload model " + modelId + " before deleting " + quantization
                );
            }

            File configured = resolveConfiguredPath(metadata.path());
            File directory = configured.isDirectory() ? configured : configured.getParentFile();
            if (directory == null || !directory.isDirectory()) {
                return false;
            }

            File[] selectedFiles = findQuantizedGgufFiles(directory, quantization);
            if (selectedFiles.length == 0) {
                return false;
            }

            boolean deleted = true;
            for (File selectedFile : selectedFiles) {
                log.info("Deleting Rockchip GGUF variant: {}", selectedFile.getAbsolutePath());
                deleted &= selectedFile.delete();
            }
            return deleted;
        }

        String folderName = getFolderName(modelId);
        File targetDir = new File(modelsDirectoryPath, folderName);
        log.info("Deleting local model directory: {}", targetDir.getAbsolutePath());

        if (currentlyLoadedModelId != null && currentlyLoadedModelId.contains(folderName)) {
            unloadCurrentModel();
        }

        return deleteDirectoryRecursively(targetDir);
    }

    private boolean deleteDirectoryRecursively(File file) {
        if (!file.exists()) return true;
        File[] contents = file.listFiles();
        if (contents != null) {
            for (File f : contents) {
                deleteDirectoryRecursively(f);
            }
        }
        return file.delete();
    }

    public String getCurrentlyLoadedModelId() {
        return currentlyLoadedModelId;
    }

    public record LoadedModelState(
            String modelId,
            String modelPath,
            String quantization,
            BackendType backend,
            int contextWindow,
            long loadedAtEpochMs,
            long expiresAtEpochMs
    ) {
    }
}
