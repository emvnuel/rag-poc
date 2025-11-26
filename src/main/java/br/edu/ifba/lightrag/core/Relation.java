package br.edu.ifba.lightrag.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a relationship between two entities in the knowledge graph.
 * Relations are directed edges that connect entities with semantic meaning.
 */
public final class Relation {
    
    /**
     * Maximum number of source chunk IDs to store per relation.
     * Uses FIFO eviction when exceeded.
     */
    public static final int MAX_SOURCE_CHUNK_IDS = 50;
    
    @JsonProperty("src_id")
    @NotNull
    private final String srcId;
    
    @JsonProperty("tgt_id")
    @NotNull
    private final String tgtId;
    
    @JsonProperty("description")
    @NotNull
    private final String description;
    
    @JsonProperty("keywords")
    @NotNull
    private final String keywords;
    
    @JsonProperty("weight")
    private final double weight;
    
    @JsonProperty("file_path")
    @Nullable
    private final String filePath;
    
    @JsonProperty("document_id")
    @Nullable
    private final String documentId;
    
    @JsonProperty("source_chunk_ids")
    @NotNull
    private final List<String> sourceChunkIds;
    
    /**
     * Constructs a new Relation (backward compatible constructor).
     *
     * @param srcId the source entity ID (required)
     * @param tgtId the target entity ID (required)
     * @param description detailed description of the relationship (required)
     * @param keywords keywords describing the relationship (required)
     * @param weight the strength/importance of the relationship (default: 1.0)
     * @param filePath the file path of the source document (optional)
     */
    public Relation(
            @NotNull String srcId,
            @NotNull String tgtId,
            @NotNull String description,
            @NotNull String keywords,
            double weight,
            @Nullable String filePath) {
        this(srcId, tgtId, description, keywords, weight, filePath, null, Collections.emptyList());
    }
    
    /**
     * Constructs a new Relation (backward compatible constructor with documentId).
     *
     * @param srcId the source entity ID (required)
     * @param tgtId the target entity ID (required)
     * @param description detailed description of the relationship (required)
     * @param keywords keywords describing the relationship (required)
     * @param weight the strength/importance of the relationship (default: 1.0)
     * @param filePath the file path of the source document (optional)
     * @param documentId the document UUID that this relation was extracted from (optional)
     */
    public Relation(
            @NotNull String srcId,
            @NotNull String tgtId,
            @NotNull String description,
            @NotNull String keywords,
            double weight,
            @Nullable String filePath,
            @Nullable String documentId) {
        this(srcId, tgtId, description, keywords, weight, filePath, documentId, Collections.emptyList());
    }
    
