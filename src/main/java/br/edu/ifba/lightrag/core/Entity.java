package br.edu.ifba.lightrag.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents an entity in the knowledge graph.
 * Entities are nodes extracted from documents that represent key concepts, people, places, etc.
 */
public final class Entity {
    
    @JsonProperty("entity_name")
    @NotNull
    private final String entityName;
    
    @JsonProperty("entity_type")
    @Nullable
    private final String entityType;
    
    @JsonProperty("description")
    @NotNull
    private final String description;
    
    @JsonProperty("source_id")
    @Nullable
    private final String sourceId;
    
    @JsonProperty("file_path")
    @Nullable
    private final String filePath;
    
    /**
     * Constructs a new Entity.
     *
     * @param entityName the unique name of the entity (required)
     * @param entityType the type/category of the entity (optional)
     * @param description detailed description of the entity (required)
     * @param sourceId the ID of the source document (optional)
     * @param filePath the file path of the source document (optional)
     */
    public Entity(
            @NotNull String entityName,
            @Nullable String entityType,
            @NotNull String description,
            @Nullable String sourceId,
            @Nullable String filePath) {
        this.entityName = Objects.requireNonNull(entityName, "entityName must not be null");
        this.entityType = entityType;
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.sourceId = sourceId;
        this.filePath = filePath;
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
    public String getSourceId() {
        return sourceId;
    }
    
    @Nullable
    public String getFilePath() {
        return filePath;
    }
    
    /**
     * Creates a new Entity with updated description.
     */
    public Entity withDescription(@NotNull String newDescription) {
        return new Entity(entityName, entityType, newDescription, sourceId, filePath);
    }
    
    /**
     * Creates a new Entity with updated entity name.
     */
    public Entity withEntityName(@NotNull String newEntityName) {
        return new Entity(newEntityName, entityType, description, sourceId, filePath);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Entity entity = (Entity) obj;
        return Objects.equals(entityName, entity.entityName) &&
               Objects.equals(entityType, entity.entityType) &&
               Objects.equals(description, entity.description) &&
               Objects.equals(sourceId, entity.sourceId) &&
               Objects.equals(filePath, entity.filePath);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(entityName, entityType, description, sourceId, filePath);
    }
    
    @Override
    public String toString() {
        return "Entity{" +
                "entityName='" + entityName + '\'' +
                ", entityType='" + entityType + '\'' +
                ", description='" + description + '\'' +
                ", sourceId='" + sourceId + '\'' +
                ", filePath='" + filePath + '\'' +
                '}';
    }
    
    /**
     * Builder for Entity instances.
     */
    public static class Builder {
        private String entityName;
        private String entityType;
        private String description;
        private String sourceId;
        private String filePath;
        
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
        
        public Builder sourceId(@Nullable String sourceId) {
            this.sourceId = sourceId;
            return this;
        }
        
        public Builder filePath(@Nullable String filePath) {
            this.filePath = filePath;
            return this;
        }
        
        public Entity build() {
            return new Entity(entityName, entityType, description, sourceId, filePath);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
