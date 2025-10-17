package br.edu.ifba;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EmbeddingResponse(
        String model,
        List<List<Double>> embeddings,
        @JsonProperty("total_duration") Long totalDuration,
        @JsonProperty("load_duration") Long loadDuration,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount) {
}
