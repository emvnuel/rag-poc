package br.edu.ifba.lightrag.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores LLM extraction results for rebuild capability.
 * Enables knowledge graph reconstruction without re-calling the LLM.
 */
public record ExtractionCache(
    /**
     * Primary key (UUID v7).
     */
    @NotNull UUID id,
    
    /**
     * FK to project (cascade delete).
     */
    @NotNull UUID projectId,
    
    /**
     * Type of cached result.
     */
    @NotNull CacheType cacheType,
    
    /**
     * FK to source chunk (SET NULL on delete).
     */
    @Nullable UUID chunkId,
    
    /**
     * SHA-256 of input content for cache invalidation.
     */
    @NotNull String contentHash,
    
    /**
     * Raw LLM response text.
     */
    @NotNull String result,
    
    /**
     * LLM tokens consumed (nullable).
     */
    @Nullable Integer tokensUsed,
    
    /**
     * Creation timestamp.
     */
    @NotNull Instant createdAt
) {
    public ExtractionCache {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(cacheType, "cacheType must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
    
    /**
     * Builder for ExtractionCache instances.
     */
    public static class Builder {
        private UUID id;
        private UUID projectId;
        private CacheType cacheType;
        private UUID chunkId;
        private String contentHash;
        private String result;
        private Integer tokensUsed;
        private Instant createdAt;
        
        public Builder id(@NotNull UUID id) {
            this.id = id;
            return this;
        }
        
        public Builder projectId(@NotNull UUID projectId) {
            this.projectId = projectId;
            return this;
        }
        
        public Builder cacheType(@NotNull CacheType cacheType) {
            this.cacheType = cacheType;
            return this;
        }
        
        public Builder chunkId(@Nullable UUID chunkId) {
            this.chunkId = chunkId;
            return this;
        }
        
        public Builder contentHash(@NotNull String contentHash) {
            this.contentHash = contentHash;
            return this;
        }
        
        public Builder result(@NotNull String result) {
            this.result = result;
            return this;
        }
        
        public Builder tokensUsed(@Nullable Integer tokensUsed) {
            this.tokensUsed = tokensUsed;
            return this;
        }
        
        public Builder createdAt(@NotNull Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }
        
        public ExtractionCache build() {
            return new ExtractionCache(
                id, projectId, cacheType, chunkId, 
                contentHash, result, tokensUsed, createdAt
            );
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
