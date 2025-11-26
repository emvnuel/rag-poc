package br.edu.ifba.lightrag.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents an entity in the knowledge graph.
 * Entities are nodes extracted from documents that represent key concepts, people, places, etc.
 */
public final class Entity {
    
    /**
     * Maximum number of source chunk IDs to store per entity.
     * Uses FIFO eviction when exceeded.
     */
    public static final int MAX_SOURCE_CHUNK_IDS = 50;
    
    @JsonProperty("entity_name")
    @NotNull
    private final String entityName;
    
    @JsonProperty("entity_type")
    @Nullable
    private final String entityType;
    
    @JsonProperty("description")
    @NotNull
    private final String description;
    
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
     * Constructs a new Entity (backward compatible constructor).
     *
     * @param entityName the unique name of the entity (required)
     * @param entityType the type/category of the entity (optional)
     * @param description detailed description of the entity (required)
     * @param filePath the file path of the source document (optional)
     */
    public Entity(
            @NotNull String entityName,
            @Nullable String entityType,
            @NotNull String description,
            @Nullable String filePath) {
        this(entityName, entityType, description, filePath, null, Collections.emptyList());
    }
    
    /**
     * Constructs a new Entity (backward compatible constructor with documentId).
     *
     * @param entityName the unique name of the entity (required)
     * @param entityType the type/category of the entity (optional)
     * @param description detailed description of the entity (required)
     * @param filePath the file path of the source document (optional)
     * @param documentId the document UUID that this entity was extracted from (optional)
     */
    public Entity(
            @NotNull String entityName,
            @Nullable String entityType,
            @NotNull String description,
            @Nullable String filePath,
            @Nullable String documentId) {
        this(entityName, entityType, description, filePath, documentId, Collections.emptyList());
    }
    
    /**
     * Constructs a new Entity with all fields.
     *
     * @param entityName the unique name of the entity (required)
     * @param entityType the type/category of the entity (optional)
     * @param description detailed description of the entity (required)
     * @param filePath the file path of the source document (optional)
     * @param documentId the document UUID that this entity was extracted from (optional)
     * @param sourceChunkIds UUIDs of chunks that contributed to this entity (optional)
     */
    public Entity(
            @NotNull String entityName,
            @Nullable String entityType,
            @NotNull String description,
            @Nullable String filePath,
            @Nullable String documentId,
            @Nullable List<String> sourceChunkIds) {
        this.entityName = Objects.requireNonNull(entityName, "entityName must not be null");
        this.entityType = entityType;
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.filePath = filePath;
        this.documentId = documentId;
        this.sourceChunkIds = sourceChunkIds != null 
            ? Collections.unmodifiableList(new ArrayList<>(sourceChunkIds)) 
            : Collections.emptyList();
    }
    
    @NotNull
    public String getEntityName() {
        return entityName;
    }
    
    @Nullable
    public String getEntityType() {
        return entityType;
    }
    
