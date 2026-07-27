package com.npuhub.web.controller;

import com.npuhub.core.model.BackendType;
import com.npuhub.core.model.HardwareInfo;
import com.npuhub.core.model.ModelMetadata;
import com.npuhub.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/control")
@CrossOrigin(origins = "*")
public class ControlPanelApiController {
    private static final Logger log = LoggerFactory.getLogger(ControlPanelApiController.class);
    private final HardwareDiscoveryService hardwareDiscoveryService;
    private final ModelManagementService modelManagementService;
    private final SettingsService settingsService;
    private final ModelScopeDownloaderService modelScopeDownloaderService;

    @Value("${npu.models.directory:models}")
    private String modelsDirectoryPath;

    private final SetupService setupService;
    private final LogService logService;
    private final InferenceApiStateService inferenceApiStateService;

    public ControlPanelApiController(HardwareDiscoveryService hardwareDiscoveryService,
                                     ModelManagementService modelManagementService,
                                     SettingsService settingsService,
                                     ModelScopeDownloaderService modelScopeDownloaderService,
                                     SetupService setupService,
                                     LogService logService,
                                     InferenceApiStateService inferenceApiStateService) {
        this.hardwareDiscoveryService = hardwareDiscoveryService;
        this.modelManagementService = modelManagementService;
        this.settingsService = settingsService;
        this.modelScopeDownloaderService = modelScopeDownloaderService;
        this.setupService = setupService;
        this.logService = logService;
        this.inferenceApiStateService = inferenceApiStateService;
    }

    @GetMapping("/hardware")
    public List<HardwareInfo> getHardwareStatus() {
        return hardwareDiscoveryService.scanHardware();
    }

    @GetMapping("/diagnostics")
    public Map<String, Object> getDiagnostics() {
        return hardwareDiscoveryService.getSystemDiagnosticDetails();
    }

    @GetMapping("/models")
    public List<ModelMetadata> getModels(@RequestParam(name = "all", defaultValue = "false") boolean all) {
        return all ? modelManagementService.listAllModels() : modelManagementService.listModelsForActiveNpu();
    }

    @GetMapping("/api/status")
    public Map<String, Object> getInferenceApiStatus() {
        return Map.of(
                "enabled", inferenceApiStateService.isEnabled(),
                "modelLoaded", modelManagementService.getLoadedModelState().isPresent()
        );
    }

    @PostMapping("/api/start")
    public ResponseEntity<Map<String, Object>> startInferenceApi() {
        try {
            inferenceApiStateService.start();
            return ResponseEntity.ok(Map.of("enabled", true));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "enabled", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/api/stop")
    public Map<String, Object> stopInferenceApi() {
        inferenceApiStateService.stop();
        return Map.of("enabled", false);
    }

    @PostMapping("/models/load")
    public ResponseEntity<Map<String, Object>> loadModel(@RequestBody Map<String, String> payload) {
        String modelId = payload.get("modelId");
        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "modelId is required"
            ));
        }
        String preferredBackend = payload.get("preferredBackend");
        String quantization = payload.get("quantization");

