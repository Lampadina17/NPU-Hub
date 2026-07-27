package com.npuhub.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OllamaGenerateResponse(
        @JsonProperty("model") String model,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("response") String response,
        @JsonProperty("done") boolean done,
        @JsonProperty("total_duration") Long totalDurationNs,
        @JsonProperty("load_duration") Long loadDurationNs,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount,
        @JsonProperty("eval_count") Integer evalCount,
        @JsonProperty("eval_duration") Long evalDurationNs
) {
}
