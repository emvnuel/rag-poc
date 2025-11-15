# API Contracts: Entity Resolver

**Branch**: `002-semantic-entity-dedup` | **Date**: 2025-11-15 | **Phase**: 1 - Design  
**Input**: Research findings and data model from Phase 0 and Phase 1 documents

## Summary

This document defines the API contracts for the entity resolution system. The main components are:
- **EntityResolver**: Main service orchestrating the deduplication process
- **EntitySimilarityCalculator**: Computes similarity scores between entities
- **EntityClusterer**: Groups similar entities into merge clusters

All interfaces follow Jakarta EE conventions with proper validation, error handling, and logging.

---

## 1. EntityResolver Interface

**Package**: `br.edu.ifba.lightrag.core`  
**Purpose**: Main service for resolving duplicate entities

### Method Signatures

```java
package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;

/**
 * Service for resolving duplicate entities in the knowledge graph.
 * 
 * This service identifies entities that represent the same real-world concept
 * and merges them into canonical entities. It uses a multi-metric similarity
 * approach combining string matching, type comparison, and optional semantic
 * similarity.
 * 
 * @author OpenCode Agent
 * @version 1.0
 * @since 2025-11-15
 */
@ApplicationScoped
public class EntityResolver {
    
    /**
     * Resolves duplicate entities in a list.
     * 
     * Algorithm:
     * 1. Group entities by type (PERSON, ORGANIZATION, etc.)
     * 2. Within each type, compute pairwise similarity scores
     * 3. Apply clustering to identify groups of duplicate entities
     * 4. Merge each cluster into a canonical entity with aliases
     * 
     * @param entities List of entities to deduplicate (must not be null)
     * @param projectId Project ID for context (used for logging, can be null)
     * @return List of resolved entities with duplicates merged
     * @throws IllegalArgumentException if entities is null
     */
    @NotNull
    public List<Entity> resolveDuplicates(
        @NotNull List<Entity> entities, 
        @Nullable String projectId
    );
    
    /**
     * Resolves duplicate entities and returns detailed statistics.
     * 
     * This method provides the same functionality as resolveDuplicates() but
     * returns additional metrics about the resolution process.
     * 
     * @param entities List of entities to deduplicate (must not be null)
     * @param projectId Project ID for context (used for logging, can be null)
     * @return EntityResolutionResult containing resolved entities and statistics
     * @throws IllegalArgumentException if entities is null
     */
    @NotNull
    public EntityResolutionResult resolveDuplicatesWithStats(
        @NotNull List<Entity> entities,
        @Nullable String projectId
    );
}
```

### Behavior Specification

**Input Validation**:
- `entities` must not be null (throws `IllegalArgumentException`)
- Empty list is valid and returns empty list immediately (no processing)
- `projectId` can be null (only affects logging context)

**Processing Guarantees**:
- **Idempotency**: Running resolution twice on the same input produces the same output
- **Order Independence**: Entity order in input list does not affect merge decisions
- **Type Safety**: Entities with different types are never merged (spec FR-003)
- **Project Isolation**: Resolution respects project boundaries (if projectId provided)

**Performance Characteristics**:
- **Time Complexity**: O(n×k + n log n) where n = total entities, k = average entities per type
- **Space Complexity**: O(n²) for similarity matrix (per type group)
- **Target Performance**: <2x baseline processing time (spec SC-004)

**Error Handling**:
- Throws `IllegalArgumentException` for invalid inputs
- Logs warnings for suspicious merge patterns (e.g., >80% deduplication rate)
- Does not throw exceptions for similarity calculation failures (skips comparison, logs error)

---

## 2. EntitySimilarityCalculator Interface

**Package**: `br.edu.ifba.lightrag.core`  
**Purpose**: Calculates similarity scores between entity pairs

### Method Signatures

