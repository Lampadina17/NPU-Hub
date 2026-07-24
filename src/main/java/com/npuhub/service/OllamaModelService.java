package com.npuhub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npuhub.core.model.BackendType;
import com.npuhub.core.model.ModelMetadata;
import com.npuhub.util.GgufMetadataReader;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OllamaModelService {
    private static final String LATEST_TAG = ":latest";

    private final ModelManagementService modelManagementService;
    private final ObjectMapper objectMapper;
    private final Map<String, ModelDefinition> aliases = new ConcurrentHashMap<>();

    @Value("${npu.ollama.aliases-file:.npuhub/ollama-models.json}")
    private String aliasesFile;

    public OllamaModelService(
            ModelManagementService modelManagementService,
            ObjectMapper objectMapper
    ) {
        this.modelManagementService = modelManagementService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadAliases() {
        Path file = Path.of(aliasesFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, ModelDefinition> saved = objectMapper.readValue(
                    file.toFile(),
                    new TypeReference<>() {
                    }
            );
            aliases.putAll(saved);
        } catch (IOException ignored) {
            // A damaged optional alias file must not prevent the inference server
            // from starting. A subsequent successful create/copy rewrites it.
        }
    }

    public List<ModelDescriptor> listLocalModels() {
        List<ModelDescriptor> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();

        for (ModelMetadata model : modelManagementService.listAllModels()) {
            if (model.compatibleBackend() == BackendType.ROCKCHIP) {
                List<String> quantizations = modelManagementService.getDownloadedQuantizations(model.id());
                if (quantizations.isEmpty()) {
                    continue;
                }

                String primary = quantizations.contains(model.quantization())
                        ? model.quantization()
                        : quantizations.get(0);
                addDescriptor(result, names, descriptor(model.id(), model, primary, null));
                for (String quantization : quantizations) {
                    if (!quantization.equals(primary)) {
                        addDescriptor(
                                result,
                                names,
                                descriptor(model.id() + ":" + quantization, model, quantization, null)
                        );
                    }
                }
            } else if (model.downloaded()) {
                addDescriptor(result, names, descriptor(model.id(), model, null, null));
            }
        }

        aliases.values().stream()
                .sorted(Comparator.comparing(ModelDefinition::model))
                .forEach(definition -> {
                    try {
                        ModelDescriptor base = resolve(definition.from());
                        addDescriptor(
                                result,
                                names,
                                new ModelDescriptor(
                                        definition.model(),
                                        base.baseModelId(),
                                        base.quantization(),
                                        base.file(),
                                        base.metadata(),
                                        definition
                                )
                        );
                    } catch (IllegalArgumentException ignored) {
                        // Do not advertise aliases whose source model was removed.
                    }
                });

        result.sort(Comparator.comparing(ModelDescriptor::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void addDescriptor(
            List<ModelDescriptor> descriptors,
            Set<String> names,
            ModelDescriptor descriptor
    ) {
        if (names.add(descriptor.name().toLowerCase(Locale.ROOT))) {
            descriptors.add(descriptor);
        }
    }

    public ModelDescriptor resolve(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }

        String requested = requestedName.trim();
        String aliasKey = findAliasKey(requested);
        if (aliasKey != null) {
            return resolveAlias(aliasKey, new LinkedHashSet<>());
        }

        String quantization = extractQuantization(requested);
        String baseName = stripKnownTag(requested);
        ModelMetadata model = modelManagementService.getModel(baseName)
                .orElseThrow(() -> new IllegalArgumentException("model '" + requestedName + "' not found"));
        if (model.compatibleBackend() == BackendType.ROCKCHIP) {
            List<String> downloaded = modelManagementService.getDownloadedQuantizations(model.id());
            if (downloaded.isEmpty()) {
                throw new IllegalArgumentException("model '" + requestedName + "' not found");
            }
            if (quantization == null) {
                quantization = downloaded.contains(model.quantization())
                        ? model.quantization()
                        : downloaded.get(0);
            } else if (!downloaded.contains(quantization)) {
                throw new IllegalArgumentException("model '" + requestedName + "' not found");
            }
        } else if (!model.downloaded()) {
            throw new IllegalArgumentException("model '" + requestedName + "' not found");
        }
        return descriptor(requested, model, quantization, null);
    }

    private ModelDescriptor resolveAlias(String aliasKey, Set<String> visited) {
        String normalized = aliasKey.toLowerCase(Locale.ROOT);
        if (!visited.add(normalized)) {
            throw new IllegalArgumentException("model alias cycle detected for '" + aliasKey + "'");
        }

        ModelDefinition definition = aliases.get(aliasKey);
        if (definition == null) {
            throw new IllegalArgumentException("model '" + aliasKey + "' not found");
        }

        String nestedAlias = findAliasKey(definition.from());
        ModelDescriptor base;
        if (nestedAlias != null) {
            base = resolveAlias(nestedAlias, visited);
        } else {
            base = resolve(definition.from());
        }
        return new ModelDescriptor(
                definition.model(),
                base.baseModelId(),
                base.quantization(),
                base.file(),
                base.metadata(),
                definition
        );
    }

    private ModelDescriptor descriptor(
            String visibleName,
            ModelMetadata model,
            String requestedQuantization,
            ModelDefinition definition
    ) {
        String quantization = requestedQuantization;
        if (model.compatibleBackend() == BackendType.ROCKCHIP
                && (quantization == null || quantization.isBlank())) {
            quantization = model.quantization();
        }
        File file = modelManagementService.resolveLocalModelFile(model.id(), quantization);
        return new ModelDescriptor(
                visibleName,
                model.id(),
                quantization,
                file,
                model,
                definition
        );
    }

    private String findAliasKey(String requestedName) {
        if (requestedName == null) {
            return null;
        }
        String normalized = requestedName.trim();
        for (String key : aliases.keySet()) {
            if (key.equalsIgnoreCase(normalized)
                    || (normalized.endsWith(LATEST_TAG)
                    && key.equalsIgnoreCase(normalized.substring(0, normalized.length() - LATEST_TAG.length())))
                    || (key.endsWith(LATEST_TAG)
                    && key.substring(0, key.length() - LATEST_TAG.length()).equalsIgnoreCase(normalized))) {
                return key;
            }
        }
        return null;
    }

    private String stripKnownTag(String modelName) {
        if (modelName.toLowerCase(Locale.ROOT).endsWith(LATEST_TAG)) {
            return modelName.substring(0, modelName.length() - LATEST_TAG.length());
        }
        String quantization = extractQuantization(modelName);
        if (quantization != null) {
            return modelName.substring(0, modelName.lastIndexOf(':'));
        }
        return modelName;
    }

    private String extractQuantization(String modelName) {
        int separator = modelName.lastIndexOf(':');
        if (separator <= modelName.lastIndexOf('/')) {
            return null;
        }
        String tag = modelName.substring(separator + 1).toUpperCase(Locale.ROOT);
        return modelManagementService.getRockchipQuantizations().contains(tag) ? tag : null;
    }

    public synchronized ModelDefinition create(
            String model,
            String from,
            String template,
            String renderer,
            String parser,
            Object license,
            String system,
            Map<String, Object> parameters,
            List<Map<String, Object>> messages,
            String quantize
    ) {
        requireValidNewName(model);
        ModelDescriptor source = resolve(from);
        ModelDefinition definition = new ModelDefinition(
                model.trim(),
                source.name(),
                nullToEmpty(template),
                nullToEmpty(renderer),
                nullToEmpty(parser),
                normalizeLicenses(license),
                nullToEmpty(system),
                parameters == null ? Map.of() : new LinkedHashMap<>(parameters),
                messages == null ? List.of() : List.copyOf(messages),
                nullToEmpty(quantize),
                System.currentTimeMillis()
        );
        aliases.put(definition.model(), definition);
        persistAliases();
        return definition;
    }

    public synchronized ModelDefinition copy(String source, String destination) {
        requireValidNewName(destination);
        ModelDescriptor sourceDescriptor = resolve(source);
        ModelDefinition sourceDefinition = sourceDescriptor.definition();
        ModelDefinition copied = sourceDefinition == null
                ? new ModelDefinition(
                        destination.trim(),
                        sourceDescriptor.name(),
                        "",
                        "",
                        "",
                        List.of(),
                        "",
                        Map.of(),
                        List.of(),
                        "",
                        System.currentTimeMillis()
                )
                : new ModelDefinition(
                        destination.trim(),
                        sourceDefinition.from(),
                        sourceDefinition.template(),
                        sourceDefinition.renderer(),
                        sourceDefinition.parser(),
                        sourceDefinition.license(),
                        sourceDefinition.system(),
                        sourceDefinition.parameters(),
                        sourceDefinition.messages(),
                        sourceDefinition.quantize(),
                        System.currentTimeMillis()
                );
        aliases.put(copied.model(), copied);
        persistAliases();
        return copied;
    }

    public synchronized boolean delete(String modelName) {
        String aliasKey = findAliasKey(modelName);
        if (aliasKey != null) {
            aliases.remove(aliasKey);
            persistAliases();
            return true;
        }

        ModelDescriptor descriptor = resolve(modelName);
        return modelManagementService.deleteModel(
                descriptor.baseModelId(),
                descriptor.quantization()
        );
    }

    private void requireValidNewName(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (model.contains(" ") || model.startsWith(":") || model.endsWith(":")) {
            throw new IllegalArgumentException("invalid model name '" + model + "'");
        }
    }

    private List<String> normalizeLicenses(Object license) {
        if (license == null) {
            return List.of();
        }
        if (license instanceof String value) {
            return value.isBlank() ? List.of() : List.of(value);
        }
        if (license instanceof List<?> values) {
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                if (value != null && !value.toString().isBlank()) {
                    result.add(value.toString());
                }
            }
            return List.copyOf(result);
        }
        throw new IllegalArgumentException("license must be a string or an array of strings");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void persistAliases() {
        Path target = Path.of(aliasesFile).toAbsolutePath().normalize();
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), aliases);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("failed to persist Ollama model aliases: " + error.getMessage(), error);
        }
    }

    public Map<String, Object> details(ModelDescriptor descriptor) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parent_model", descriptor.definition() == null ? "" : descriptor.definition().from());
        details.put("format", isGguf(descriptor.file()) ? "gguf" : "npu-native");
        String family = descriptor.metadata().architecture() == null
                ? "unknown"
                : descriptor.metadata().architecture().toLowerCase(Locale.ROOT);
        details.put("family", family);
        details.put("families", List.of(family));
        details.put("parameter_size", formatParameters(descriptor.metadata().parameterCount()));
        details.put("quantization_level", descriptor.quantization() == null ? "" : descriptor.quantization());
        return details;
    }

    public Map<String, Object> tag(ModelDescriptor descriptor) {
        Map<String, Object> model = new LinkedHashMap<>();
        Integer modelContextLength = descriptor.metadata().contextWindow();
        model.put("name", descriptor.name());
        model.put("model", descriptor.name());
        model.put("modified_at", Instant.ofEpochMilli(lastModified(descriptor)).toString());
        model.put("size", size(descriptor.file()));
        model.put("digest", digest(descriptor));
        model.put("details", details(descriptor));
        if (modelContextLength != null && modelContextLength > 0) {
            model.put("context_length", modelContextLength);
            model.put("model_context_length", modelContextLength);
            model.put("max_output_tokens", Math.min(32_768, Math.max(1, modelContextLength - 256)));
        }
        return model;
    }

    public Map<String, Object> show(ModelDescriptor descriptor, boolean verbose) {
        ModelDefinition definition = descriptor.definition();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(
                "modelfile",
                "FROM " + (definition == null ? descriptor.file().getAbsolutePath() : definition.from())
        );
        response.put("parameters", parameterText(definition));
        response.put("template", definition == null ? "" : definition.template());
        response.put("system", definition == null ? "" : definition.system());
        response.put(
                "license",
                definition == null ? "" : String.join("\n", definition.license())
        );
        response.put("details", details(descriptor));
        response.put(
                "model_info",
                isGguf(descriptor.file())
                        ? GgufMetadataReader.readMetadata(descriptor.file(), verbose).orElseGet(Map::of)
                        : Map.of(
                                "general.architecture",
                                descriptor.metadata().architecture() == null
                                        ? "unknown"
                                        : descriptor.metadata().architecture(),
                                "general.parameter_count",
                                descriptor.metadata().parameterCount() == null
                                        ? 0L
                                        : descriptor.metadata().parameterCount()
                        )
        );
        response.put("capabilities", List.of("completion"));
        response.put("modified_at", Instant.ofEpochMilli(lastModified(descriptor)).toString());
        return response;
    }

    private String parameterText(ModelDefinition definition) {
        if (definition == null || definition.parameters().isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        definition.parameters().forEach((key, value) ->
                result.append(key).append(' ').append(value).append('\n'));
        return result.toString().stripTrailing();
    }

    private boolean isGguf(File file) {
        return file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".gguf");
    }

    private long lastModified(ModelDescriptor descriptor) {
        if (descriptor.definition() != null) {
            return descriptor.definition().createdAtEpochMs();
        }
        return Math.max(0L, descriptor.file().lastModified());
    }

    public long size(File file) {
        if (file.isFile()) {
            return file.length();
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0L;
        }
        long total = 0L;
        for (File child : children) {
            total += size(child);
        }
        return total;
    }

    private String digest(ModelDescriptor descriptor) {
        String identity = descriptor.file().getAbsolutePath()
                + '\n' + size(descriptor.file())
                + '\n' + descriptor.file().lastModified();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte current : digest) {
                value.append(String.format("%02x", current));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String formatParameters(Long parameterCount) {
        if (parameterCount == null || parameterCount <= 0) {
            return "";
        }
        double billions = parameterCount / 1_000_000_000.0;
        if (billions >= 1.0) {
            return String.format(Locale.ROOT, "%.1fB", billions);
        }
        return String.format(Locale.ROOT, "%.1fM", parameterCount / 1_000_000.0);
    }

    public Map<String, Object> mergedParameters(ModelDescriptor descriptor) {
        return descriptor.definition() == null
                ? Map.of()
                : Collections.unmodifiableMap(descriptor.definition().parameters());
    }

    public record ModelDescriptor(
            String name,
            String baseModelId,
            String quantization,
            File file,
            ModelMetadata metadata,
            ModelDefinition definition
    ) {
    }

    public record ModelDefinition(
            String model,
            String from,
            String template,
            String renderer,
            String parser,
            List<String> license,
            String system,
            Map<String, Object> parameters,
            List<Map<String, Object>> messages,
            String quantize,
            long createdAtEpochMs
    ) {
        public ModelDefinition {
            license = license == null ? List.of() : List.copyOf(license);
            parameters = parameters == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
