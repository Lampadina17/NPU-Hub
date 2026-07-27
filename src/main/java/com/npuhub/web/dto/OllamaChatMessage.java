package com.npuhub.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record OllamaChatMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("images") List<String> images
) {
}