    /**
     * Constructs a new Relation with all fields.
     *
     * @param srcId the source entity ID (required)
     * @param tgtId the target entity ID (required)
     * @param description detailed description of the relationship (required)
     * @param keywords keywords describing the relationship (required)
     * @param weight the strength/importance of the relationship (default: 1.0)
     * @param filePath the file path of the source document (optional)
     * @param documentId the document UUID that this relation was extracted from (optional)
     * @param sourceChunkIds UUIDs of chunks that contributed to this relation (optional)
     */
    public Relation(
            @NotNull String srcId,
            @NotNull String tgtId,
            @NotNull String description,
            @NotNull String keywords,
            double weight,
            @Nullable String filePath,
            @Nullable String documentId,
            @Nullable List<String> sourceChunkIds) {
        this.srcId = Objects.requireNonNull(srcId, "srcId must not be null");
        this.tgtId = Objects.requireNonNull(tgtId, "tgtId must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.keywords = Objects.requireNonNull(keywords, "keywords must not be null");
        this.weight = weight;
        this.filePath = filePath;
        this.documentId = documentId;
        this.sourceChunkIds = sourceChunkIds != null 
            ? Collections.unmodifiableList(new ArrayList<>(sourceChunkIds)) 
            : Collections.emptyList();
    }
    
    @NotNull
    public String getSrcId() {
        return srcId;
    }
    
    @NotNull
    public String getTgtId() {
        return tgtId;
    }
    
    @NotNull
    public String getDescription() {
        return description;
    }
    
    @NotNull
    public String getKeywords() {
        return keywords;
    }
    
    public double getWeight() {
        return weight;
    }
    
    @Nullable
    public String getFilePath() {
        return filePath;
    }
    
    @Nullable
    public String getDocumentId() {
        return documentId;
    }
    
    /**
     * Gets the list of source chunk IDs that contributed to this relation.
     *
     * @return unmodifiable list of chunk UUIDs
     */
    @NotNull
    public List<String> getSourceChunkIds() {
        return sourceChunkIds;
    }
    
    /**
     * Creates a new Relation with updated description.
     */
    public Relation withDescription(@NotNull String newDescription) {
        return new Relation(srcId, tgtId, newDescription, keywords, weight, filePath, documentId, sourceChunkIds);
    }
    
    /**
     * Creates a new Relation with updated weight.
     */
    public Relation withWeight(double newWeight) {
        return new Relation(srcId, tgtId, description, keywords, newWeight, filePath, documentId, sourceChunkIds);
    }
    
    /**
     * Creates a new Relation with updated source chunk IDs.
     *
     * @param newSourceChunkIds the new list of source chunk IDs
     * @return new Relation instance with updated sourceChunkIds
     */
    public Relation withSourceChunkIds(@NotNull List<String> newSourceChunkIds) {
        return new Relation(srcId, tgtId, description, keywords, weight, filePath, documentId, newSourceChunkIds);
    }
    
    /**
     * Creates a new Relation with an additional source chunk ID.
     * Uses FIFO eviction if the list exceeds MAX_SOURCE_CHUNK_IDS.
     *
     * @param chunkId the chunk ID to add
     * @return new Relation instance with the chunk ID added
     */
    public Relation addSourceChunkId(@NotNull String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        
        // Skip if already present
        if (sourceChunkIds.contains(chunkId)) {
            return this;
        }
        
        List<String> newList = new ArrayList<>(sourceChunkIds);
        newList.add(chunkId);
        
        // FIFO eviction if exceeds max
        while (newList.size() > MAX_SOURCE_CHUNK_IDS) {
            newList.remove(0);
        }
        
        return new Relation(srcId, tgtId, description, keywords, weight, filePath, documentId, newList);
    }
    
    /**
     * Gets the normalized relationship pair (sorted for consistent lock keys).
     * Critical pattern from Python: Sort relationship pairs to prevent deadlocks.
     */
    public RelationPair getNormalizedPair() {
        if (srcId.compareTo(tgtId) <= 0) {
            return new RelationPair(srcId, tgtId);
        } else {
            return new RelationPair(tgtId, srcId);
        }
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Relation relation = (Relation) obj;
        return Double.compare(relation.weight, weight) == 0 &&
               Objects.equals(srcId, relation.srcId) &&
               Objects.equals(tgtId, relation.tgtId) &&
               Objects.equals(description, relation.description) &&
               Objects.equals(keywords, relation.keywords) &&
               Objects.equals(filePath, relation.filePath) &&
               Objects.equals(documentId, relation.documentId) &&
               Objects.equals(sourceChunkIds, relation.sourceChunkIds);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(srcId, tgtId, description, keywords, weight, filePath, documentId, sourceChunkIds);
    }
    
    @Override
    public String toString() {
        return "Relation{" +
                "srcId='" + srcId + '\'' +
                ", tgtId='" + tgtId + '\'' +
                ", description='" + description + '\'' +
                ", keywords='" + keywords + '\'' +
                ", weight=" + weight +
                ", filePath='" + filePath + '\'' +
                ", documentId='" + documentId + '\'' +
                ", sourceChunkIds=" + sourceChunkIds +
                '}';
    }
    
    /**
     * Builder for Relation instances.
     */
    public static class Builder {
        private String srcId;
        private String tgtId;
        private String description;
        private String keywords;
        private double weight = 1.0;
        private String filePath;
        private String documentId;
        private List<String> sourceChunkIds = new ArrayList<>();
        
        public Builder srcId(@NotNull String srcId) {
            this.srcId = srcId;
            return this;
        }
        
        public Builder tgtId(@NotNull String tgtId) {
            this.tgtId = tgtId;
            return this;
        }
        
        public Builder description(@NotNull String description) {
            this.description = description;
            return this;
        }
        
        public Builder keywords(@NotNull String keywords) {
            this.keywords = keywords;
            return this;
        }
        
        public Builder weight(double weight) {
            this.weight = weight;
            return this;
        }
        
        public Builder filePath(@Nullable String filePath) {
            this.filePath = filePath;
            return this;
        }
        
        public Builder documentId(@Nullable String documentId) {
            this.documentId = documentId;
            return this;
        }
        
        /**
         * Sets the source chunk IDs.
         *
         * @param sourceChunkIds the list of chunk IDs (nullable, will be treated as empty list)
         * @return this builder
         */
        public Builder sourceChunkIds(@Nullable List<String> sourceChunkIds) {
            this.sourceChunkIds = sourceChunkIds != null ? new ArrayList<>(sourceChunkIds) : new ArrayList<>();
            return this;
        }
        
        /**
         * Adds a single source chunk ID.
         *
         * @param chunkId the chunk ID to add
         * @return this builder
         */
        public Builder addSourceChunkId(@NotNull String chunkId) {
            Objects.requireNonNull(chunkId, "chunkId must not be null");
            if (!sourceChunkIds.contains(chunkId)) {
                sourceChunkIds.add(chunkId);
            }
            return this;
        }
        
        public Relation build() {
            return new Relation(srcId, tgtId, description, keywords, weight, filePath, documentId, sourceChunkIds);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Represents a normalized relation pair for lock key generation.
     */
    public static record RelationPair(@NotNull String first, @NotNull String second) {
        public RelationPair {
            Objects.requireNonNull(first, "first must not be null");
            Objects.requireNonNull(second, "second must not be null");
        }
    }
}
