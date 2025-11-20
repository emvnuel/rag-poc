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
    
    @JsonProperty("file_path")
    @Nullable
    private final String filePath;
    
    @JsonProperty("document_id")
    @Nullable
    private final String documentId;
    
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
        this(entityName, entityType, description, filePath, null);
    }
    
    /**
     * Constructs a new Entity.
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
        this.entityName = Objects.requireNonNull(entityName, "entityName must not be null");
        this.entityType = entityType;
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.filePath = filePath;
        this.documentId = documentId;
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
     * Creates a new Entity with updated description.
     */
    public Entity withDescription(@NotNull String newDescription) {
        return new Entity(entityName, entityType, newDescription, filePath, documentId);
    }
    
    /**
     * Creates a new Entity with updated entity name.
     */
    public Entity withEntityName(@NotNull String newEntityName) {
        return new Entity(newEntityName, entityType, description, filePath, documentId);
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
               Objects.equals(documentId, entity.documentId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(entityName, entityType, description, filePath, documentId);
    }
    
    @Override
    public String toString() {
        return "Entity{" +
                "entityName='" + entityName + '\'' +
                ", entityType='" + entityType + '\'' +
                ", description='" + description + '\'' +
                ", filePath='" + filePath + '\'' +
                ", documentId='" + documentId + '\'' +
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
        
        public Entity build() {
            return new Entity(entityName, entityType, description, filePath, documentId);
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
