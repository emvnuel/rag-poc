package br.edu.ifba.lightrag.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents a relationship between two entities in the knowledge graph.
 * Relations are directed edges that connect entities with semantic meaning.
 */
public final class Relation {
    
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
        this(srcId, tgtId, description, keywords, weight, filePath, null);
    }
    
    /**
     * Constructs a new Relation.
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
        this.srcId = Objects.requireNonNull(srcId, "srcId must not be null");
        this.tgtId = Objects.requireNonNull(tgtId, "tgtId must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.keywords = Objects.requireNonNull(keywords, "keywords must not be null");
        this.weight = weight;
        this.filePath = filePath;
        this.documentId = documentId;
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
     * Creates a new Relation with updated description.
     */
    public Relation withDescription(@NotNull String newDescription) {
        return new Relation(srcId, tgtId, newDescription, keywords, weight, filePath, documentId);
    }
    
    /**
     * Creates a new Relation with updated weight.
     */
    public Relation withWeight(double newWeight) {
        return new Relation(srcId, tgtId, description, keywords, newWeight, filePath, documentId);
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
               Objects.equals(documentId, relation.documentId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(srcId, tgtId, description, keywords, weight, filePath, documentId);
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
        
        public Relation build() {
            return new Relation(srcId, tgtId, description, keywords, weight, filePath, documentId);
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
