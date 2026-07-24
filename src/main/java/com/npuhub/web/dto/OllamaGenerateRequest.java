package com.npuhub.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record OllamaGenerateRequest(
        @JsonProperty("model") String model,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("system") String system,
        @JsonProperty("template") String template,
        @JsonProperty("context") int[] context,
        @JsonProperty("stream") Boolean stream,
        @JsonProperty("raw") Boolean raw,
        @JsonProperty("options") Map<String, Object> options
) {}