```java
package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;

/**
 * Calculator for entity similarity using multiple metrics.
 * 
 * Combines four complementary similarity metrics:
 * - Jaccard similarity (token overlap)
 * - Containment (substring matching)
 * - Levenshtein distance (edit distance)
 * - Abbreviation matching
 * 
 * @author OpenCode Agent
 * @version 1.0
 * @since 2025-11-15
 */
@ApplicationScoped
public class EntitySimilarityCalculator {
    
    /**
     * Computes similarity between two entities.
     * 
     * Returns a detailed score breakdown with individual metric scores
     * and a weighted final score.
     * 
     * @param entity1 First entity (must not be null)
     * @param entity2 Second entity (must not be null)
     * @return EntitySimilarityScore with metric breakdown
     * @throws IllegalArgumentException if either entity is null
     */
    @NotNull
    public EntitySimilarityScore computeSimilarity(
        @NotNull Entity entity1,
        @NotNull Entity entity2
    );
    
    /**
     * Computes similarity between two entity names with type constraint.
     * 
     * This is a lower-level method that operates on entity attributes directly.
     * Returns 0.0 if entity types don't match (type-aware matching).
     * 
     * @param name1 First entity name (must not be null or blank)
     * @param name2 Second entity name (must not be null or blank)
     * @param type1 First entity type (must not be null)
     * @param type2 Second entity type (must not be null)
     * @return Similarity score [0.0, 1.0], or 0.0 if types don't match
     * @throws IllegalArgumentException if any parameter is null or blank
     */
    public double computeNameSimilarity(
        @NotNull String name1,
        @NotNull String name2,
        @NotNull String type1,
        @NotNull String type2
    );
    
    /**
     * Computes Jaccard similarity (token overlap) between two entity names.
     * 
     * Formula: |intersection| / |union| of token sets
     * 
     * @param name1 First entity name (must not be null)
     * @param name2 Second entity name (must not be null)
     * @return Jaccard score [0.0, 1.0]
     */
    public double computeJaccardSimilarity(
        @NotNull String name1,
        @NotNull String name2
    );
    
    /**
     * Computes containment score (substring matching).
     * 
     * Returns 1.0 if one name contains the other (after normalization),
     * 0.0 otherwise.
     * 
     * @param name1 First entity name (must not be null)
     * @param name2 Second entity name (must not be null)
     * @return Containment score (0.0 or 1.0)
     */
    public double computeContainmentScore(
        @NotNull String name1,
        @NotNull String name2
    );
    
    /**
     * Computes normalized Levenshtein similarity (edit distance).
     * 
     * Formula: 1 - (editDistance / maxLength)
     * 
     * @param name1 First entity name (must not be null)
     * @param name2 Second entity name (must not be null)
     * @return Levenshtein similarity [0.0, 1.0]
     */
    public double computeLevenshteinSimilarity(
        @NotNull String name1,
        @NotNull String name2
    );
    
    /**
     * Computes abbreviation matching score.
     * 
     * Returns 1.0 if one name is an acronym of the other (e.g., "MIT" vs
     * "Massachusetts Institute of Technology"), 0.0 otherwise.
     * 
     * @param name1 First entity name (must not be null)
     * @param name2 Second entity name (must not be null)
     * @return Abbreviation score (0.0 or 1.0)
     */
    public double computeAbbreviationScore(
        @NotNull String name1,
        @NotNull String name2
    );
    
    /**
     * Normalizes an entity name for comparison.
     * 
     * Normalization steps:
     * - Convert to lowercase
     * - Trim whitespace
     * - Collapse multiple spaces to single space
     * - Remove punctuation
     * 
     * @param name Entity name to normalize (must not be null)
     * @return Normalized entity name
     */
    @NotNull
    public String normalizeName(@NotNull String name);
    
    /**
     * Tokenizes an entity name into words.
     * 
     * @param name Entity name to tokenize (must not be null)
     * @return Set of tokens (words)
     */
    @NotNull
    public Set<String> tokenize(@NotNull String name);
}
```

### Calculation Example

```java
// Input entities
Entity e1 = new Entity("Warren State Home and Training School", "ORGANIZATION", ...);
Entity e2 = new Entity("Warren Home", "ORGANIZATION", ...);

// Calculate similarity
EntitySimilarityScore score = calculator.computeSimilarity(e1, e2);

// Result breakdown:
// - jaccardScore: 0.67 (2/3 tokens match: "Warren", "Home")
// - containmentScore: 0.80 ("Warren Home" contained in longer name)
// - levenshteinScore: 0.75 (relatively few edits needed)
// - abbreviationScore: 0.00 (not an acronym match)
// - finalScore: 0.35*0.67 + 0.25*0.80 + 0.30*0.75 + 0.10*0.00 = 0.659

if (score.isDuplicate(0.65)) {
    logger.info("Entities are duplicates: {}", score.toLogString());
}
```

---

## 3. EntityClusterer Interface

**Package**: `br.edu.ifba.lightrag.core`  
**Purpose**: Groups similar entities into merge clusters

### Method Signatures

