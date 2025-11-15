# Data Model: Semantic Entity Deduplication

**Branch**: `002-semantic-entity-dedup` | **Date**: 2025-11-15 | **Phase**: 1 - Design  
**Input**: Research findings from `research.md` and requirements from `spec.md`

## Summary

This document defines the data structures and models used in the semantic entity deduplication system. The core models are: **EntitySimilarityScore** (tracks pairwise entity similarity with metric breakdown), **EntityCluster** (represents a group of duplicate entities to be merged), **DeduplicationConfig** (configuration parameters), and **EntityResolutionResult** (output of the resolution process with statistics). All models use immutable Java records for thread-safety and clarity.

---

## 1. EntitySimilarityScore

**Purpose**: Represents the similarity score between two entities with a breakdown by metric.

### Structure

```java
package br.edu.ifba.lightrag.core;

import org.jetbrains.annotations.NotNull;

/**
 * Represents the similarity score between two entities.
 * 
 * The score is a weighted combination of multiple metrics:
 * - Jaccard similarity (token overlap)
 * - Containment (substring matching)
 * - Levenshtein distance (edit distance)
 * - Abbreviation matching
 * 
 * @param entity1Name Name of the first entity
 * @param entity2Name Name of the second entity
 * @param entity1Type Type of the first entity (PERSON, ORGANIZATION, etc.)
 * @param entity2Type Type of the second entity
 * @param jaccardScore Jaccard similarity score [0.0, 1.0]
 * @param containmentScore Containment score [0.0, 1.0] (1.0 if one name contains the other)
 * @param levenshteinScore Normalized Levenshtein similarity [0.0, 1.0]
 * @param abbreviationScore Abbreviation match score [0.0, 1.0]
 * @param finalScore Weighted combined score [0.0, 1.0]
 */
public record EntitySimilarityScore(
    @NotNull String entity1Name,
    @NotNull String entity2Name,
    @NotNull String entity1Type,
    @NotNull String entity2Type,
    double jaccardScore,
    double containmentScore,
    double levenshteinScore,
    double abbreviationScore,
    double finalScore
) {
    
    /**
     * Creates an EntitySimilarityScore with validation.
     */
    public EntitySimilarityScore {
        if (entity1Name == null || entity1Name.isBlank()) {
            throw new IllegalArgumentException("entity1Name cannot be null or blank");
        }
        if (entity2Name == null || entity2Name.isBlank()) {
            throw new IllegalArgumentException("entity2Name cannot be null or blank");
        }
        if (entity1Type == null || entity1Type.isBlank()) {
            throw new IllegalArgumentException("entity1Type cannot be null or blank");
        }
        if (entity2Type == null || entity2Type.isBlank()) {
            throw new IllegalArgumentException("entity2Type cannot be null or blank");
        }
        if (jaccardScore < 0.0 || jaccardScore > 1.0) {
            throw new IllegalArgumentException("jaccardScore must be in [0.0, 1.0]");
        }
        if (containmentScore < 0.0 || containmentScore > 1.0) {
            throw new IllegalArgumentException("containmentScore must be in [0.0, 1.0]");
        }
        if (levenshteinScore < 0.0 || levenshteinScore > 1.0) {
            throw new IllegalArgumentException("levenshteinScore must be in [0.0, 1.0]");
        }
        if (abbreviationScore < 0.0 || abbreviationScore > 1.0) {
            throw new IllegalArgumentException("abbreviationScore must be in [0.0, 1.0]");
        }
        if (finalScore < 0.0 || finalScore > 1.0) {
            throw new IllegalArgumentException("finalScore must be in [0.0, 1.0]");
        }
    }
    
    /**
     * Returns true if the entities should be considered duplicates based on the threshold.
     */
    public boolean isDuplicate(double threshold) {
        return finalScore >= threshold;
    }
    
    /**
     * Returns true if the entities have the same type.
     */
    public boolean hasSameType() {
        return entity1Type.equalsIgnoreCase(entity2Type);
    }
    
    /**
     * Returns a formatted string representation for logging.
     */
    public String toLogString() {
        return String.format(
            "Similarity('%s' vs '%s'): %.3f [J:%.2f C:%.2f L:%.2f A:%.2f]",
            entity1Name, entity2Name, finalScore,
            jaccardScore, containmentScore, levenshteinScore, abbreviationScore
        );
    }
}
```