        final boolean ok;
        try {
            ok = modelManagementService.loadModel(modelId, preferredBackend, quantization);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "modelId", modelId == null ? "" : modelId,
                    "error", e.getMessage()
            ));
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", ok);
        resp.put("modelId", modelId);
        if (quantization != null && !quantization.isBlank()) {
            resp.put("quantization", quantization);
        }
        resp.put("activeModel", modelManagementService.getCurrentlyLoadedModelId());
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.badRequest().body(resp);
    }

    @PostMapping("/models/unload")
    public ResponseEntity<Map<String, Object>> unloadModel() {
        try {
            boolean ok = modelManagementService.unloadCurrentModel();
            Map<String, Object> response = new HashMap<>();
            response.put("success", ok);
            response.put("activeModel", modelManagementService.getCurrentlyLoadedModelId());
            return ok ? ResponseEntity.ok(response) : ResponseEntity.badRequest().body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    @PostMapping("/models/download")
    public ResponseEntity<Map<String, Object>> downloadModel(@RequestBody Map<String, String> payload) {
        String modelId = payload.get("modelId");
        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "modelId is required"));
        }

        String quantization = payload.get("quantization");
        if (modelId.startsWith("unsloth/")) {
            try {
                quantization = modelManagementService.normalizeRockchipQuantization(quantization);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        } else {
            quantization = null;
        }

        modelScopeDownloaderService.downloadModelFromModelScope(
                modelId,
                modelsDirectoryPath,
                quantization
        );
        Map<String, Object> response = new HashMap<>();
        response.put("status", "STARTED");
        response.put("modelId", modelId);
        if (quantization != null) {
            response.put("quantization", quantization);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/models/download/status")
    public ResponseEntity<Map<String, Object>> getDownloadStatus(
            @RequestParam("modelId") String modelId,
            @RequestParam(name = "quantization", required = false) String quantization
    ) {
        if (modelId.startsWith("unsloth/")) {
            try {
                quantization = modelManagementService.normalizeRockchipQuantization(quantization);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
        } else {
            quantization = null;
        }

        String status = modelScopeDownloaderService.getStatus(modelId, quantization);
        double progress = modelScopeDownloaderService.getProgress(modelId, quantization);
        boolean isLocal = modelScopeDownloaderService.isDownloaded(
                modelId,
                modelsDirectoryPath,
                quantization
        );

        Map<String, Object> response = new HashMap<>();
        response.put("modelId", modelId);
        response.put("status", isLocal ? "COMPLETED" : status);
        response.put("progress", isLocal ? 100.0 : progress);
        response.put("isDownloaded", isLocal);
        if (quantization != null) {
            response.put("quantization", quantization);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/models/delete")
    public ResponseEntity<Map<String, Object>> deleteModel(@RequestBody Map<String, String> payload) {
        String modelId = payload.get("modelId");
        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "modelId is required"));
        }
        String quantization = payload.get("quantization");
        try {
            boolean ok = modelManagementService.deleteModel(modelId, quantization);
            Map<String, Object> response = new HashMap<>();
            response.put("success", ok);
            response.put("modelId", modelId);
            if (quantization != null && !quantization.isBlank()) {
                response.put("quantization", quantization);
            }
            return ok ? ResponseEntity.ok(response) : ResponseEntity.badRequest().body(response);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "modelId", modelId,
                    "error", e.getMessage()
            ));
        }
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> settings = settingsService.getSettings();
        String configuredBackend = String.valueOf(settings.getOrDefault("preferredBackend", "auto"));
        String recommendedBackend = hardwareDiscoveryService.getRecommendedBackend()
                .map(BackendType::name)
                .orElse("");

        boolean automaticSelection = configuredBackend.isBlank()
                || "auto".equalsIgnoreCase(configuredBackend)
                || !isKnownBackend(configuredBackend);
        String effectiveBackend = automaticSelection
                ? recommendedBackend
                : configuredBackend.toUpperCase();
        String selectionState = automaticSelection
                ? (recommendedBackend.isBlank() ? "UNSUPPORTED" : "AUTO")
                : "MANUAL";

        settings.put("configuredBackend", configuredBackend);
        settings.put("recommendedBackend", recommendedBackend);
        settings.put("preferredBackend", effectiveBackend);
        settings.put("backendSelectionMode", automaticSelection ? "AUTO" : "MANUAL");
        settings.put("selectionState", selectionState);
        settings.put("recommendationAvailable", !recommendedBackend.isBlank());
        return settings;
    }

    private boolean isKnownBackend(String backend) {
        try {
            BackendType.valueOf(backend.toUpperCase());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @PostMapping("/settings")
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> newSettings) {
        settingsService.updateSettings(newSettings);
        return ResponseEntity.ok(Map.of("status", "success", "settings", settingsService.getSettings()));
    }

    @PostMapping("/setup/intel-driver")
    public ResponseEntity<Map<String, Object>> installIntelDriver() {
        setupService.installIntelRuntimeAsync();
        return ResponseEntity.ok(Map.of("status", "STARTED", "taskId", "intel-driver"));
    }

    @PostMapping("/setup/build-worker")
    public ResponseEntity<Map<String, Object>> buildWorker(@RequestBody Map<String, String> payload) {
        String workerType = payload.getOrDefault("workerType", "openvino");
        setupService.buildWorkerAsync(workerType);
        return ResponseEntity.ok(Map.of("status", "STARTED", "taskId", "build-" + workerType));
    }

    @PostMapping("/setup/modelscope")
    public ResponseEntity<Map<String, Object>> installModelScope() {
        setupService.installModelScopeAsync();
        return ResponseEntity.ok(Map.of("status", "STARTED", "taskId", "modelscope-setup"));
    }

    @PostMapping("/setup/openvino-sdk")
    public ResponseEntity<Map<String, Object>> installOpenVinoSdk() {
        setupService.installOpenVinoSdkAsync();
        return ResponseEntity.ok(Map.of("status", "STARTED", "taskId", "openvino-sdk"));
    }

    @GetMapping("/setup/status")
    public ResponseEntity<Map<String, Object>> getSetupStatus(@RequestParam("taskId") String taskId) {
        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "status", setupService.getStatus(taskId),
                "progress", setupService.getProgress(taskId)
        ));
    }

    @GetMapping("/logs")
    public ResponseEntity<List<LogService.LogEntry>> getLogs(@RequestParam(value = "afterId", defaultValue = "0") long afterId) {
        return ResponseEntity.ok(logService.getLogsAfter(afterId));
    }
}
