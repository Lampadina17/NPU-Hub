package com.npuhub.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npuhub.core.model.InferenceResponse;
import com.npuhub.core.model.ModelMetadata;
import com.npuhub.service.ModelManagementService;
import com.npuhub.service.ModelScopeDownloaderService;
import com.npuhub.service.OllamaInferenceFacade;
import com.npuhub.service.OllamaInferenceFacade.PreparedGeneration;
import com.npuhub.service.OllamaModelService;
import com.npuhub.service.OllamaModelService.ModelDescriptor;
import com.npuhub.web.NdjsonEmitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@CrossOrigin(origins = "*")
public class OllamaApiController {
    private final OllamaInferenceFacade inferenceFacade;
    private final OllamaModelService modelService;
    private final ModelManagementService modelManagementService;
    private final ModelScopeDownloaderService downloaderService;
    private final ObjectMapper objectMapper;

    @Value("${npu.models.directory:models}")
    private String modelsDirectoryPath;

    @Value("${npu.ollama.compatibility-version:0.20.0}")
    private String compatibilityVersion;

    public OllamaApiController(
            OllamaInferenceFacade inferenceFacade,
            OllamaModelService modelService,
            ModelManagementService modelManagementService,
            ModelScopeDownloaderService downloaderService,
            ObjectMapper objectMapper
    ) {
        this.inferenceFacade = inferenceFacade;
        this.modelService = modelService;
        this.modelManagementService = modelManagementService;
        this.downloaderService = downloaderService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "Ollama is running";
    }

    @GetMapping("/api/version")
    public Map<String, String> getVersion() {
        return Map.of("version", compatibilityVersion);
    }

    @GetMapping("/api/tags")
    public Map<String, Object> listTags() {
        return Map.of(
                "models",
                modelService.listLocalModels().stream().map(modelService::tag).toList()
        );
    }

    @GetMapping("/api/ps")
    public Map<String, Object> listRunningModels() {
        Optional<ModelManagementService.LoadedModelState> loaded =
                modelManagementService.getLoadedModelState();
        if (loaded.isEmpty()) {
            return Map.of("models", List.of());
        }

        ModelManagementService.LoadedModelState state = loaded.get();
        String visibleName = state.quantization() == null
                ? state.modelId()
                : state.modelId() + ":" + state.quantization();
        ModelDescriptor descriptor = modelService.resolve(visibleName);
        Map<String, Object> model = new LinkedHashMap<>(modelService.tag(descriptor));
        model.put(
                "expires_at",
                state.expiresAtEpochMs() == Long.MAX_VALUE
                        ? "9999-12-31T23:59:59Z"
                        : Instant.ofEpochMilli(state.expiresAtEpochMs()).toString()
        );
        long size = modelService.size(new File(state.modelPath()));
        model.put("size", size);
        model.put("size_vram", size);
        model.put("context_length", state.contextWindow());
        model.put(
                "max_output_tokens",
                Math.min(32_768, Math.max(1, state.contextWindow() - 256))
        );
        return Map.of("models", List.of(model));
    }