```java
package br.edu.ifba.lightrag.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.Set;

/**
 * Service for clustering entities based on similarity scores.
 * 
 * Uses threshold-based connected components algorithm to identify
 * groups of similar entities that should be merged.
 * 
 * @author OpenCode Agent
 * @version 1.0
 * @since 2025-11-15
 */
@ApplicationScoped
public class EntityClusterer {
    
    /**
     * Clusters entities based on similarity matrix.
     * 
     * Algorithm (Threshold-Based Connected Components):
     * 1. Build similarity graph: nodes = entities, edges = similarity >= threshold
     * 2. Apply connected components algorithm (DFS/BFS)
     * 3. Each connected component becomes a cluster
     * 
     * @param entities List of entities to cluster (must not be null)
     * @param similarityMatrix Pairwise similarity scores (must be n×n where n = entities.size())
     * @param threshold Similarity threshold for considering entities duplicates [0.0, 1.0]
     * @return List of clusters, each containing entity indices that should be merged
     * @throws IllegalArgumentException if inputs are invalid
     */
    @NotNull
    public List<Set<Integer>> clusterBySimilarity(
        @NotNull List<Entity> entities,
        @NotNull double[][] similarityMatrix,
        double threshold
    );
    
    /**
     * Merges a cluster of entities into a single canonical entity.
     * 
     * Merge strategy:
     * - Select canonical entity: longest name in cluster
     * - Combine descriptions: concatenate with " | " separator
     * - Extract aliases: all names except canonical
     * - Aggregate source IDs: union of all source IDs
     * 
     * @param clusterIndices Indices of entities in the cluster (must not be empty)
     * @param entities Original list of entities (must not be null)
     * @return EntityCluster with canonical entity and metadata
     * @throws IllegalArgumentException if clusterIndices is empty or entities is null
     */
    @NotNull
    public EntityCluster mergeCluster(
        @NotNull Set<Integer> clusterIndices,
        @NotNull List<Entity> entities
    );
    
    /**
     * Builds a similarity matrix for a list of entities.
     * 
     * This is a helper method that uses EntitySimilarityCalculator to
     * compute all pairwise similarities.
     * 
     * @param entities List of entities (must not be null)
     * @param calculator Similarity calculator (must not be null)
     * @return n×n similarity matrix where matrix[i][j] = similarity(i, j)
     * @throws IllegalArgumentException if any parameter is null
     */
    @NotNull
    public double[][] buildSimilarityMatrix(
        @NotNull List<Entity> entities,
        @NotNull EntitySimilarityCalculator calculator
    );
}
```

### Clustering Example

```java
// Input: 5 entities with similarity matrix
List<Entity> entities = List.of(e0, e1, e2, e3, e4);
double[][] simMatrix = {
    {1.00, 0.82, 0.78, 0.30, 0.15},  // e0 similar to e1, e2
    {0.82, 1.00, 0.75, 0.25, 0.10},  // e1 similar to e0, e2
    {0.78, 0.75, 1.00, 0.20, 0.05},  // e2 similar to e0, e1
    {0.30, 0.25, 0.20, 1.00, 0.85},  // e3 similar to e4
    {0.15, 0.10, 0.05, 0.85, 1.00}   // e4 similar to e3
};

// Cluster with threshold 0.70
List<Set<Integer>> clusters = clusterer.clusterBySimilarity(
    entities, simMatrix, 0.70
);

// Result: 2 clusters
// Cluster 1: {0, 1, 2} - "Warren State Home..." variants
// Cluster 2: {3, 4}    - Different entities

// Merge each cluster
for (Set<Integer> cluster : clusters) {
    EntityCluster merged = clusterer.mergeCluster(cluster, entities);
    logger.info(merged.toLogString());
}
```

---

## 4. Integration with LightRAG

**Modification Point**: `LightRAG.java` (line 925, `storeKnowledgeGraph` method)

### Updated Method Signature

```java
package br.edu.ifba.lightrag.core;

import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;

public class LightRAG {
    
    @Inject
    EntityResolver entityResolver;
    
    @Inject
    DeduplicationConfig config;
    
    /**
     * Stores entities and relations in the knowledge graph.
     * 
     * NEW: Applies entity resolution before existing deduplication logic.
     * 
     * @param entities List of entities to store
     * @param relations List of relations to store
     * @param metadata Metadata including project_id
     * @return CompletableFuture that completes when storage is done
     */
    private CompletableFuture<Void> storeKnowledgeGraph(
        @NotNull List<Entity> entities,
        @NotNull List<Relation> relations,
        @Nullable Map<String, Object> metadata
    ) {
        if (entities.isEmpty() && relations.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        // NEW: Apply entity resolution before deduplication
        List<Entity> resolvedEntities = entities;
        if (config.enabled() && !entities.isEmpty()) {
            String projectId = metadata != null ? (String) metadata.get("project_id") : null;
            resolvedEntities = entityResolver.resolveDuplicates(entities, projectId);
            
            logger.info("Entity resolution: {} → {} entities", 
                        entities.size(), resolvedEntities.size());
        }
        
        // Existing deduplication logic (now operates on resolved entities)
        Map<String, Entity> uniqueEntities = new HashMap<>();
        for (Entity entity : resolvedEntities) {
            String key = normalizeEntityName(entity.getEntityName());
            
            if (uniqueEntities.containsKey(key)) {
                // Merge descriptions
                Entity existing = uniqueEntities.get(key);
                String mergedDesc = mergeDescriptions(
                    existing.getDescription(), 
                    entity.getDescription()
                );
                uniqueEntities.put(key, existing.withDescription(mergedDesc));
            } else {
                uniqueEntities.put(key, entity);
            }
        }
        
        // Store in graph storage
        return graphStorage.upsertEntities(uniqueEntities.values().stream().toList());
    }
}
```

