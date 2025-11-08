package br.edu.ifba.lightrag.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents a text chunk from a document.
 * Chunks are segments of text split from larger documents for processing.
 */
public final class Chunk {
    
    @JsonProperty("content")
    @NotNull
    private final String content;
    
    @JsonProperty("source_id")
    @NotNull
    private final String sourceId;
    
    @JsonProperty("file_path")
    @Nullable
    private final String filePath;
    
    @JsonProperty("chunk_id")
    @NotNull
    private final String chunkId;
    
    @JsonProperty("tokens")
    private final int tokens;
    
    /**
     * Constructs a new Chunk.
     *
     * @param content the text content of the chunk (required)
     * @param sourceId the ID of the source document (required)
     * @param filePath the file path of the source document (optional)
     * @param chunkId the unique identifier for this chunk (required)
     * @param tokens the number of tokens in this chunk
     */
    public Chunk(
            @NotNull String content,
            @NotNull String sourceId,
            @Nullable String filePath,
            @NotNull String chunkId,
            int tokens) {
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId must not be null");
        this.filePath = filePath;
        this.chunkId = Objects.requireNonNull(chunkId, "chunkId must not be null");
        this.tokens = tokens;
    }
    
    @NotNull
    public String getContent() {
        return content;
    }
    
    @NotNull
    public String getSourceId() {
        return sourceId;
    }
    
    @Nullable
    public String getFilePath() {
        return filePath;
    }
    
    @NotNull
    public String getChunkId() {
        return chunkId;
    }
    
    public int getTokens() {
        return tokens;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Chunk chunk = (Chunk) obj;
        return tokens == chunk.tokens &&
               Objects.equals(content, chunk.content) &&
               Objects.equals(sourceId, chunk.sourceId) &&
               Objects.equals(filePath, chunk.filePath) &&
               Objects.equals(chunkId, chunk.chunkId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(content, sourceId, filePath, chunkId, tokens);
    }
    
    @Override
    public String toString() {
        return "Chunk{" +
                "chunkId='" + chunkId + '\'' +
                ", sourceId='" + sourceId + '\'' +
                ", filePath='" + filePath + '\'' +
                ", tokens=" + tokens +
                ", contentLength=" + content.length() +
                '}';
    }
    
    /**
     * Builder for Chunk instances.
     */
    public static class Builder {
        private String content;
        private String sourceId;
        private String filePath;
        private String chunkId;
        private int tokens;
        
        public Builder content(@NotNull String content) {
            this.content = content;
            return this;
        }
        
        public Builder sourceId(@NotNull String sourceId) {
            this.sourceId = sourceId;
            return this;
        }
        
        public Builder filePath(@Nullable String filePath) {
            this.filePath = filePath;
            return this;
        }
        
        public Builder chunkId(@NotNull String chunkId) {
            this.chunkId = chunkId;
            return this;
        }
        
        public Builder tokens(int tokens) {
            this.tokens = tokens;
            return this;
        }
        
        public Chunk build() {
            return new Chunk(content, sourceId, filePath, chunkId, tokens);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