### Usage Example

```java
EntitySimilarityScore score = new EntitySimilarityScore(
    "Warren State Home and Training School",
    "Warren Home",
    "ORGANIZATION",
    "ORGANIZATION",
    0.67,  // Jaccard: 2/3 tokens match
    0.80,  // Containment: "Warren Home" contained in longer name
    0.75,  // Levenshtein: high similarity
    0.00,  // Abbreviation: no acronym match
    0.73   // Final weighted score
);

boolean isDupe = score.isDuplicate(0.70); // true
logger.info(score.toLogString());
// Output: Similarity('Warren State Home and Training School' vs 'Warren Home'): 0.730 [J:0.67 C:0.80 L:0.75 A:0.00]
```

---

## 2. EntityCluster

**Purpose**: Represents a group of entities that should be merged into a single canonical entity.

### Structure

```java
package br.edu.ifba.lightrag.core;

import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.Set;

/**
 * Represents a cluster of entities that should be merged.
 * 
 * A cluster contains:
 * - A canonical entity (the "representative" entity for the cluster)
 * - A list of entity indices that belong to this cluster
 * - Merged descriptions from all entities
 * - Aliases (alternative names) extracted from cluster members
 * 
 * @param canonicalEntity The canonical entity representing this cluster
 * @param entityIndices Indices of entities in the original list that belong to this cluster
 * @param aliases Alternative names for this entity (excluding the canonical name)
 * @param mergedDescription Combined description from all entities in the cluster
 */
public record EntityCluster(
    @NotNull Entity canonicalEntity,
    @NotNull Set<Integer> entityIndices,
    @NotNull List<String> aliases,
    @NotNull String mergedDescription
) {
    
    /**
     * Creates an EntityCluster with validation.
     */
    public EntityCluster {
        if (canonicalEntity == null) {
            throw new IllegalArgumentException("canonicalEntity cannot be null");
        }
        if (entityIndices == null || entityIndices.isEmpty()) {
            throw new IllegalArgumentException("entityIndices cannot be null or empty");
        }
        if (aliases == null) {
            throw new IllegalArgumentException("aliases cannot be null");
        }
        if (mergedDescription == null || mergedDescription.isBlank()) {
            throw new IllegalArgumentException("mergedDescription cannot be null or blank");
        }
    }
    
    /**
     * Returns the size of the cluster (number of entities merged).
     */
    public int size() {
        return entityIndices.size();
    }
    
    /**
     * Returns true if this is a singleton cluster (no duplicates found).
     */
    public boolean isSingleton() {
        return entityIndices.size() == 1;
    }
    
    /**
     * Returns true if this cluster contains the given entity index.
     */
    public boolean containsEntityIndex(int index) {
        return entityIndices.contains(index);
    }
    
    /**
     * Returns a formatted string representation for logging.
     */
    public String toLogString() {
        if (isSingleton()) {
            return String.format("Cluster: '%s' (no duplicates)", canonicalEntity.getEntityName());
        } else {
            return String.format(
                "Cluster: '%s' [%d entities merged] Aliases: %s",
                canonicalEntity.getEntityName(),
                size(),
                aliases.isEmpty() ? "none" : String.join(", ", aliases)
            );
        }
    }
}
```

### Usage Example

```java
// Entities 0, 2, 5 are duplicates, merge into entity 0 as canonical
EntityCluster cluster = new EntityCluster(
    entities.get(0), // "Warren State Home and Training School"
    Set.of(0, 2, 5),
    List.of("Warren Home", "Warren State Home"),
    "State institution for mentally disabled individuals. Also known as Warren Home, Warren State Home."
);

logger.info(cluster.toLogString());
// Output: Cluster: 'Warren State Home and Training School' [3 entities merged] Aliases: Warren Home, Warren State Home
```

---

## 3. DeduplicationConfig

**Purpose**: Configuration parameters for the entity resolution process.

### Structure

