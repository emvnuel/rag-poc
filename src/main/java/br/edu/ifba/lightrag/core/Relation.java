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
    
    /**
     * Checks if this relation is a self-loop (source equals target).
     * 
     * Self-loops are typically invalid and should be filtered out during extraction.
     *
     * @return true if srcId equals tgtId (case-insensitive)
     * @since spec-007
     */
    public boolean isSelfLoop() {
        return srcId.equalsIgnoreCase(tgtId);
    }
    
    /**
     * Creates a new Relation with redirected source or target entity.
     * 
     * Used during entity merging to redirect relations from a merged entity
     * to the target (surviving) entity.
     *
     * @param oldEntityName the entity name being replaced
     * @param newEntityName the entity name to redirect to
     * @return new Relation with the entity reference updated, or this if no change needed
     * @since spec-007
     */
    public Relation redirect(@NotNull String oldEntityName, @NotNull String newEntityName) {
        Objects.requireNonNull(oldEntityName, "oldEntityName must not be null");
        Objects.requireNonNull(newEntityName, "newEntityName must not be null");
        
        boolean srcMatches = srcId.equalsIgnoreCase(oldEntityName);
        boolean tgtMatches = tgtId.equalsIgnoreCase(oldEntityName);
        
        if (!srcMatches && !tgtMatches) {
            return this;
        }
        
        String newSrcId = srcMatches ? newEntityName : srcId;
        String newTgtId = tgtMatches ? newEntityName : tgtId;
        
        return new Relation(newSrcId, newTgtId, description, keywords, weight, filePath, documentId, sourceChunkIds);
    }
    
    /**
     * Merges this relation with another relation using the specified strategy.
     * 
     * The resulting relation combines source chunk IDs from both relations
     * and merges descriptions based on the strategy. Weight is summed.
     *
     * @param other the relation to merge with
     * @param strategy how to merge descriptions (CONCATENATE, KEEP_FIRST, KEEP_LONGEST)
     * @param separator separator for CONCATENATE strategy
     * @return new Relation instance with merged data
     * @throws IllegalArgumentException if relations don't have the same src/tgt pair
     * @since spec-007
     */
    public Relation mergeWith(@NotNull Relation other, @NotNull String strategy, @NotNull String separator) {
        Objects.requireNonNull(other, "other must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(separator, "separator must not be null");
        
        // Validate that relations connect the same entity pair
        if (!srcId.equalsIgnoreCase(other.srcId) || !tgtId.equalsIgnoreCase(other.tgtId)) {
            throw new IllegalArgumentException(
                    "Cannot merge relations with different entity pairs: " +
                    srcId + "->" + tgtId + " vs " + other.srcId + "->" + other.tgtId);
        }
        
        // Merge descriptions based on strategy
        String mergedDescription = switch (strategy.toUpperCase()) {
            case "CONCATENATE" -> {
                if (this.description.equals(other.description)) {
                    yield this.description;
                }
                yield this.description + separator + other.description;
            }
            case "KEEP_FIRST" -> this.description;
            case "KEEP_LONGEST" -> this.description.length() >= other.description.length() 
                    ? this.description 
                    : other.description;
            default -> this.description + separator + other.description;
        };
        
        // Merge keywords (deduplicated)
        String mergedKeywords = mergeKeywords(this.keywords, other.keywords);
        
        // Sum weights
        double mergedWeight = this.weight + other.weight;
        
        // Merge source chunk IDs (deduplicated, respecting max)
        List<String> mergedChunkIds = new ArrayList<>(this.sourceChunkIds);
        for (String chunkId : other.sourceChunkIds) {
            if (!mergedChunkIds.contains(chunkId)) {
                mergedChunkIds.add(chunkId);
            }
        }
        // Apply FIFO eviction if needed
        while (mergedChunkIds.size() > MAX_SOURCE_CHUNK_IDS) {
            mergedChunkIds.remove(0);
        }
        
        return new Relation(srcId, tgtId, mergedDescription, mergedKeywords, mergedWeight, filePath, documentId, mergedChunkIds);
    }
    
    /**
     * Merges two keyword strings by combining unique keywords.
     */
    private static String mergeKeywords(String keywords1, String keywords2) {
        if (keywords1.equals(keywords2)) {
            return keywords1;
        }
        
        java.util.Set<String> uniqueKeywords = new java.util.LinkedHashSet<>();
        for (String kw : keywords1.split(",")) {
            String trimmed = kw.trim();
            if (!trimmed.isEmpty()) {
                uniqueKeywords.add(trimmed);
            }
        }
        for (String kw : keywords2.split(",")) {
            String trimmed = kw.trim();
            if (!trimmed.isEmpty()) {
                uniqueKeywords.add(trimmed);
            }
        }
        
        return String.join(", ", uniqueKeywords);
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
