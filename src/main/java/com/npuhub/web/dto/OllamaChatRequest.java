package com.npuhub.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record OllamaChatRequest(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<OllamaChatMessage> messages,
        @JsonProperty("stream") Boolean stream,
        @JsonProperty("options") Map<String, Object> options
) {
}