```java
package br.edu.ifba.lightrag.core;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jetbrains.annotations.NotNull;

/**
 * Configuration for entity deduplication.
 * 
 * All properties are read from application.properties with the prefix
 * "lightrag.entity.resolution".
 */
@ConfigMapping(prefix = "lightrag.entity.resolution")
public interface DeduplicationConfig {
    
    /**
     * Feature toggle for entity resolution.
     */
    @WithDefault("true")
    boolean enabled();
    
    /**
     * Similarity threshold for considering entities as duplicates.
     * Range: [0.0, 1.0]
     * Default: 0.75
     */
    @WithDefault("0.75")
    @Min(0)
    @Max(1)
    double similarityThreshold();
    
    /**
     * Maximum number of aliases to store per entity.
     * Default: 5
     */
    @WithDefault("5")
    int maxAliases();
    
    /**
     * Clustering algorithm: "threshold" (connected components) or "dbscan".
     * Default: "threshold"
     */
    @WithDefault("threshold")
    String clusteringAlgorithm();
    
    /**
     * Weight for Jaccard similarity metric.
     * Default: 0.35
     */
    @WithDefault("0.35")
    @Min(0)
    @Max(1)
    double weightJaccard();
    
    /**
     * Weight for containment metric.
     * Default: 0.25
     */
    @WithDefault("0.25")
    @Min(0)
    @Max(1)
    double weightContainment();
    
    /**
     * Weight for Levenshtein (edit distance) metric.
     * Default: 0.30
     */
    @WithDefault("0.30")
    @Min(0)
    @Max(1)
    double weightEdit();
    
    /**
     * Weight for abbreviation matching metric.
     * Default: 0.10
     */
    @WithDefault("0.10")
    @Min(0)
    @Max(1)
    double weightAbbreviation();
    
    /**
     * Batch size for processing entities.
     * Default: 200
     */
    @WithDefault("200")
    int batchSize();
    
    /**
     * Enable parallel processing for similarity computation.
     * Default: true
     */
    @WithDefault("true")
    boolean parallelEnabled();
    
    /**
     * Number of threads for parallel processing.
     * Default: 4
     */
    @WithDefault("4")
    int parallelThreads();
    
    /**
     * Enable semantic similarity using embeddings (Phase 3 feature).
     * Default: false
     */
    @WithDefault("false")
    boolean semanticEnabled();
    
    /**
     * Weight for semantic similarity in combined score.
     * Only used if semanticEnabled=true.
     * Default: 0.40
     */
    @WithDefault("0.40")
    @Min(0)
    @Max(1)
    double semanticWeight();
    
    /**
     * Log entity merge decisions at INFO level.
     * Default: true
     */
    @WithDefault("true")
    boolean logMerges();
    
    /**
     * Log detailed similarity scores at DEBUG level.
     * Default: false
     */
    @WithDefault("false")
    boolean logSimilarityScores();
    
    /**
     * Validates configuration at startup.
     * Throws IllegalArgumentException if invalid.
     */
    default void validate() {
        // Validate weights sum to 1.0
        double weightSum = weightJaccard() + weightContainment() 
                         + weightEdit() + weightAbbreviation();
        if (Math.abs(weightSum - 1.0) > 0.01) {
            throw new IllegalArgumentException(
                String.format(
                    "Similarity weights must sum to 1.0, got %.3f (jaccard=%.2f, containment=%.2f, edit=%.2f, abbrev=%.2f)",
                    weightSum, weightJaccard(), weightContainment(), weightEdit(), weightAbbreviation()
                )
            );
        }
        
        // Validate threshold
        if (similarityThreshold() < 0.0 || similarityThreshold() > 1.0) {
            throw new IllegalArgumentException(
                String.format("Similarity threshold must be in [0.0, 1.0], got %.3f", similarityThreshold())
            );
        }
        
        // Validate clustering algorithm
        if (!clusteringAlgorithm().equals("threshold") && !clusteringAlgorithm().equals("dbscan")) {
            throw new IllegalArgumentException(
                String.format("Clustering algorithm must be 'threshold' or 'dbscan', got '%s'", clusteringAlgorithm())
            );
        }
    }
}
```

### Configuration Example (application.properties)

