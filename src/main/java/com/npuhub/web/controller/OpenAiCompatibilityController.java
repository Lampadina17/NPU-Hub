package com.npuhub.web.controller;

import com.npuhub.core.model.InferenceResponse;
import com.npuhub.service.OllamaInferenceFacade;
import com.npuhub.service.OllamaInferenceFacade.PreparedGeneration;
import com.npuhub.service.OllamaModelService;
import com.npuhub.service.OllamaModelService.ModelDescriptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@CrossOrigin(origins = "*")
public class OpenAiCompatibilityController {
    private final OllamaModelService modelService;
    private final OllamaInferenceFacade inferenceFacade;

    public OpenAiCompatibilityController(
            OllamaModelService modelService,
            OllamaInferenceFacade inferenceFacade
    ) {
        this.modelService = modelService;
        this.inferenceFacade = inferenceFacade;
    }

    @GetMapping("/v1/models")
    public Map<String, Object> models() {
        return Map.of(
                "object", "list",
                "data", modelService.listLocalModels().stream().map(this::openAiModel).toList()
        );
    }

    @GetMapping("/v1/models/{*modelName}")
    public Map<String, Object> model(
            @org.springframework.web.bind.annotation.PathVariable("modelName") String modelName
    ) {
        String normalized = modelName.startsWith("/") ? modelName.substring(1) : modelName;
        return openAiModel(modelService.resolve(normalized));
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<ResponseBodyEmitter> chatCompletions(
            @RequestBody Map<String, Object> request
    ) {
        rejectMultipleChoices(request);
        Map<String, Object> ollamaRequest = toOllamaChatRequest(request);
        PreparedGeneration prepared = inferenceFacade.prepareChat(ollamaRequest);
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        boolean stream = booleanValue(request.get("stream"), false);

        if (!stream) {
            InferenceResponse response = inferenceFacade.generate(prepared);
            return jsonResponse(Map.of(
                    "id", completionId,
                    "object", "chat.completion",
                    "created", created,
                    "model", prepared.requestedModel(),
                    "choices", List.of(Map.of(
                            "index", 0,
                            "message", Map.of(
                                    "role", "assistant",
                                    "content", response.text()
                            ),
                            "finish_reason", "stop"
                    )),
                    "usage", usage(
                            response.promptTokens(),
                            response.completionTokens()
                    )
            ));
        }

        SseEmitter emitter = new SseEmitter(600_000L);
        AtomicInteger completionTokens = new AtomicInteger();
        AtomicBoolean first = new AtomicBoolean(true);
        inferenceFacade.generateStream(
                prepared,
                chunk -> {
                    try {
                        if (chunk.done()) {
                            sendSse(emitter, chatCompletionChunk(
                                    completionId,
                                    created,
                                    prepared.requestedModel(),
                                    Map.of(),
                                    "stop"
                            ));
                            if (includeUsage(request)) {
                                sendSse(emitter, Map.of(
                                        "id", completionId,
                                        "object", "chat.completion.chunk",
                                        "created", created,
                                        "model", prepared.requestedModel(),
                                        "choices", List.of(),
                                        "usage", usage(
                                                estimateTokens(prepared.inferenceRequest().prompt()),
                                                completionTokens.get()
                                        )
                                ));
                            }
                            emitter.send(SseEmitter.event().data("[DONE]"));
                            emitter.complete();
                        } else {
                            completionTokens.incrementAndGet();
                            Map<String, Object> delta = new LinkedHashMap<>();
                            if (first.compareAndSet(true, false)) {
                                delta.put("role", "assistant");
                            }
                            delta.put("content", chunk.token());
                            sendSse(emitter, chatCompletionChunk(
                                    completionId,
                                    created,
                                    prepared.requestedModel(),
                                    delta,
                                    null
                            ));
                        }
                    } catch (IOException error) {
                        emitter.completeWithError(error);
                    }
                },
                error -> sendOpenAiStreamError(emitter, error)
        );
        return sseResponse(emitter);
    }

    @PostMapping("/v1/completions")
    public ResponseEntity<ResponseBodyEmitter> completions(
            @RequestBody Map<String, Object> request
    ) {
        rejectMultipleChoices(request);
        Map<String, Object> ollamaRequest = toOllamaGenerateRequest(request);
        PreparedGeneration prepared = inferenceFacade.prepareGenerate(ollamaRequest);
        String completionId = "cmpl-" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        boolean stream = booleanValue(request.get("stream"), false);

        if (!stream) {
            InferenceResponse response = inferenceFacade.generate(prepared);
            return jsonResponse(Map.of(
                    "id", completionId,
                    "object", "text_completion",
                    "created", created,
                    "model", prepared.requestedModel(),
                    "choices", List.of(Map.of(
                            "text", response.text(),
                            "index", 0,
                            "finish_reason", "stop"
                    )),
                    "usage", usage(
                            response.promptTokens(),
                            response.completionTokens()
                    )
            ));
        }

        SseEmitter emitter = new SseEmitter(600_000L);
        inferenceFacade.generateStream(
                prepared,
                chunk -> {
                    try {
                        Map<String, Object> choice = new LinkedHashMap<>();
                        choice.put("text", chunk.done() ? "" : chunk.token());
                        choice.put("index", 0);
                        choice.put("finish_reason", chunk.done() ? "stop" : null);
                        sendSse(emitter, Map.of(
                                "id", completionId,
                                "object", "text_completion",
                                "created", created,
                                "model", prepared.requestedModel(),
                                "choices", List.of(choice)
                        ));
                        if (chunk.done()) {
                            emitter.send(SseEmitter.event().data("[DONE]"));
                            emitter.complete();
                        }
                    } catch (IOException error) {
                        emitter.completeWithError(error);
                    }
                },
                error -> sendOpenAiStreamError(emitter, error)
        );
        return sseResponse(emitter);
    }

    @PostMapping("/v1/responses")
    public ResponseEntity<ResponseBodyEmitter> responses(
            @RequestBody Map<String, Object> request
    ) {
        Map<String, Object> chatRequest = responsesToChatRequest(request);
        PreparedGeneration prepared = inferenceFacade.prepareChat(chatRequest);
        String responseId = "resp_" + UUID.randomUUID().toString().replace("-", "");
        long created = Instant.now().getEpochSecond();
        boolean stream = booleanValue(request.get("stream"), false);

        if (!stream) {
            InferenceResponse generated = inferenceFacade.generate(prepared);
            return jsonResponse(responseObject(
                    responseId,
                    created,
                    prepared.requestedModel(),
                    generated.text(),
                    "completed",
                    generated.promptTokens(),
                    generated.completionTokens()
            ));
        }

        SseEmitter emitter = new SseEmitter(600_000L);
        StringBuilder output = new StringBuilder();
        AtomicInteger sequence = new AtomicInteger();
        try {
            sendResponseEvent(emitter, "response.created", Map.of(
                    "type", "response.created",
                    "sequence_number", sequence.getAndIncrement(),
                    "response", responseObject(
                            responseId,
                            created,
                            prepared.requestedModel(),
                            "",
                            "in_progress",
                            0,
                            0
                    )
            ));
        } catch (IOException error) {
            emitter.completeWithError(error);
            return sseResponse(emitter);
        }

        inferenceFacade.generateStream(
                prepared,
                chunk -> {
                    try {
                        if (!chunk.done()) {
                            output.append(chunk.token());
                            sendResponseEvent(emitter, "response.output_text.delta", Map.of(
                                    "type", "response.output_text.delta",
                                    "sequence_number", sequence.getAndIncrement(),
                                    "item_id", responseId + "_message",
                                    "output_index", 0,
                                    "content_index", 0,
                                    "delta", chunk.token()
                            ));
                            return;
                        }
                        Map<String, Object> completed = responseObject(
                                responseId,
                                created,
                                prepared.requestedModel(),
                                output.toString(),
                                "completed",
                                estimateTokens(prepared.inferenceRequest().prompt()),
                                estimateTokens(output.toString())
                        );
                        sendResponseEvent(emitter, "response.completed", Map.of(
                                "type", "response.completed",
                                "sequence_number", sequence.getAndIncrement(),
                                "response", completed
                        ));
                        emitter.complete();
                    } catch (IOException error) {
                        emitter.completeWithError(error);
                    }
                },
                error -> sendOpenAiStreamError(emitter, error)
        );
        return sseResponse(emitter);
    }

    @PostMapping("/v1/embeddings")
    public Map<String, Object> embeddings() {
        throw new UnsupportedOperationException(
                "embeddings are not supported by the selected NPU runtime"
        );
    }

    @PostMapping("/v1/images/generations")
    public Map<String, Object> images() {
        throw new UnsupportedOperationException(
                "image generation is not supported by the selected NPU runtime"
        );
    }

    private Map<String, Object> openAiModel(ModelDescriptor descriptor) {
        String owner = "library";
        int slash = descriptor.name().indexOf('/');
        if (slash > 0) {
            owner = descriptor.name().substring(0, slash);
        }
        return Map.of(
                "id", descriptor.name(),
                "object", "model",
                "created", Math.max(0L, descriptor.file().lastModified() / 1000L),
                "owned_by", owner
        );
    }

    private Map<String, Object> toOllamaChatRequest(Map<String, Object> request) {
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("model", request.get("model"));
        translated.put("messages", request.get("messages"));
        translated.put("stream", request.get("stream"));
        translated.put("tools", request.get("tools"));
        translated.put("options", openAiOptions(request));
        Object responseFormat = request.get("response_format");
        if (responseFormat instanceof Map<?, ?> format) {
            Object type = format.get("type");
            if ("json_object".equals(type)) {
                translated.put("format", "json");
            } else if ("json_schema".equals(type)) {
                Object jsonSchema = format.get("json_schema");
                if (jsonSchema instanceof Map<?, ?> schemaWrapper
                        && schemaWrapper.get("schema") != null) {
                    translated.put("format", schemaWrapper.get("schema"));
                }
            }
        }
        return translated;
    }

    private Map<String, Object> toOllamaGenerateRequest(Map<String, Object> request) {
        Object prompt = request.get("prompt");
        if (prompt != null && !(prompt instanceof String)) {
            throw new IllegalArgumentException("prompt must be a string");
        }
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("model", request.get("model"));
        translated.put("prompt", prompt);
        translated.put("suffix", request.get("suffix"));
        translated.put("stream", request.get("stream"));
        translated.put("options", openAiOptions(request));
        return translated;
    }

    private Map<String, Object> responsesToChatRequest(Map<String, Object> request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        String instructions = stringValue(request.get("instructions"));
        if (!instructions.isBlank()) {
            messages.add(Map.of("role", "system", "content", instructions));
        }
        Object input = request.get("input");
        if (input instanceof String text) {
            messages.add(Map.of("role", "user", "content", text));
        } else if (input instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("role", map.containsKey("role") ? map.get("role") : "user");
                    message.put("content", map.get("content"));
                    messages.add(message);
                }
            }
        } else {
            throw new IllegalArgumentException("input must be a string or an array");
        }
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("model", request.get("model"));
        translated.put("messages", messages);
        translated.put("stream", request.get("stream"));
        translated.put("tools", request.get("tools"));
        translated.put("options", openAiOptions(request));
        return translated;
    }

    private Map<String, Object> openAiOptions(Map<String, Object> request) {
        Map<String, Object> options = new LinkedHashMap<>();
        copy(request, options, "temperature", "temperature");
        copy(request, options, "top_p", "top_p");
        copy(request, options, "seed", "seed");
        copy(request, options, "stop", "stop");
        copy(request, options, "frequency_penalty", "frequency_penalty");
        copy(request, options, "presence_penalty", "presence_penalty");
        if (request.get("max_tokens") != null) {
            options.put("num_predict", request.get("max_tokens"));
        } else if (request.get("max_output_tokens") != null) {
            options.put("num_predict", request.get("max_output_tokens"));
        }
        return options;
    }

    private void copy(
            Map<String, Object> source,
            Map<String, Object> destination,
            String sourceKey,
            String destinationKey
    ) {
        if (source.get(sourceKey) != null) {
            destination.put(destinationKey, source.get(sourceKey));
        }
    }

    private void rejectMultipleChoices(Map<String, Object> request) {
        Object value = request.get("n");
        if (value instanceof Number number && number.intValue() != 1) {
            throw new IllegalArgumentException("only n=1 is supported by this NPU runtime");
        }
    }

    private Map<String, Object> chatCompletionChunk(
            String id,
            long created,
            String model,
            Map<String, Object> delta,
            String finishReason
    ) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        return Map.of(
                "id", id,
                "object", "chat.completion.chunk",
                "created", created,
                "model", model,
                "choices", List.of(choice)
        );
    }

    private Map<String, Object> responseObject(
            String id,
            long created,
            String model,
            String text,
            String status,
            int inputTokens,
            int outputTokens
    ) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "output_text");
        content.put("text", text);
        content.put("annotations", List.of());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id + "_message");
        message.put("type", "message");
        message.put("status", status);
        message.put("role", "assistant");
        message.put("content", List.of(content));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("object", "response");
        response.put("created_at", created);
        response.put("status", status);
        response.put("model", model);
        response.put("output", List.of(message));
        response.put("parallel_tool_calls", true);
        response.put("tool_choice", "auto");
        response.put("tools", List.of());
        response.put("usage", Map.of(
                "input_tokens", inputTokens,
                "output_tokens", outputTokens,
                "total_tokens", inputTokens + outputTokens
        ));
        response.put("error", null);
        return response;
    }

    private Map<String, Integer> usage(int promptTokens, int completionTokens) {
        return Map.of(
                "prompt_tokens", Math.max(0, promptTokens),
                "completion_tokens", Math.max(0, completionTokens),
                "total_tokens", Math.max(0, promptTokens + completionTokens)
        );
    }

    private boolean includeUsage(Map<String, Object> request) {
        Object streamOptions = request.get("stream_options");
        return streamOptions instanceof Map<?, ?> map
                && booleanValue(map.get("include_usage"), false);
    }

    private void sendSse(SseEmitter emitter, Object data) throws IOException {
        emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
    }

    private void sendResponseEvent(
            SseEmitter emitter,
            String eventName,
            Object data
    ) throws IOException {
        emitter.send(
                SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON)
        );
    }

    private void sendOpenAiStreamError(SseEmitter emitter, Throwable error) {
        try {
            sendSse(emitter, Map.of(
                    "error", Map.of(
                            "message",
                            error.getMessage() == null ? "generation failed" : error.getMessage(),
                            "type", "server_error"
                    )
            ));
            emitter.complete();
        } catch (IOException sendError) {
            emitter.completeWithError(sendError);
        }
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

    private ResponseEntity<ResponseBodyEmitter> sseResponse(SseEmitter emitter) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