    @NotNull
    public String getDescription() {
        return description;
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
     * Gets the list of source chunk IDs that contributed to this entity.
     *
     * @return unmodifiable list of chunk UUIDs
     */
    @NotNull
    public List<String> getSourceChunkIds() {
        return sourceChunkIds;
    }
    
    /**
     * Creates a new Entity with updated description.
     */
    public Entity withDescription(@NotNull String newDescription) {
        return new Entity(entityName, entityType, newDescription, filePath, documentId, sourceChunkIds);
    }
    
    /**
     * Creates a new Entity with updated entity name.
     */
    public Entity withEntityName(@NotNull String newEntityName) {
        return new Entity(newEntityName, entityType, description, filePath, documentId, sourceChunkIds);
    }
    
    /**
     * Creates a new Entity with updated source chunk IDs.
     *
     * @param newSourceChunkIds the new list of source chunk IDs
     * @return new Entity instance with updated sourceChunkIds
     */
    public Entity withSourceChunkIds(@NotNull List<String> newSourceChunkIds) {
        return new Entity(entityName, entityType, description, filePath, documentId, newSourceChunkIds);
    }
    
    /**
     * Creates a new Entity with an additional source chunk ID.
     * Uses FIFO eviction if the list exceeds MAX_SOURCE_CHUNK_IDS.
     *
     * @param chunkId the chunk ID to add
     * @return new Entity instance with the chunk ID added
     */
    public Entity addSourceChunkId(@NotNull String chunkId) {
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
        
        return new Entity(entityName, entityType, description, filePath, documentId, newList);
    }
    
    /**
     * Checks if this entity has a specific source chunk ID.
     * 
     * Used during document deletion to identify entities sourced from a chunk.
     *
     * @param chunkId the chunk ID to check
     * @return true if this entity has the chunk in its source IDs
     * @since spec-007
     */
    public boolean hasSourceChunk(@NotNull String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        return sourceChunkIds.contains(chunkId);
    }
    
    /**
     * Creates a new Entity with a source chunk ID removed.
     * 
     * Used during document deletion to remove a chunk's contribution.
     *
     * @param chunkId the chunk ID to remove
     * @return new Entity instance with the chunk ID removed
     * @since spec-007
     */
    public Entity removeSourceChunk(@NotNull String chunkId) {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        
        if (!sourceChunkIds.contains(chunkId)) {
            return this;
        }
        
        List<String> newList = new ArrayList<>(sourceChunkIds);
        newList.remove(chunkId);
        
        return new Entity(entityName, entityType, description, filePath, documentId, newList);
    }
    
    /**
     * Merges this entity with another entity using the specified strategy.
     * 
     * The resulting entity combines source chunk IDs from both entities
     * and merges descriptions based on the strategy.
     *
     * @param other the entity to merge with
     * @param strategy how to merge descriptions (CONCATENATE, KEEP_FIRST, KEEP_LONGEST)
     * @param separator separator for CONCATENATE strategy
     * @return new Entity instance with merged data
     * @since spec-007
     */
    public Entity mergeWith(@NotNull Entity other, @NotNull String strategy, @NotNull String separator) {
        Objects.requireNonNull(other, "other must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(separator, "separator must not be null");
        
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
        
        // Use type from this entity if present, otherwise from other
        String mergedType = this.entityType != null ? this.entityType : other.entityType;
        
        return new Entity(this.entityName, mergedType, mergedDescription, this.filePath, this.documentId, mergedChunkIds);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Entity entity = (Entity) obj;
        return Objects.equals(entityName, entity.entityName) &&
               Objects.equals(entityType, entity.entityType) &&
               Objects.equals(description, entity.description) &&
               Objects.equals(filePath, entity.filePath) &&
               Objects.equals(documentId, entity.documentId) &&
               Objects.equals(sourceChunkIds, entity.sourceChunkIds);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(entityName, entityType, description, filePath, documentId, sourceChunkIds);
    }
    
    @Override
    public String toString() {
        return "Entity{" +
                "entityName='" + entityName + '\'' +
                ", entityType='" + entityType + '\'' +
                ", description='" + description + '\'' +
                ", filePath='" + filePath + '\'' +
                ", documentId='" + documentId + '\'' +
                ", sourceChunkIds=" + sourceChunkIds +
                '}';
    }
    
    /**
     * Builder for Entity instances.
     */
    public static class Builder {
        private String entityName;
        private String entityType;
        private String description;
        private String filePath;
        private String documentId;
        private List<String> sourceChunkIds = new ArrayList<>();
        
        public Builder entityName(@NotNull String entityName) {
            this.entityName = entityName;
            return this;
        }
        
        public Builder entityType(@Nullable String entityType) {
            this.entityType = entityType;
            return this;
        }
        
        public Builder description(@NotNull String description) {
            this.description = description;
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
        
        public Entity build() {
            return new Entity(entityName, entityType, description, filePath, documentId, sourceChunkIds);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