```properties
# Entity Resolution Configuration
lightrag.entity.resolution.enabled=true
lightrag.entity.resolution.similarity.threshold=0.75
lightrag.entity.resolution.max.aliases=5
lightrag.entity.resolution.clustering.algorithm=threshold

# Similarity Metric Weights (must sum to 1.0)
lightrag.entity.resolution.weight.jaccard=0.35
lightrag.entity.resolution.weight.containment=0.25
lightrag.entity.resolution.weight.edit=0.30
lightrag.entity.resolution.weight.abbreviation=0.10

# Performance Tuning
lightrag.entity.resolution.batch.size=200
lightrag.entity.resolution.parallel.enabled=true
lightrag.entity.resolution.parallel.threads=4

# Phase 3 - Semantic Similarity (disabled by default)
lightrag.entity.resolution.semantic.enabled=false
lightrag.entity.resolution.semantic.weight=0.40

# Logging
lightrag.entity.resolution.log.merges=true
lightrag.entity.resolution.log.similarity.scores=false
```

---

## 4. EntityResolutionResult

**Purpose**: Output of the entity resolution process with statistics.

### Structure

```java
package br.edu.ifba.lightrag.core;

import org.jetbrains.annotations.NotNull;
import java.time.Duration;
import java.util.List;

/**
 * Result of the entity resolution process.
 * 
 * Contains:
 * - Resolved entities (after deduplication)
 * - Statistics about the resolution process
 * - Performance metrics
 * 
 * @param resolvedEntities List of entities after deduplication
 * @param originalEntityCount Number of entities before resolution
 * @param resolvedEntityCount Number of entities after resolution
 * @param duplicatesRemoved Number of duplicate entities merged
 * @param clustersFound Number of clusters identified
 * @param processingTime Duration of the resolution process
 */
public record EntityResolutionResult(
    @NotNull List<Entity> resolvedEntities,
    int originalEntityCount,
    int resolvedEntityCount,
    int duplicatesRemoved,
    int clustersFound,
    @NotNull Duration processingTime
) {
    
    /**
     * Creates an EntityResolutionResult with validation.
     */
    public EntityResolutionResult {
        if (resolvedEntities == null) {
            throw new IllegalArgumentException("resolvedEntities cannot be null");
        }
        if (originalEntityCount < 0) {
            throw new IllegalArgumentException("originalEntityCount cannot be negative");
        }
        if (resolvedEntityCount < 0) {
            throw new IllegalArgumentException("resolvedEntityCount cannot be negative");
        }
        if (duplicatesRemoved < 0) {
            throw new IllegalArgumentException("duplicatesRemoved cannot be negative");
        }
        if (clustersFound < 0) {
            throw new IllegalArgumentException("clustersFound cannot be negative");
        }
        if (processingTime == null) {
            throw new IllegalArgumentException("processingTime cannot be null");
        }
    }
    
    /**
     * Returns the deduplication rate (percentage of entities removed).
     */
    public double deduplicationRate() {
        if (originalEntityCount == 0) return 0.0;
        return (double) duplicatesRemoved / originalEntityCount;
    }
    
    /**
     * Returns the average processing time per entity in milliseconds.
     */
    public double averageProcessingTimePerEntity() {
        if (originalEntityCount == 0) return 0.0;
        return processingTime.toMillis() / (double) originalEntityCount;
    }
    
    /**
     * Returns true if any duplicates were found and removed.
     */
    public boolean hadDuplicates() {
        return duplicatesRemoved > 0;
    }
    
    /**
     * Returns a formatted string representation for logging.
     */
    public String toLogString() {
        return String.format(
            "Entity Resolution: %d → %d entities (-%d duplicates, %.1f%% reduction) | %d clusters | %dms (%.2fms/entity)",
            originalEntityCount,
            resolvedEntityCount,
            duplicatesRemoved,
            deduplicationRate() * 100,
            clustersFound,
            processingTime.toMillis(),
            averageProcessingTimePerEntity()
        );
    }
}
```

### Usage Example

