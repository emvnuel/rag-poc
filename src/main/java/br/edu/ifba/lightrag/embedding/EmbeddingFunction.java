package br.edu.ifba.lightrag.embedding;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Functional interface for text-to-vector embedding.
 * Implementations should handle API calls to embedding providers (OpenAI, Sentence Transformers, etc.).
 */
@FunctionalInterface
public interface EmbeddingFunction {
    
    /**
     * Generate embeddings for a batch of texts.
     *
     * @param texts List of texts to embed
     * @return CompletableFuture with list of embedding vectors (one per input text)
     */
    CompletableFuture<List<float[]>> embed(@NotNull List<String> texts);
    
    /**
     * Convenience method for embedding a single text.
     */
    default CompletableFuture<float[]> embedSingle(@NotNull String text) {
        return embed(List.of(text)).thenApply(embeddings -> embeddings.get(0));
    }
    
    /**
     * Response metadata including token usage and model information.
     */
    record EmbeddingMetadata(
        int totalTokens,
        @NotNull String model,
        int dimensions
    ) {}
}
