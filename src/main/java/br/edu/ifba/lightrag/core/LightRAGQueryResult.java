package br.edu.ifba.lightrag.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Result of a LightRAG query containing both the synthesized answer and source chunks.
 * This allows clients to display both the AI-generated response and the source materials
 * used to generate that response, enabling proper citations and transparency.
 */
public record LightRAGQueryResult(
        @NotNull String answer,
        @NotNull List<SourceChunk> sourceChunks,
        @NotNull QueryParam.Mode mode,
        int totalSources
) {
    public LightRAGQueryResult {
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(sourceChunks, "sourceChunks must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
    }

    /**
     * Represents a source chunk or entity used to generate the answer.
     * Contains all metadata needed for proper citation and source tracking.
     */
    public record SourceChunk(
            @NotNull String chunkId,
            @NotNull String content,
            double relevanceScore,
            @Nullable String documentId,
            @NotNull String sourceId,
            int chunkIndex,
            @NotNull String type
    ) {
        public SourceChunk {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }
    }
}