```java
List<Entity> resolvedEntities = entityResolver.resolveDuplicates(entities, projectId);

EntityResolutionResult result = new EntityResolutionResult(
    resolvedEntities,
    200,  // original count
    150,  // resolved count
    50,   // duplicates removed
    45,   // clusters found (45 clusters of 150 unique entities)
    Duration.ofMillis(120)
);

logger.info(result.toLogString());
// Output: Entity Resolution: 200 → 150 entities (-50 duplicates, 25.0% reduction) | 45 clusters | 120ms (0.60ms/entity)

if (result.deduplicationRate() > 0.40) {
    logger.warn("High deduplication rate detected: {}", result.deduplicationRate());
}
```

---

## 5. Entity Model Extensions

**Purpose**: Document how the existing `Entity` class will be used in the resolution process.

### Existing Entity Structure

```java
package br.edu.ifba.lightrag.core;

// Existing Entity class (no modifications needed)
public class Entity {
    private String entityName;
    private String entityType;
    private String description;
    private List<String> sourceIds;
    
    // Getters, setters, constructors...
}
```

### Entity Merger Strategy

When merging entities in a cluster:

1. **Canonical Entity Selection**: Choose the entity with the longest name
2. **Description Merging**: Combine all descriptions, append aliases
3. **Source ID Aggregation**: Collect all source IDs from merged entities
4. **Type Preservation**: Use the canonical entity's type

**Merged Description Format**:
```
[Original Description] | [Additional Descriptions] | Também conhecida como: [Alias1], [Alias2], [Alias3]
```

**Example**:
```java
// Original entities
Entity e1 = new Entity("Warren State Home and Training School", "ORGANIZATION", 
                       "State institution for mentally disabled individuals", 
                       List.of("chunk-1"));
Entity e2 = new Entity("Warren Home", "ORGANIZATION", 
                       "Care facility in Warren", 
                       List.of("chunk-5", "chunk-8"));

// Merged entity
Entity merged = new Entity(
    "Warren State Home and Training School",  // Canonical name (longest)
    "ORGANIZATION",
    "State institution for mentally disabled individuals | Care facility in Warren | Também conhecida como: Warren Home",
    List.of("chunk-1", "chunk-5", "chunk-8")  // Aggregated source IDs
);
```

---

## 6. Database Schema Considerations

### No Schema Changes Required

The entity resolution process operates **before** entities are stored in the database. Therefore, no database schema changes are needed:

- **Apache AGE (Graph Storage)**: Entities are stored as nodes with properties (`entity_name`, `entity_type`, `description`). Merged entities are stored directly with combined descriptions.
- **pgvector (Vector Storage)**: Entity embeddings are stored as vectors. Merged entities generate a single embedding from the canonical name.

### Vector Storage Update Strategy

When entities are merged:

1. **Generate Single Embedding**: Use canonical entity name for embedding
2. **Store with Merged Metadata**: Link embedding to merged entity in graph
3. **Skip Duplicate Embeddings**: Don't store embeddings for merged-away entities

**No retroactive updates needed**: Existing entities in the database are not affected. Resolution only applies to newly indexed documents.

---

## 7. Immutability and Thread-Safety

All data models use Java records, which are:

- **Immutable**: All fields are final, no setters
- **Thread-safe**: Safe to share across threads without synchronization
- **Value-based**: Equality based on field values, not identity
- **Compact**: Automatic constructor, `equals()`, `hashCode()`, `toString()`

**Example**:
```java
EntitySimilarityScore score = new EntitySimilarityScore(...);
// score.finalScore = 0.9;  // Compile error: cannot assign to final field

// Thread-safe sharing
CompletableFuture.supplyAsync(() -> processScore(score));
```

---

## Next Steps

1. ✅ **Complete**: Data model defined
2. ⏭️ **Next**: Generate `contracts/EntityResolver.interface.md`
3. ⏭️ **Next**: Generate `quickstart.md`
4. ⏭️ **Next**: Run agent context update
5. ⏭️ **Next**: Begin TDD implementation

---

## References

- **Research Findings**: `specs/002-semantic-entity-dedup/research.md`
- **Specification**: `specs/002-semantic-entity-dedup/spec.md`
- **Implementation Plan**: `specs/002-semantic-entity-dedup/plan.md`
- **Existing Entity Class**: `src/main/java/br/edu/ifba/lightrag/core/Entity.java`