    @PostMapping("/api/show")
    public Map<String, Object> show(@RequestBody Map<String, Object> body) {
        String model = firstNonBlank(body.get("model"), body.get("name"));
        if (model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        boolean verbose = booleanValue(body.get("verbose"), false);
        return modelService.show(modelService.resolve(model), verbose);
    }

    @PostMapping("/api/generate")
    public ResponseEntity<ResponseBodyEmitter> generate(@RequestBody Map<String, Object> body) {
        boolean stream = booleanValue(body.get("stream"), true);
        PreparedGeneration prepared = inferenceFacade.prepareGenerate(body);
        String prompt = prepared.inferenceRequest().prompt();

        if (prompt.isEmpty()) {
            String doneReason = prepared.keepAliveMs() == 0L ? "unload" : "load";
            inferenceFacade.finish(prepared);
            return jsonResponse(generationChunk(
                    prepared,
                    "",
                    true,
                    doneReason,
                    prepared.loadDurationNs(),
                    0L,
                    0,
                    0
            ));
        }

        if (!stream) {
            InferenceResponse response = inferenceFacade.generate(prepared);
            return jsonResponse(generationChunk(
                    prepared,
                    response.text(),
                    true,
                    "stop",
                    prepared.loadDurationNs(),
                    response.totalExecutionTimeMs() * 1_000_000L,
                    response.promptTokens(),
                    response.completionTokens()
            ));
        }

        NdjsonEmitter emitter = new NdjsonEmitter(objectMapper);
        long started = System.nanoTime();
        AtomicInteger completionCount = new AtomicInteger();
        inferenceFacade.generateStream(
                prepared,
                chunk -> {
                    try {
                        if (chunk.done()) {
                            emitter.sendJson(generationChunk(
                                    prepared,
                                    "",
                                    true,
                                    "stop",
                                    prepared.loadDurationNs(),
                                    System.nanoTime() - started,
                                    estimateTokens(prompt),
                                    completionCount.get()
                            ));
                            emitter.complete();
                        } else {
                            completionCount.incrementAndGet();
                            emitter.sendJson(generationChunk(
                                    prepared,
                                    chunk.token(),
                                    false,
                                    null,
                                    0L,
                                    0L,
                                    0,
                                    0
                            ));
                        }
                    } catch (IOException error) {
                        emitter.completeWithError(error);
                    }
                },
                error -> streamError(emitter, error)
        );
        return ResponseEntity.ok().contentType(NdjsonEmitter.NDJSON).body(emitter);
    }

    @PostMapping("/api/chat")
    public ResponseEntity<ResponseBodyEmitter> chat(@RequestBody Map<String, Object> body) {
        boolean stream = booleanValue(body.get("stream"), true);
        PreparedGeneration prepared = inferenceFacade.prepareChat(body);

        if (!stream) {
            InferenceResponse response = inferenceFacade.generate(prepared);
            return jsonResponse(chatChunk(
                    prepared,
                    response.text(),
                    true,
                    "stop",
                    prepared.loadDurationNs(),
                    response.totalExecutionTimeMs() * 1_000_000L,
                    response.promptTokens(),
                    response.completionTokens()
            ));
        }

        NdjsonEmitter emitter = new NdjsonEmitter(objectMapper);
        long started = System.nanoTime();
        AtomicInteger completionCount = new AtomicInteger();
        inferenceFacade.generateStream(
                prepared,
                chunk -> {
                    try {
                        if (chunk.done()) {
                            emitter.sendJson(chatChunk(
                                    prepared,
                                    "",
                                    true,
                                    "stop",
                                    prepared.loadDurationNs(),
                                    System.nanoTime() - started,
                                    estimateTokens(prepared.inferenceRequest().prompt()),
                                    completionCount.get()
                            ));
                            emitter.complete();
                        } else {
                            completionCount.incrementAndGet();
                            emitter.sendJson(chatChunk(
                                    prepared,
                                    chunk.token(),
                                    false,
                                    null,
                                    0L,
                                    0L,
                                    0,
                                    0
                            ));
                        }
                    } catch (IOException error) {
                        emitter.completeWithError(error);
                    }
                },
                error -> streamError(emitter, error)
        );
        return ResponseEntity.ok().contentType(NdjsonEmitter.NDJSON).body(emitter);
    }

    @PostMapping({"/api/embed", "/api/embeddings"})
    public Map<String, Object> embed() {
        throw new UnsupportedOperationException(
                "embeddings are not supported by the selected NPU runtime"
        );
    }

    @PostMapping("/api/create")
    public ResponseEntity<ResponseBodyEmitter> create(@RequestBody Map<String, Object> body) {
        String model = firstNonBlank(body.get("model"), body.get("name"));
        String from = stringValue(body.get("from"));
        if (from.isBlank()) {
            throw new UnsupportedOperationException(
                    "this NPU runtime can create models only with the 'from' field"
            );
        }
        modelService.create(
                model,
                from,
                stringValue(body.get("template")),
                stringValue(body.get("renderer")),
                stringValue(body.get("parser")),
                body.get("license"),
                stringValue(body.get("system")),
                mapValue(body.get("parameters")),
                messageList(body.get("messages")),
                stringValue(body.get("quantize"))
        );

        if (!booleanValue(body.get("stream"), true)) {
            return jsonResponse(Map.of("status", "success"));
        }
        NdjsonEmitter emitter = new NdjsonEmitter(objectMapper);
        emitAfterReturn(emitter, List.of(
                Map.of("status", "creating model"),
                Map.of("status", "success")
        ));
        return ResponseEntity.ok().contentType(NdjsonEmitter.NDJSON).body(emitter);
    }

    @PostMapping("/api/copy")
    public ResponseEntity<Void> copy(@RequestBody Map<String, Object> body) {
        String source = stringValue(body.get("source"));
        String destination = stringValue(body.get("destination"));
        if (source.isBlank() || destination.isBlank()) {
            throw new IllegalArgumentException("source and destination are required");
        }
        modelService.copy(source, destination);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/delete")
    public ResponseEntity<Void> delete(@RequestBody Map<String, Object> body) {
        String model = firstNonBlank(body.get("model"), body.get("name"));
        if (model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (!modelService.delete(model)) {
            throw new IllegalArgumentException("model '" + model + "' not found");
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/pull")
    public ResponseEntity<ResponseBodyEmitter> pull(@RequestBody Map<String, Object> body) {
        String modelName = firstNonBlank(body.get("model"), body.get("name"));
        if (modelName.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        String baseModel = stripKnownQuantizationTag(modelName);
        ModelMetadata metadata = modelManagementService.getModel(baseModel)
                .orElseThrow(() -> new IllegalArgumentException(
                        "model '" + modelName + "' is not available in the NPU Hub catalog"
                ));
        String quantization = quantizationFromName(modelName);
        if (metadata.compatibleBackend().name().equals("ROCKCHIP")) {
            quantization = modelManagementService.normalizeRockchipQuantization(
                    quantization == null ? metadata.quantization() : quantization
            );
        } else {
            quantization = null;
        }

        boolean stream = booleanValue(body.get("stream"), true);
        if (downloaderService.isDownloaded(metadata.id(), modelsDirectoryPath, quantization)) {
            if (!stream) {
                return jsonResponse(Map.of("status", "success"));
            }
            NdjsonEmitter emitter = new NdjsonEmitter(objectMapper);
            emitAfterReturn(emitter, List.of(Map.of("status", "success")));
            return ResponseEntity.ok().contentType(NdjsonEmitter.NDJSON).body(emitter);
        }

        downloaderService.downloadModelFromModelScope(
                metadata.id(),
                modelsDirectoryPath,
                quantization
        );
        if (!stream) {
            waitForDownload(metadata.id(), quantization, null);
            return jsonResponse(Map.of("status", "success"));
        }

        NdjsonEmitter emitter = new NdjsonEmitter(objectMapper);
        String selectedQuantization = quantization;
        CompletableFuture.runAsync(() ->
                waitForDownload(metadata.id(), selectedQuantization, emitter));
        return ResponseEntity.ok().contentType(NdjsonEmitter.NDJSON).body(emitter);
    }

    @PostMapping("/api/push")
    public Map<String, Object> push() {
        throw new UnsupportedOperationException(
                "pushing to the Ollama registry is not configured on NPU Hub"
        );
    }

    @RequestMapping(path = "/api/blobs/{digest}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> hasBlob(@PathVariable("digest") String digest) {
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/api/blobs/{digest}")
    public Map<String, Object> pushBlob(@PathVariable("digest") String digest) {
        throw new UnsupportedOperationException(
                "Ollama blob uploads are not configured on NPU Hub"
        );
    }

    private Map<String, Object> generationChunk(
            PreparedGeneration prepared,
            String response,
            boolean done,
            String doneReason,
            long loadDurationNs,
            long totalDurationNs,
            int promptTokens,
            int completionTokens
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", prepared.requestedModel());
        result.put("created_at", Instant.now().toString());
        result.put("response", response == null ? "" : response);
        result.put("done", done);
        if (doneReason != null) {
            result.put("done_reason", doneReason);
        }
        if (done) {
            addMetrics(
                    result,
                    loadDurationNs,
                    totalDurationNs,
                    promptTokens,
                    completionTokens
            );
        }
        return result;
    }

    private Map<String, Object> chatChunk(
            PreparedGeneration prepared,
            String content,
            boolean done,
            String doneReason,
            long loadDurationNs,
            long totalDurationNs,
            int promptTokens,
            int completionTokens
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", prepared.requestedModel());
        result.put("created_at", Instant.now().toString());
        result.put("message", Map.of(
                "role", "assistant",
                "content", content == null ? "" : content
        ));
        result.put("done", done);
        if (doneReason != null) {
            result.put("done_reason", doneReason);
        }
        if (done) {
            addMetrics(
                    result,
                    loadDurationNs,
                    totalDurationNs,
                    promptTokens,
                    completionTokens
            );
        }
        return result;
    }

    private void addMetrics(
            Map<String, Object> response,
            long loadDurationNs,
            long totalDurationNs,
            int promptTokens,
            int completionTokens
    ) {
        response.put("total_duration", Math.max(totalDurationNs, loadDurationNs));
        response.put("load_duration", Math.max(0L, loadDurationNs));
        response.put("prompt_eval_count", Math.max(0, promptTokens));
        response.put(
                "prompt_eval_duration",
                Math.max(0L, totalDurationNs - loadDurationNs) / 4L
        );
        response.put("eval_count", Math.max(0, completionTokens));
        response.put(
                "eval_duration",
                Math.max(0L, totalDurationNs - loadDurationNs)
        );
    }

    private void streamError(NdjsonEmitter emitter, Throwable error) {
        try {
            emitter.sendJson(Map.of(
                    "error",
                    error.getMessage() == null ? "generation failed" : error.getMessage()
            ));
            emitter.complete();
        } catch (IOException sendError) {
            emitter.completeWithError(sendError);
        }
    }

    private void emitAfterReturn(
            NdjsonEmitter emitter,
            List<? extends Map<String, ?>> values
    ) {
        CompletableFuture.runAsync(() -> {
            try {
                for (Map<String, ?> value : values) {
                    emitter.sendJson(value);
                }
                emitter.complete();
            } catch (IOException error) {
                emitter.completeWithError(error);
            }
        });
    }

    private ResponseEntity<ResponseBodyEmitter> jsonResponse(Object value) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(600_000L);
        CompletableFuture.runAsync(() -> {
            try {
                emitter.send(value, MediaType.APPLICATION_JSON);
                emitter.complete();
            } catch (IOException error) {
                emitter.completeWithError(error);
            }
        });
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(emitter);
    }

    private void waitForDownload(
            String modelId,
            String quantization,
            NdjsonEmitter emitter
    ) {
        double lastProgress = -1.0;
        try {
            if (emitter != null) {
                emitter.sendJson(Map.of("status", "pulling manifest"));
            }
            while (true) {
                String status = downloaderService.getStatus(modelId, quantization);
                double progress = downloaderService.getProgress(modelId, quantization);
                if (emitter != null && progress != lastProgress) {
                    Map<String, Object> update = new LinkedHashMap<>();
                    update.put("status", "downloading model");
                    update.put("total", 100);
                    update.put("completed", Math.max(0, Math.min(100, Math.round(progress))));
                    emitter.sendJson(update);
                    lastProgress = progress;
                }
                if ("COMPLETED".equals(status)
                        || downloaderService.isDownloaded(modelId, modelsDirectoryPath, quantization)) {
                    if (emitter != null) {
                        emitter.sendJson(Map.of("status", "success"));
                        emitter.complete();
                    }
                    return;
                }
                if ("FAILED".equals(status)) {
                    throw new IllegalStateException("failed to pull model '" + modelId + "'");
                }
                Thread.sleep(500L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (emitter != null) {
                streamError(emitter, interrupted);
                return;
            }
            throw new IllegalStateException("model pull interrupted", interrupted);
        } catch (IOException error) {
            if (emitter != null) {
                emitter.completeWithError(error);
                return;
            }
            throw new IllegalStateException("failed to stream pull progress", error);
        } catch (RuntimeException error) {
            if (emitter != null) {
                streamError(emitter, error);
                return;
            }
            throw error;
        }
    }

    private String stripKnownQuantizationTag(String model) {
        return quantizationFromName(model) == null
                ? model.replaceFirst("(?i):latest$", "")
                : model.substring(0, model.lastIndexOf(':'));
    }

    private String quantizationFromName(String model) {
        int separator = model.lastIndexOf(':');
        if (separator <= model.lastIndexOf('/')) {
            return null;
        }
        String tag = model.substring(separator + 1).toUpperCase(Locale.ROOT);
        return modelManagementService.getRockchipQuantizations().contains(tag) ? tag : null;
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

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private String firstNonBlank(Object first, Object second) {
        String firstText = stringValue(first);
        return firstText.isBlank() ? stringValue(second) : firstText;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("parameters must be an object");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messageList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("messages must be an array");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object message : list) {
            if (!(message instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("messages must contain objects");
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }
}
