package com.npuhub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npuhub.core.model.InferenceRequest;
import com.npuhub.core.model.InferenceResponse;
import com.npuhub.core.model.TokenChunk;
import com.npuhub.service.OllamaModelService.ModelDefinition;
import com.npuhub.service.OllamaModelService.ModelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class OllamaInferenceFacade {
    private static final Logger log = LoggerFactory.getLogger(OllamaInferenceFacade.class);
    private final OllamaModelService modelService;
    private final ModelManagementService modelManagementService;
    private final InferenceService inferenceService;
    private final ObjectMapper objectMapper;

    @Value("${npu.ollama.default-context:4096}")
    private int defaultContextWindow;

    @Value("${npu.ollama.default-max-tokens:512}")
    private int defaultMaxTokens;

    @Value("${npu.ollama.default-keep-alive:5m}")
    private String defaultKeepAlive;

    public OllamaInferenceFacade(
            OllamaModelService modelService,
            ModelManagementService modelManagementService,
            InferenceService inferenceService,
            ObjectMapper objectMapper
    ) {
        this.modelService = modelService;
        this.modelManagementService = modelManagementService;
        this.inferenceService = inferenceService;
        this.objectMapper = objectMapper;
    }

    public PreparedGeneration prepareGenerate(Map<String, Object> body) {
        String model = requiredString(body, "model");
        ModelDescriptor descriptor = modelService.resolve(model);
        Map<String, Object> options = mergedOptions(descriptor, asMap(body.get("options")));

        boolean raw = asBoolean(body.get("raw"), false);
        String prompt = stringValue(body.get("prompt"));
        String system = firstNonBlank(
                stringValue(body.get("system")),
                descriptor.definition() == null ? "" : descriptor.definition().system()
        );
        String template = firstNonBlank(
                stringValue(body.get("template")),
                descriptor.definition() == null ? "" : descriptor.definition().template()
        );

        if (raw && (!system.isBlank() || !template.isBlank() || body.get("context") != null)) {
            throw new IllegalArgumentException("raw mode does not support template, system, or context");
        }
        rejectImages(body.get("images"));

        String renderedPrompt = raw
                ? prompt
                : renderGeneratePrompt(descriptor, system, template, prompt, stringValue(body.get("suffix")));
        renderedPrompt = applyStructuredOutputInstruction(renderedPrompt, body.get("format"));
        return prepare(model, descriptor, renderedPrompt, options, body.get("keep_alive"));
    }

    public PreparedGeneration prepareChat(Map<String, Object> body) {
        String model = requiredString(body, "model");
        ModelDescriptor descriptor = modelService.resolve(model);
        Map<String, Object> options = mergedOptions(descriptor, asMap(body.get("options")));
        List<Map<String, Object>> messages = asMessageList(body.get("messages"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }

        List<Map<String, Object>> allMessages = new ArrayList<>();
        ModelDefinition definition = descriptor.definition();
        if (definition != null) {
            if (!definition.system().isBlank()
                    && messages.stream().noneMatch(message ->
                    "system".equalsIgnoreCase(stringValue(message.get("role"))))) {
                allMessages.add(Map.of("role", "system", "content", definition.system()));
            }
            allMessages.addAll(definition.messages());
        }
        allMessages.addAll(messages);

        Object tools = body.get("tools");
        String prompt = renderCompactedChatPrompt(
                descriptor,
                allMessages,
                tools,
                options
        );
        prompt = applyStructuredOutputInstruction(prompt, body.get("format"));
        return prepare(model, descriptor, prompt, options, body.get("keep_alive"));
    }

    private PreparedGeneration prepare(
            String requestedModel,
            ModelDescriptor descriptor,
            String prompt,
            Map<String, Object> options,
            Object keepAlive
    ) {
        int contextWindow = integerOption(options, "num_ctx", defaultContextWindow);
        int maxTokens = integerOption(options, "num_predict", defaultMaxTokens);
        if (maxTokens <= 0) {
            maxTokens = defaultMaxTokens;
        }
        maxTokens = Math.min(maxTokens, 32_768);
        double temperature = doubleOption(options, "temperature", 0.8);
        double topP = doubleOption(options, "top_p", 0.9);
        int topK = integerOption(options, "top_k", 40);
        double minP = doubleOption(options, "min_p", 0.0);
        long seed = longOption(options, "seed", -1L);
        int repeatLastN = integerOption(options, "repeat_last_n", 64);
        double repeatPenalty = doubleOption(options, "repeat_penalty", 1.1);
        double frequencyPenalty = doubleOption(options, "frequency_penalty", 0.0);
        double presencePenalty = doubleOption(options, "presence_penalty", 0.0);
        long keepAliveMs = parseKeepAliveMs(keepAlive);

        Optional<ModelManagementService.LoadedModelState> loadedState =
                modelManagementService.getLoadedModelState();
        if (loadedState.isEmpty()) {
            throw new IllegalStateException(
                    "No model is loaded. Load a model from the Models page before chatting."
            );
        }

        ModelManagementService.LoadedModelState state = loadedState.get();
        boolean sameModel = descriptor.baseModelId().equals(state.modelId())
                && java.util.Objects.equals(descriptor.quantization(), state.quantization())
                && descriptor.metadata().compatibleBackend() == state.backend();
        if (!sameModel) {
            throw new IllegalStateException(
                    "Model '" + requestedModel + "' is not loaded. Unload the active model, "
                            + "then load this model from the Models page."
            );
        }
        if (contextWindow > state.contextWindow()) {
            throw new IllegalStateException(
                    "Requested context " + contextWindow + " exceeds the loaded context "
                            + state.contextWindow() + ". Reload the model with a larger context."
            );
        }

        // Loading is an explicit control-plane action. Inference must never
        // replace or unload the manually selected model.
        long loadDuration = 0L;
        InferenceRequest request = new InferenceRequest(
                UUID.randomUUID().toString(),
                requestedModel,
                prompt,
                List.of(),
                temperature,
                topP,
                maxTokens,
                topK,
                minP,
                seed,
                repeatLastN,
                repeatPenalty,
                frequencyPenalty,
                presencePenalty,
                true
        );
        return new PreparedGeneration(
                requestedModel,
                descriptor,
                request,
                loadDuration,
                keepAliveMs,
                stringList(options.get("stop"))
        );
    }

    public InferenceResponse generate(PreparedGeneration prepared) {
        try {
            InferenceResponse response = inferenceService.processInference(
                    prepared.inferenceRequest(),
                    prepared.descriptor().metadata().compatibleBackend().name()
            );
            String stopped = applyStop(response.text(), prepared.stop());
            if (!stopped.equals(response.text())) {
                response = new InferenceResponse(
                        response.requestId(),
                        response.modelName(),
                        stopped,
                        response.promptTokens(),
                        response.completionTokens(),
                        response.tokensPerSecond(),
                        response.timeToFirstTokenMs(),
                        response.totalExecutionTimeMs(),
                        response.backendUsed()
                );
            }
            return response;
        } finally {
            finish(prepared);
        }
    }

    public void generateStream(
            PreparedGeneration prepared,
            Consumer<TokenChunk> tokenConsumer,
            Consumer<Throwable> errorConsumer
    ) {
        StringBuilder emitted = new StringBuilder();
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicBoolean finalSent = new AtomicBoolean(false);
        inferenceService.processInferenceStream(
                prepared.inferenceRequest(),
                prepared.descriptor().metadata().compatibleBackend().name(),
                chunk -> {
                    if (stopped.get()) {
                        return;
                    }
                    if (chunk.done()) {
                        finalSent.set(true);
                        tokenConsumer.accept(chunk);
                        return;
                    }
                    String token = chunk.token();
                    if (!prepared.stop().isEmpty()) {
                        String candidate = emitted + token;
                        String cropped = applyStop(candidate, prepared.stop());
                        if (cropped.length() < candidate.length()) {
                            String visible = cropped.substring(Math.min(emitted.length(), cropped.length()));
                            if (!visible.isEmpty()) {
                                tokenConsumer.accept(new TokenChunk(
                                        chunk.requestId(),
                                        visible,
                                        false,
                                        chunk.currentTokensPerSecond()
                                ));
                            }
                            stopped.set(true);
                            finalSent.set(true);
                            tokenConsumer.accept(new TokenChunk(
                                    chunk.requestId(),
                                    "",
                                    true,
                                    chunk.currentTokensPerSecond()
                            ));
                            return;
                        }
                    }
                    emitted.append(token);
                    tokenConsumer.accept(chunk);
                },
                error -> {
                    finish(prepared);
                    errorConsumer.accept(error);
                },
                () -> {
                    if (finalSent.compareAndSet(false, true)) {
                        tokenConsumer.accept(new TokenChunk(
                                prepared.inferenceRequest().requestId(),
                                "",
                                true,
                                0.0
                        ));
                    }
                    finish(prepared);
                }
        );
    }

    public void finish(PreparedGeneration prepared) {
        // Models are managed explicitly from the control panel. Ollama's
        // keep_alive is intentionally ignored so a chat request cannot unload
        // or replace the model selected by the user.
    }

    public long parseKeepAliveMs(Object value) {
        if (value == null) {
            value = defaultKeepAlive;
        }
        if (value instanceof Number number) {
            double seconds = number.doubleValue();
            if (seconds < 0) {
                return -1L;
            }
            return Math.round(seconds * 1000.0);
        }

        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            text = defaultKeepAlive;
        }
        if ("-1".equals(text) || "infinite".equals(text)) {
            return -1L;
        }

        try {
            if (text.endsWith("ms")) {
                return Math.max(0L, Math.round(Double.parseDouble(
                        text.substring(0, text.length() - 2)))); 
            }
            if (text.endsWith("h")) {
                return durationValue(text, 3_600_000.0);
            }
            if (text.endsWith("m")) {
                return durationValue(text, 60_000.0);
            }
            if (text.endsWith("s")) {
                return durationValue(text, 1_000.0);
            }
            return Math.max(0L, Math.round(Double.parseDouble(text) * 1000.0));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid keep_alive duration '" + value + "'");
        }
    }

    private long durationValue(String text, double multiplier) {
        double amount = Double.parseDouble(text.substring(0, text.length() - 1));
        if (amount < 0) {
            return -1L;
        }
        return Math.round(amount * multiplier);
    }

    private Map<String, Object> mergedOptions(
            ModelDescriptor descriptor,
            Map<String, Object> requestOptions
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(modelService.mergedParameters(descriptor));
        merged.putAll(requestOptions);
        return merged;
    }

    private String renderGeneratePrompt(
            ModelDescriptor descriptor,
            String system,
            String template,
            String prompt,
            String suffix
    ) {
        if (!template.isBlank()) {
            return template
                    .replace("{{ .System }}", system)
                    .replace("{{.System}}", system)
                    .replace("{{ .Prompt }}", prompt)
                    .replace("{{.Prompt}}", prompt)
                    .replace("{{ .Suffix }}", suffix)
                    .replace("{{.Suffix}}", suffix)
                    .replace("{{ .Response }}", "")
                    .replace("{{.Response}}", "");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        messages.add(Map.of("role", "user", "content", prompt + suffix));
        return renderChatPrompt(descriptor, messages, null);
    }

    private String renderChatPrompt(
            ModelDescriptor descriptor,
            List<Map<String, Object>> messages,
            Object tools
    ) {
        String architecture = descriptor.metadata().architecture() == null
                ? ""
                : descriptor.metadata().architecture().toLowerCase(Locale.ROOT);
        StringBuilder prompt = new StringBuilder();

        if (tools != null) {
            prompt.append("SYSTEM: Available tools (return a JSON tool call when needed): ")
                    .append(toJson(tools))
                    .append("\n");
        }

        if (architecture.contains("phi")) {
            for (Map<String, Object> message : messages) {
                rejectImages(message.get("images"));
                prompt.append("<|")
                        .append(normalizedRole(message.get("role")))
                        .append("|>")
                        .append(contentText(message.get("content")))
                        .append("<|end|>");
            }
            return prompt.append("<|assistant|>").toString();
        }

        if (architecture.contains("gemma")) {
            for (Map<String, Object> message : messages) {
                rejectImages(message.get("images"));
                String role = normalizedRole(message.get("role"));
                if ("system".equals(role)) {
                    role = "user";
                } else if ("assistant".equals(role)) {
                    role = "model";
                }
                prompt.append("<start_of_turn>")
                        .append(role)
                        .append('\n')
                        .append(contentText(message.get("content")))
                        .append("<end_of_turn>\n");
            }
            return prompt.append("<start_of_turn>model\n").toString();
        }

        for (Map<String, Object> message : messages) {
            rejectImages(message.get("images"));
            prompt.append(normalizedRole(message.get("role")).toUpperCase(Locale.ROOT))
                    .append(": ")
                    .append(contentText(message.get("content")))
                    .append('\n');
        }
        return prompt.append("ASSISTANT: ").toString();
    }

    private String renderCompactedChatPrompt(
            ModelDescriptor descriptor,
            List<Map<String, Object>> messages,
            Object tools,
            Map<String, Object> options
    ) {
        int contextWindow = Math.max(
                512,
                integerOption(options, "num_ctx", defaultContextWindow)
        );
        int maxTokens = integerOption(options, "num_predict", defaultMaxTokens);
        if (maxTokens <= 0) {
            maxTokens = defaultMaxTokens;
        }

        int outputReserve = Math.min(maxTokens, Math.max(1, contextWindow - 256));
        int inputBudget = Math.max(256, contextWindow - outputReserve - 64);
        List<Map<String, Object>> compacted = new ArrayList<>(messages);
        String prompt = renderChatPrompt(descriptor, compacted, tools);
        int originalMessages = compacted.size();
        int originalEstimate = estimatePromptTokens(prompt);

        while (estimatePromptTokens(prompt) > inputBudget) {
            int start = oldestDroppableTurn(compacted);
            if (start < 0) {
                break;
            }
            int end = endOfDroppableTurn(compacted, start);
            compacted.subList(start, end).clear();
            prompt = renderChatPrompt(descriptor, compacted, tools);
        }

        if (compacted.size() < originalMessages) {
            log.warn(
                    "Compacted chat history from {} to {} messages for num_ctx={} "
                            + "(estimated prompt {} -> {} tokens)",
                    originalMessages,
                    compacted.size(),
                    contextWindow,
                    originalEstimate,
                    estimatePromptTokens(prompt)
            );
        }
        return prompt;
    }

    private int oldestDroppableTurn(List<Map<String, Object>> messages) {
        int latestConversationMessage = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (!"system".equals(normalizedRole(messages.get(index).get("role")))) {
                latestConversationMessage = index;
                break;
            }
        }
        if (latestConversationMessage <= 0) {
            return -1;
        }

        for (int index = 0; index < latestConversationMessage; index++) {
            if (!"system".equals(normalizedRole(messages.get(index).get("role")))) {
                return index;
            }
        }
        return -1;
    }

    private int endOfDroppableTurn(
            List<Map<String, Object>> messages,
            int start
    ) {
        int latestConversationMessage = messages.size() - 1;
        while (latestConversationMessage > start
                && "system".equals(normalizedRole(
                messages.get(latestConversationMessage).get("role")))) {
            latestConversationMessage--;
        }

        int end = start + 1;
        if (!"user".equals(normalizedRole(messages.get(start).get("role")))) {
            return end;
        }
        while (end < latestConversationMessage) {
            String role = normalizedRole(messages.get(end).get("role"));
            if ("system".equals(role) || "user".equals(role)) {
                break;
            }
            end++;
        }
        return end;
    }

    private int estimatePromptTokens(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return 0;
        }
        int utf8Bytes = prompt.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, (utf8Bytes + 2) / 3);
    }

    private String applyStructuredOutputInstruction(String prompt, Object format) {
        if (format == null) {
            return prompt;
        }
        if (format instanceof String text && text.isBlank()) {
            return prompt;
        }
        if (format instanceof String text && "json".equalsIgnoreCase(text)) {
            return prompt + "\nReturn only a valid JSON object, without Markdown fences.";
        }
        if (format instanceof Map<?, ?>) {
            return prompt + "\nReturn only JSON matching this JSON Schema:\n" + toJson(format);
        }
        throw new IllegalArgumentException("format must be 'json' or a JSON schema object");
    }

    private String normalizedRole(Object roleValue) {
        String role = stringValue(roleValue).toLowerCase(Locale.ROOT);
        return switch (role) {
            case "system", "user", "assistant", "tool" -> role;
            default -> throw new IllegalArgumentException("invalid message role '" + role + "'");
        };
    }

    private String contentText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder result = new StringBuilder();
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> map)) {
                    continue;
                }
                String type = stringValue(map.get("type"));
                if ("text".equals(type) || "input_text".equals(type)) {
                    result.append(stringValue(map.get("text")));
                } else if (type.contains("image")) {
                    throw new IllegalArgumentException(
                            "the selected NPU runtime does not support image input"
                    );
                }
            }
            return result.toString();
        }
        return content.toString();
    }

    private void rejectImages(Object images) {
        if (images instanceof List<?> list && !list.isEmpty()) {
            throw new IllegalArgumentException(
                    "the selected NPU runtime does not support image input"
            );
        }
    }

    private String applyStop(String value, List<String> stops) {
        int first = value.length();
        for (String stop : stops) {
            if (stop == null || stop.isEmpty()) {
                continue;
            }
            int index = value.indexOf(stop);
            if (index >= 0) {
                first = Math.min(first, index);
            }
        }
        return value.substring(0, first);
    }

    private String requiredString(Map<String, Object> body, String key) {
        String value = stringValue(body.get(key));
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("options must be an object");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMessageList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object current : values) {
            if (!(current instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("messages must contain objects");
            }
            result.add((Map<String, Object>) current);
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String text) {
            return List.of(text);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("stop must be a string or an array of strings");
    }

    private int integerOption(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private double doubleOption(Map<String, Object> options, String key, double fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
    }

    private long longOption(Map<String, Object> options, String key, long fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? stringValue(second) : first;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("invalid JSON value: " + error.getMessage(), error);
        }
    }

    public record PreparedGeneration(
            String requestedModel,
            ModelDescriptor descriptor,
            InferenceRequest inferenceRequest,
            long loadDurationNs,
            long keepAliveMs,
            List<String> stop
    ) {
    }
}