---

## 5. Configuration Integration

**Configuration Class**: Defined in `data-model.md`

### Usage in Services

```java
@ApplicationScoped
public class EntityResolver {
    
    @Inject
    DeduplicationConfig config;
    
    @Inject
    EntitySimilarityCalculator calculator;
    
    @Inject
    EntityClusterer clusterer;
    
    public List<Entity> resolveDuplicates(List<Entity> entities, String projectId) {
        // Use configuration for threshold
        double threshold = config.similarityThreshold();
        
        // Use configuration for logging
        if (config.logMerges()) {
            logger.info("Resolving duplicates for {} entities (threshold={})", 
                        entities.size(), threshold);
        }
        
        // ... resolution logic
    }
}
```

---

## 6. Logging Standards

All services must follow structured logging conventions:

### Log Levels

- **INFO**: Entity resolution summary (input/output counts, duration)
- **DEBUG**: Detailed similarity scores, cluster compositions
- **WARN**: High deduplication rates (>60%), suspicious merge patterns
- **ERROR**: Failures in similarity calculation, configuration errors

### Log Format

```java
// INFO - Resolution summary
logger.info("Entity resolution: {} → {} entities (-{} duplicates) in {}ms", 
            originalCount, resolvedCount, duplicatesRemoved, durationMs);

// DEBUG - Similarity scores (if config.logSimilarityScores() = true)
logger.debug("Similarity: '{}' vs '{}' = {:.3f} [J:{:.2f} C:{:.2f} L:{:.2f} A:{:.2f}]",
             name1, name2, finalScore, jaccard, containment, levenshtein, abbreviation);

// WARN - High deduplication rate
logger.warn("High deduplication rate detected: {:.1f}% ({} → {} entities)", 
            deduplicationRate * 100, originalCount, resolvedCount);
```

---

## 7. Exception Handling

### Standard Exceptions

| Exception Type | When to Use | HTTP Status (if applicable) |
|----------------|-------------|---------------------------|
| `IllegalArgumentException` | Invalid input parameters (null, blank, out of range) | 400 Bad Request |
| `IllegalStateException` | Invalid service state (e.g., config not validated) | 500 Internal Server Error |
| `RuntimeException` | Unexpected errors (algorithm failures) | 500 Internal Server Error |

### Error Handling Example

```java
public List<Entity> resolveDuplicates(List<Entity> entities, String projectId) {
    // Input validation
    if (entities == null) {
        throw new IllegalArgumentException("entities cannot be null");
    }
    
    // Early return for empty input
    if (entities.isEmpty()) {
        return List.of();
    }
    
    try {
        // Processing logic
        return processEntities(entities, projectId);
        
    } catch (Exception e) {
        logger.error("Failed to resolve entities for project {}: {}", 
                     projectId, e.getMessage(), e);
        // Don't throw - return unmodified entities as fallback
        return entities;
    }
}
```

---

## 8. Testing Contracts

All services must provide testable interfaces:

### Test Hooks

```java
@ApplicationScoped
public class EntitySimilarityCalculator {
    
    // Package-private constructor for testing
    EntitySimilarityCalculator() {}
    
    // Allow injection of custom config in tests
    @Inject
    public EntitySimilarityCalculator(DeduplicationConfig config) {
        this.config = config;
    }
}
```

### Mock-Friendly Design

All dependencies are injected via constructor or field injection, allowing easy mocking:

```java
@QuarkusTest
public class EntityResolverTest {
    
    @InjectMock
    EntitySimilarityCalculator calculator;
    
    @InjectMock
    EntityClusterer clusterer;
    
    @Inject
    EntityResolver resolver;
    
    @Test
    public void testResolveDuplicates() {
        // Mock behavior
        when(calculator.computeSimilarity(any(), any()))
            .thenReturn(new EntitySimilarityScore(...));
        
        // Test
        List<Entity> result = resolver.resolveDuplicates(entities, "project-1");
        
        // Verify
        assertEquals(expectedCount, result.size());
    }
}
```

---

## Next Steps

1. ✅ **Complete**: API contracts defined
2. ⏭️ **Next**: Generate `quickstart.md` user guide
3. ⏭️ **Next**: Run agent context update
4. ⏭️ **Next**: Begin TDD implementation with unit tests

---

## References

- **Data Model**: `specs/002-semantic-entity-dedup/data-model.md`
- **Research Findings**: `specs/002-semantic-entity-dedup/research.md`
- **Specification**: `specs/002-semantic-entity-dedup/spec.md`
- **Jakarta EE Standards**: https://jakarta.ee/specifications/
