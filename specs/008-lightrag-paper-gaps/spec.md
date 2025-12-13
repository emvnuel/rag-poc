# LightRAG Paper Implementation Gaps

## Overview

This document analyzes the gaps between the official LightRAG paper (arXiv:2410.05779v3) and the current Java implementation in this repository. It provides a detailed implementation plan for addressing each gap.

**Paper:** "LightRAG: Simple and Fast Retrieval-Augmented Generation" - HKUDS/LightRAG  
**Repository:** https://github.com/HKUDS/LightRAG

---

## Executive Summary

| Category | Coverage | Notes |
|----------|----------|-------|
| Graph-based Text Indexing | 90% | Missing relation vector storage |
| Dual-level Retrieval | 70% | Missing relation matching, N-hop expansion |
| Incremental Updates | 100% | Fully implemented |
| Deduplication | 100% | Semantic entity deduplication complete |
| Gleaning Extraction | 100% | Iterative extraction implemented |

**Overall Coverage: ~80%**

---

## Gap 1: Relation Vector Storage & Matching (P0 - Critical)

### Paper Reference

**Section 3.2 - Dual-level Retrieval Paradigm:**
> "The algorithm uses an efficient vector database to match local query keywords with candidate entities AND global query keywords with relations linked to global keys."

**Figure 1 - Architecture Diagram:**
Shows separate vector matching for both entities AND relations.

### Current Implementation

```
Current Storage Architecture:
├── chunkVectorStorage     → Stores chunk embeddings ✅
├── entityVectorStorage    → Stores entity embeddings ✅
└── relationVectorStorage  → NOT IMPLEMENTED ❌
```

**Problem:** GLOBAL mode queries search `entityVectorStorage` instead of a dedicated relation storage. This means:
- Relations are only discovered through graph traversal from matched entities
- High-level thematic queries (e.g., "How does X influence Y?") can't directly match relevant relationships
- The dual-level retrieval paradigm is incomplete

### Impact Analysis

| Query Type | Current Behavior | Expected Behavior |
|------------|------------------|-------------------|
| "Who is the CEO of Apple?" | Finds "Apple" entity, then traverses to find CEO relation | Same (LOCAL mode works) |
| "How does climate change affect agriculture?" | Finds "climate change" entity, limited relation discovery | Should directly match relations about climate-agriculture impact |
| Abstract/thematic queries | Poor performance | Should match relations via global keywords |

### Implementation Plan

#### Step 1: Add Relation Vector Storage Interface

**File:** `src/main/java/br/edu/ifba/lightrag/storage/VectorStorage.java`

Add a new metadata type for relations:

```java
/**
 * Metadata for relation vectors.
 * 
 * @param type Always "relation" for relation vectors
 * @param content The relation description text used for embedding
 * @param sourceEntityId Source entity name
 * @param targetEntityId Target entity name
 * @param keywords Relation keywords for additional context
 * @param documentId Source document UUID
 * @param projectId Project UUID for isolation
 */
public record RelationVectorMetadata(
    String type,
    String content,
    String sourceEntityId,
    String targetEntityId,
    String keywords,
    String documentId,
    String projectId
) {}
```

#### Step 2: Create Relation Embeddings During Extraction

**File:** `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`

Modify `storeKnowledgeGraph()` method:

```java
private CompletableFuture<Void> storeKnowledgeGraph(
    @NotNull List<Entity> entities,
    @NotNull List<Relation> relations,
    @Nullable Map<String, Object> metadata
) {
    // ... existing entity storage code ...
    
    // NEW: Generate and store relation embeddings
    CompletableFuture<Void> relationEmbeddingsFuture = CompletableFuture.completedFuture(null);
    
    if (!relations.isEmpty()) {
        // Build relation text for embedding: "keywords: description"
        List<String> relationTexts = relations.stream()
            .map(r -> buildRelationEmbeddingText(r))
            .toList();
        
        relationEmbeddingsFuture = embeddingFunction.embed(relationTexts)
            .thenCompose(embeddings -> {
                List<VectorStorage.VectorEntry> vectorEntries = new ArrayList<>();
                
                for (int i = 0; i < relations.size(); i++) {
                    Relation relation = relations.get(i);
                    String projectId = (String) metadata.get("project_id");
                    String documentId = (String) metadata.get("document_id");
                    
                    // Generate deterministic ID for relation vector
                    String relationId = generateRelationVectorId(
                        relation.getSrcId(), 
                        relation.getTgtId(), 
                        projectId
                    );
                    
                    VectorStorage.VectorMetadata vectorMetadata = new VectorStorage.VectorMetadata(
                        "relation",
                        relationTexts.get(i),
                        documentId,
                        null,  // chunkIndex not applicable
                        projectId
                    );
                    
                    vectorEntries.add(new VectorStorage.VectorEntry(
                        relationId,
                        embeddings.get(i),
                        vectorMetadata
                    ));
                }
                
                return relationVectorStorage.upsertBatch(vectorEntries);
            });
    }
    
    return CompletableFuture.allOf(
        entitiesFuture,
        relationsFuture,
        embeddingsFuture,
        relationEmbeddingsFuture  // NEW
    );
}

/**
 * Builds embedding text for a relation.
 * Combines keywords and description for richer semantic matching.
 */
private String buildRelationEmbeddingText(Relation relation) {
    StringBuilder text = new StringBuilder();
    
    // Include source and target for context
    text.append(relation.getSrcId())
        .append(" -> ")
        .append(relation.getTgtId())
        .append(": ");
    
    // Include keywords if present
    if (relation.getKeywords() != null && !relation.getKeywords().isEmpty()) {
        text.append(relation.getKeywords()).append(". ");
    }
    
    // Include description
    text.append(relation.getDescription());
    
    return text.toString();
}
```

#### Step 3: Add Relation Vector Storage to LightRAG Builder

**File:** `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`

```java
public static class Builder {
    // ... existing fields ...
    private VectorStorage relationVectorStorage;  // NEW
    
    public Builder relationVectorStorage(@NotNull VectorStorage relationVectorStorage) {
        this.relationVectorStorage = relationVectorStorage;
        return this;
    }
    
    public LightRAG build() {
        // ... existing validations ...
        if (relationVectorStorage == null) {
            throw new IllegalStateException("relationVectorStorage is required");
        }
        // ...
    }
}
```

#### Step 4: Update GlobalQueryExecutor to Search Relations

**File:** `src/main/java/br/edu/ifba/lightrag/query/GlobalQueryExecutor.java`

```java
@Override
public CompletableFuture<LightRAGQueryResult> execute(
    @NotNull String query,
    @NotNull QueryParam param
) {
    logger.info("Executing GLOBAL query");
    
    return extractSearchQuery(query, param.getProjectId()).thenCompose(searchQuery -> {
        return embeddingFunction.embedSingle(searchQuery).thenCompose(queryEmbedding -> {
            
            // NEW: Search BOTH entities AND relations in parallel
            int topK = param.getTopK();
            VectorStorage.VectorFilter entityFilter = new VectorStorage.VectorFilter(
                "entity", null, param.getProjectId()
            );
            VectorStorage.VectorFilter relationFilter = new VectorStorage.VectorFilter(
                "relation", null, param.getProjectId()
            );
            
            CompletableFuture<List<VectorSearchResult>> entityResults = 
                entityVectorStorage.query(queryEmbedding, topK, entityFilter);
            
            CompletableFuture<List<VectorSearchResult>> relationResults = 
                relationVectorStorage.query(queryEmbedding, topK, relationFilter);
            
            return CompletableFuture.allOf(entityResults, relationResults)
                .thenCompose(v -> {
                    List<VectorSearchResult> entities = entityResults.join();
                    List<VectorSearchResult> relations = relationResults.join();
                    
                    // Combine and build context from both sources
                    return buildGlobalContext(param.getProjectId(), entities, relations);
                });
        });
    });
}
```

#### Step 5: Database Migration

**File:** `docker-init/13-add-relation-vectors.sql`

```sql
-- Add index for relation vector queries
-- Note: Relation vectors use the same document_vectors table with type='relation'

-- Create partial index for relation-type vectors
CREATE INDEX IF NOT EXISTS idx_document_vectors_relation_project 
ON document_vectors (project_id) 
WHERE type = 'relation';

-- Add composite index for relation lookups
CREATE INDEX IF NOT EXISTS idx_document_vectors_relation_hnsw
ON document_vectors USING hnsw (embedding halfvec_cosine_ops)
WHERE type = 'relation';
```

#### Step 6: Configuration

**File:** `src/main/resources/application.properties`

```properties
# Relation vector storage settings
lightrag.relation.embedding.enabled=true
lightrag.relation.embedding.batch-size=32
```

### Testing Plan

1. **Unit Tests:**
   - Test relation embedding generation
   - Test deterministic relation ID generation
   - Test vector storage for relations

2. **Integration Tests:**
   - Test GLOBAL mode queries match relations
   - Test combined entity + relation context building
   - Test relation provenance tracking

3. **E2E Tests:**
   - Compare query quality before/after relation vectors
   - Measure GLOBAL mode accuracy improvement

### Estimated Effort

| Task | Effort |
|------|--------|
| Add relation vector storage interface | 2 hours |
| Modify storeKnowledgeGraph() | 3 hours |
| Update GlobalQueryExecutor | 4 hours |
| Database migration | 1 hour |
| Unit tests | 3 hours |
| Integration tests | 4 hours |
| **Total** | **17 hours** |

---

## Gap 2: N-hop Neighbor Expansion in LOCAL/GLOBAL Modes (P0 - Critical)

### Paper Reference

**Section 3.2 - Step (iii) Incorporating High-Order Relatedness:**
> "To enhance the query with higher-order relatedness, LightRAG further gathers neighboring nodes within the local subgraphs of the retrieved graph elements. This process involves the set {vi | vi ∈ V ∧ (vi ∈ Nv ∨ vi ∈ Ne)}, where Nv and Ne represent the one-hop neighboring nodes of the retrieved nodes v and edges e, respectively."

### Current Implementation

```
N-hop Traversal Usage:
├── MIX mode        → Uses traverseBFS() ✅
├── LOCAL mode      → No N-hop expansion ❌
├── GLOBAL mode     → No N-hop expansion ❌
└── HYBRID mode     → No N-hop expansion ❌
```

**Problem:** LOCAL and GLOBAL modes retrieve entities/relations but don't expand to neighboring nodes, missing valuable contextual information.

### Impact Analysis

**Example Query:** "What are the health effects of air pollution?"

| Mode | Current Behavior | With N-hop Expansion |
|------|------------------|---------------------|
| LOCAL | Finds chunks mentioning "air pollution" and "health effects" | Same |
| GLOBAL | Finds "Air Pollution" entity and its direct relations | Also finds "Respiratory Disease", "Asthma", "Particulate Matter" connected entities |

### Implementation Plan

#### Step 1: Add Neighbor Expansion Configuration

**File:** `src/main/java/br/edu/ifba/lightrag/core/LightRAGExtractionConfig.java`

```java
public interface LightRAGExtractionConfig {
    // ... existing config ...
    
    @WithName("neighbor-expansion")
    NeighborExpansionConfig neighborExpansion();
    
    interface NeighborExpansionConfig {
        /**
         * Enable N-hop neighbor expansion in LOCAL/GLOBAL modes.
         * Default: true
         */
        @WithDefault("true")
        boolean enabled();
        
        /**
         * Maximum depth for neighbor traversal.
         * Default: 1 (one-hop neighbors only, as per paper)
         */
        @WithDefault("1")
        int maxDepth();
        
        /**
         * Maximum number of neighbor nodes to retrieve.
         * Default: 20
         */
        @WithDefault("20")
        int maxNodes();
        
        /**
         * Whether to include neighbor relations in context.
         * Default: true
         */
        @WithDefault("true")
        boolean includeRelations();
    }
}
```

#### Step 2: Create NeighborExpander Utility

**File:** `src/main/java/br/edu/ifba/lightrag/query/NeighborExpander.java`

```java
package br.edu.ifba.lightrag.query;

import br.edu.ifba.lightrag.core.Entity;
import br.edu.ifba.lightrag.core.LightRAGExtractionConfig;
import br.edu.ifba.lightrag.core.Relation;
import br.edu.ifba.lightrag.storage.GraphStorage;
import br.edu.ifba.lightrag.storage.GraphStorage.GraphSubgraph;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Expands retrieved entities to include N-hop neighbors.
 * Implements LightRAG paper Section 3.2 Step (iii): Incorporating High-Order Relatedness.
 */
public class NeighborExpander {
    
    private static final Logger logger = LoggerFactory.getLogger(NeighborExpander.class);
    
    private final GraphStorage graphStorage;
    private final LightRAGExtractionConfig.NeighborExpansionConfig config;
    
    public NeighborExpander(
        @NotNull GraphStorage graphStorage,
        @NotNull LightRAGExtractionConfig.NeighborExpansionConfig config
    ) {
        this.graphStorage = graphStorage;
        this.config = config;
    }
    
    /**
     * Expands a list of seed entities to include their N-hop neighbors.
     * 
     * @param projectId Project UUID for graph isolation
     * @param seedEntities Initial entities from vector search
     * @return Expanded result including neighbors and their relations
     */
    public CompletableFuture<ExpansionResult> expand(
        @NotNull String projectId,
        @NotNull List<Entity> seedEntities
    ) {
        if (!config.enabled() || seedEntities.isEmpty()) {
            return CompletableFuture.completedFuture(
                new ExpansionResult(seedEntities, List.of())
            );
        }
        
        logger.debug("Expanding {} seed entities with maxDepth={}, maxNodes={}", 
            seedEntities.size(), config.maxDepth(), config.maxNodes());
        
        // Track all unique entities and relations
        Map<String, Entity> allEntities = new LinkedHashMap<>();
        Set<String> relationKeys = new HashSet<>();
        List<Relation> allRelations = new ArrayList<>();
        
        // Add seed entities first (preserve order for ranking)
        for (Entity entity : seedEntities) {
            allEntities.put(entity.getEntityName().toLowerCase(), entity);
        }
        
        // Expand each seed entity in parallel
        List<CompletableFuture<GraphSubgraph>> expansionFutures = new ArrayList<>();
        
        for (Entity seed : seedEntities) {
            CompletableFuture<GraphSubgraph> future = graphStorage.traverseBFS(
                projectId,
                seed.getEntityName(),
                config.maxDepth(),
                config.maxNodes() / seedEntities.size()  // Distribute budget
            );
            expansionFutures.add(future);
        }
        
        return CompletableFuture.allOf(expansionFutures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                // Merge all subgraphs
                for (CompletableFuture<GraphSubgraph> future : expansionFutures) {
                    GraphSubgraph subgraph = future.join();
                    
                    // Add neighbor entities
                    for (Entity neighbor : subgraph.entities()) {
                        String key = neighbor.getEntityName().toLowerCase();
                        if (!allEntities.containsKey(key)) {
                            allEntities.put(key, neighbor);
                        }
                    }
                    
                    // Add relations if configured
                    if (config.includeRelations()) {
                        for (Relation relation : subgraph.relations()) {
                            String relKey = relation.getSrcId() + "->" + relation.getTgtId();
                            if (!relationKeys.contains(relKey)) {
                                relationKeys.add(relKey);
                                allRelations.add(relation);
                            }
                        }
                    }
                }
                
                // Limit total entities to maxNodes
                List<Entity> resultEntities = allEntities.values().stream()
                    .limit(config.maxNodes())
                    .toList();
                
                logger.debug("Expansion complete: {} entities (from {} seeds), {} relations",
                    resultEntities.size(), seedEntities.size(), allRelations.size());
                
                return new ExpansionResult(resultEntities, allRelations);
            });
    }
    
    /**
     * Result of neighbor expansion.
     * 
     * @param entities All entities including seeds and neighbors
     * @param relations Relations between the entities
     */
    public record ExpansionResult(
        List<Entity> entities,
        List<Relation> relations
    ) {}
}
```

#### Step 3: Integrate into GlobalQueryExecutor

**File:** `src/main/java/br/edu/ifba/lightrag/query/GlobalQueryExecutor.java`

```java
public class GlobalQueryExecutor extends QueryExecutor {
    
    private final NeighborExpander neighborExpander;  // NEW
    
    public GlobalQueryExecutor(
        // ... existing params ...
        @Nullable NeighborExpander neighborExpander  // NEW
    ) {
        // ... existing initialization ...
        this.neighborExpander = neighborExpander;
    }
    
    @Override
    public CompletableFuture<LightRAGQueryResult> execute(...) {
        // ... existing entity retrieval code ...
        
        return graphStorage.getEntities(param.getProjectId(), entityIds)
            .thenCompose((List<Entity> entities) -> {
                
                // NEW: Expand to include N-hop neighbors
                if (neighborExpander != null) {
                    return neighborExpander.expand(param.getProjectId(), entities)
                        .thenCompose(expansionResult -> {
                            return buildContextWithExpansion(
                                param.getProjectId(),
                                expansionResult.entities(),
                                expansionResult.relations(),
                                entityResults
                            );
                        });
                }
                
                // Fallback: existing behavior without expansion
                return buildContext(param.getProjectId(), entities, entityResults);
            });
    }
}
```

#### Step 4: Integrate into LocalQueryExecutor

**File:** `src/main/java/br/edu/ifba/lightrag/query/LocalQueryExecutor.java`

For LOCAL mode, the expansion is optional and focuses on finding entities mentioned in the chunks:

```java
/**
 * Optionally expands chunk results with related graph context.
 * This bridges the gap between chunk-based retrieval and graph knowledge.
 */
private CompletableFuture<List<ChunkSelector.ScoredChunk>> enrichChunksWithGraphContext(
    @NotNull List<ChunkSelector.ScoredChunk> chunks,
    @NotNull QueryParam param
) {
    if (neighborExpander == null || !config.neighborExpansion().enabled()) {
        return CompletableFuture.completedFuture(chunks);
    }
    
    // Extract entity mentions from chunks (simple approach: use keywords)
    // More sophisticated: NER on chunk content
    List<String> potentialEntities = extractEntityMentions(chunks);
    
    // Look up these entities in the graph
    return graphStorage.getEntities(param.getProjectId(), potentialEntities)
        .thenCompose(entities -> {
            if (entities.isEmpty()) {
                return CompletableFuture.completedFuture(chunks);
            }
            
            // Expand to neighbors
            return neighborExpander.expand(param.getProjectId(), entities)
                .thenApply(expansion -> {
                    // Add graph context as additional scored chunks
                    // These are weighted lower than direct chunk matches
                    return mergeGraphContextWithChunks(chunks, expansion);
                });
        });
}
```

### Configuration Examples

**File:** `src/main/resources/application.properties`

```properties
# Default: Balanced expansion
lightrag.neighbor-expansion.enabled=true
lightrag.neighbor-expansion.max-depth=1
lightrag.neighbor-expansion.max-nodes=20
lightrag.neighbor-expansion.include-relations=true

# High-quality: Deeper expansion for comprehensive context
# lightrag.neighbor-expansion.max-depth=2
# lightrag.neighbor-expansion.max-nodes=50

# Fast: Minimal expansion for low latency
# lightrag.neighbor-expansion.max-depth=1
# lightrag.neighbor-expansion.max-nodes=10
```

### Testing Plan

1. **Unit Tests:**
   - Test NeighborExpander with mock GraphStorage
   - Test expansion result merging
   - Test configuration variations

2. **Integration Tests:**
   - Test GLOBAL mode with expansion enabled
   - Test expansion respects maxNodes limit
   - Test expansion with disconnected entities

3. **Performance Tests:**
   - Measure latency impact of expansion
   - Test with large graphs (1000+ entities)

### Estimated Effort

| Task | Effort |
|------|--------|
| Add configuration interface | 1 hour |
| Create NeighborExpander class | 4 hours |
| Integrate into GlobalQueryExecutor | 2 hours |
| Integrate into LocalQueryExecutor | 3 hours |
| Unit tests | 3 hours |
| Integration tests | 3 hours |
| Performance tests | 2 hours |
| **Total** | **18 hours** |

---

## Gap 3: Relation Global Theme Keys (P1 - Important)

### Paper Reference

**Section 3.1 - LLM Profiling for Key-Value Pair Generation:**
> "Relations may have multiple index keys derived from LLM enhancements that include global themes from connected entities."

### Current Implementation

Relations are stored with:
- `srcId` - Source entity name
- `tgtId` - Target entity name
- `description` - Relation description
- `keywords` - Keywords extracted during relation parsing

**Missing:** LLM-generated global theme keys that capture abstract/thematic concepts.

### Impact Analysis

**Example Relation:**
```
Source: "Electric Vehicles"
Target: "Urban Air Quality"  
Description: "The adoption of electric vehicles has led to measurable improvements in urban air quality by reducing tailpipe emissions."
Keywords: "adoption, improvements, emissions"
```

**Current Matching:** Only matches queries containing "electric vehicles", "air quality", "emissions"

**With Global Themes:** Would also match:
- "Environmental impact of transportation"
- "Sustainable urban development"
- "Climate change mitigation strategies"
- "Public health improvements in cities"

### Implementation Plan

#### Step 1: Add Global Keys to Relation Model

**File:** `src/main/java/br/edu/ifba/lightrag/core/Relation.java`

```java
public class Relation {
    // ... existing fields ...
    
    /**
     * LLM-generated global theme keys for high-level query matching.
     * Example: ["environmental sustainability", "urban planning", "public health"]
     */
    private List<String> globalKeys;
    
    // ... builder updates ...
}
```

#### Step 2: Create Global Theme Extractor

**File:** `src/main/java/br/edu/ifba/lightrag/core/GlobalThemeExtractor.java`

```java
package br.edu.ifba.lightrag.core;

import br.edu.ifba.lightrag.llm.LLMFunction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Extracts global themes from relations using LLM.
 * Implements LightRAG paper's P(·) profiling function for relations.
 */
public class GlobalThemeExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(GlobalThemeExtractor.class);
    
    private final LLMFunction llmFunction;
    private final int maxThemes;
    
    private static final String THEME_EXTRACTION_PROMPT = """
        Given the following relationship between two entities, extract 3-5 high-level themes 
        or abstract concepts that this relationship represents. These themes should be useful 
        for matching abstract queries about broader topics.
        
        Source Entity: %s
        Target Entity: %s
        Relationship: %s
        
        Output only the themes as a comma-separated list, nothing else.
        Example output: environmental sustainability, urban planning, public health policy
        
        Themes:
        """;
    
    public GlobalThemeExtractor(@NotNull LLMFunction llmFunction, int maxThemes) {
        this.llmFunction = llmFunction;
        this.maxThemes = maxThemes;
    }
    
    /**
     * Extracts global themes for a single relation.
     */
    public CompletableFuture<List<String>> extractThemes(@NotNull Relation relation) {
        String prompt = String.format(THEME_EXTRACTION_PROMPT,
            relation.getSrcId(),
            relation.getTgtId(),
            relation.getDescription()
        );
        
        return llmFunction.apply(prompt)
            .thenApply(response -> parseThemes(response))
            .exceptionally(e -> {
                logger.warn("Failed to extract themes for relation {}->{}: {}",
                    relation.getSrcId(), relation.getTgtId(), e.getMessage());
                return List.of();
            });
    }
    
    /**
     * Extracts global themes for multiple relations in batch.
     * Uses batching to reduce LLM calls.
     */
    public CompletableFuture<List<Relation>> extractThemesBatch(
        @NotNull List<Relation> relations,
        int batchSize
    ) {
        if (relations.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        List<CompletableFuture<Relation>> futures = new ArrayList<>();
        
        for (Relation relation : relations) {
            CompletableFuture<Relation> future = extractThemes(relation)
                .thenApply(themes -> relation.withGlobalKeys(themes));
            futures.add(future);
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .toList());
    }
    
    private List<String> parseThemes(String response) {
        List<String> themes = new ArrayList<>();
        
        String[] parts = response.split(",");
        for (String part : parts) {
            String theme = part.trim().toLowerCase();
            if (!theme.isEmpty() && themes.size() < maxThemes) {
                themes.add(theme);
            }
        }
        
        return themes;
    }
}
```

#### Step 3: Integrate into Extraction Pipeline

**File:** `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`

Modify `storeKnowledgeGraph()`:

```java
private CompletableFuture<Void> storeKnowledgeGraph(
    @NotNull List<Entity> entities,
    @NotNull List<Relation> relations,
    @Nullable Map<String, Object> metadata
) {
    // ... existing code ...
    
    // NEW: Extract global themes for relations if enabled
    CompletableFuture<List<Relation>> relationsWithThemes;
    
    if (globalThemeExtractor != null && extractionConfig.globalThemes().enabled()) {
        relationsWithThemes = globalThemeExtractor.extractThemesBatch(
            relations,
            extractionConfig.globalThemes().batchSize()
        );
    } else {
        relationsWithThemes = CompletableFuture.completedFuture(relations);
    }
    
    return relationsWithThemes.thenCompose(enrichedRelations -> {
        // Store enriched relations
        return graphStorage.upsertRelations(graphProjectId, enrichedRelations);
    });
}
```

#### Step 4: Include Global Keys in Relation Embedding

Update `buildRelationEmbeddingText()`:

```java
private String buildRelationEmbeddingText(Relation relation) {
    StringBuilder text = new StringBuilder();
    
    text.append(relation.getSrcId())
        .append(" -> ")
        .append(relation.getTgtId())
        .append(": ");
    
    // Include global themes for better abstract matching
    if (relation.getGlobalKeys() != null && !relation.getGlobalKeys().isEmpty()) {
        text.append("[Themes: ")
            .append(String.join(", ", relation.getGlobalKeys()))
            .append("] ");
    }
    
    if (relation.getKeywords() != null && !relation.getKeywords().isEmpty()) {
        text.append(relation.getKeywords()).append(". ");
    }
    
    text.append(relation.getDescription());
    
    return text.toString();
}
```

### Configuration

**File:** `src/main/resources/application.properties`

```properties
# Global theme extraction for relations
lightrag.extraction.global-themes.enabled=true
lightrag.extraction.global-themes.max-themes=5
lightrag.extraction.global-themes.batch-size=10

# Can be disabled for faster processing at the cost of abstract query matching
# lightrag.extraction.global-themes.enabled=false
```

### Estimated Effort

| Task | Effort |
|------|--------|
| Update Relation model | 1 hour |
| Create GlobalThemeExtractor | 3 hours |
| Integrate into extraction pipeline | 2 hours |
| Update relation embedding | 1 hour |
| Tests | 3 hours |
| **Total** | **10 hours** |

---

## Gap 4: LLM Description Summarization (P2 - Enhancement)

### Paper Reference

**Implicit in Section 3.1:**
When entities are encountered multiple times across documents, their descriptions accumulate. The paper implies using LLM to synthesize coherent summaries rather than simple concatenation.

### Current Implementation

**File:** `LightRAG.java:1443-1463`

```java
private String mergeDescriptions(@NotNull String existingDesc, @NotNull String newDesc) {
    // Simple concatenation with separator
    String merged = existingDesc + config.entityDescriptionSeparator() + newDesc;
    
    // Truncate if too long
    if (merged.length() > config.entityDescriptionMaxLength()) {
        merged = merged.substring(0, config.entityDescriptionMaxLength() - 3) + "...";
    }
    
    return merged;
}
```

**Problem:** Descriptions become repetitive and incoherent when an entity appears many times.

### Implementation Plan

You already have `LLMDescriptionSummarizer.java`. The fix is to invoke it when descriptions exceed a threshold:

```java
private String mergeDescriptions(@NotNull String existingDesc, @NotNull String newDesc) {
    String merged = existingDesc + config.entityDescriptionSeparator() + newDesc;
    
    // If merged description is too long, use LLM summarization
    if (merged.length() > config.entityDescriptionMaxLength() && descriptionSummarizer != null) {
        return descriptionSummarizer.summarize(merged, config.entityDescriptionMaxLength())
            .join();  // Block for simplicity, or make mergeDescriptions async
    }
    
    return merged;
}
```

### Estimated Effort

| Task | Effort |
|------|--------|
| Integrate existing summarizer | 2 hours |
| Add async support if needed | 2 hours |
| Tests | 2 hours |
| **Total** | **6 hours** |

---

## Gap 5: Chunk Provenance Tracking (P2 - Enhancement)

### Paper Reference

**Figure 1:**
Shows entities and relations with "Original Chunks ID: xxx" field.

### Current Implementation

Entities and relations don't track which chunks they were extracted from.

### Implementation Plan

#### Step 1: Add Source Tracking to Models

```java
public class Entity {
    // ... existing fields ...
    private List<String> sourceChunkIds;
    private List<String> sourceDocumentIds;
}

public class Relation {
    // ... existing fields ...
    private List<String> sourceChunkIds;
    private List<String> sourceDocumentIds;
}
```

#### Step 2: Track During Extraction

In `extractKnowledgeGraphFromChunk()`:

```java
private KGExtractionChunkResult extractKnowledgeGraphFromChunk(
    @NotNull String chunkId,
    @NotNull String chunkContent
) {
    // ... extraction code ...
    
    // Add chunk provenance to all extracted entities/relations
    List<Entity> entitiesWithProvenance = entities.stream()
        .map(e -> e.withSourceChunkIds(List.of(chunkId)))
        .toList();
    
    List<Relation> relationsWithProvenance = relations.stream()
        .map(r -> r.withSourceChunkIds(List.of(chunkId)))
        .toList();
    
    return new KGExtractionChunkResult(entitiesWithProvenance, relationsWithProvenance);
}
```

#### Step 3: Merge Provenance During Deduplication

When entities are merged, combine their source lists:

```java
private void mergeEntityKeepLonger(Map<String, Entity> entityMap, Entity entity) {
    String key = entity.getEntityName().toLowerCase();
    Entity existing = entityMap.get(key);
    
    if (existing == null) {
        entityMap.put(key, entity);
        return;
    }
    
    // Merge source chunk IDs
    List<String> mergedSources = new ArrayList<>(existing.getSourceChunkIds());
    mergedSources.addAll(entity.getSourceChunkIds());
    
    // Keep entity with longer description, but preserve all sources
    Entity merged = (newDescLen > existingDescLen ? entity : existing)
        .withSourceChunkIds(mergedSources);
    
    entityMap.put(key, merged);
}
```

### Estimated Effort

| Task | Effort |
|------|--------|
| Update Entity/Relation models | 2 hours |
| Track during extraction | 2 hours |
| Merge during deduplication | 2 hours |
| Update graph storage | 2 hours |
| Tests | 3 hours |
| **Total** | **11 hours** |

---

## Implementation Roadmap

### Phase 1: Critical Gaps (P0) - Week 1-2

| Gap | Task | Effort | Owner |
|-----|------|--------|-------|
| Gap 1 | Relation Vector Storage | 17 hours | - |
| Gap 2 | N-hop Neighbor Expansion | 18 hours | - |
| **Total** | | **35 hours** | |

### Phase 2: Important Gaps (P1) - Week 3

| Gap | Task | Effort | Owner |
|-----|------|--------|-------|
| Gap 3 | Relation Global Theme Keys | 10 hours | - |
| **Total** | | **10 hours** | |

### Phase 3: Enhancements (P2) - Week 4

| Gap | Task | Effort | Owner |
|-----|------|--------|-------|
| Gap 4 | LLM Description Summarization | 6 hours | - |
| Gap 5 | Chunk Provenance Tracking | 11 hours | - |
| **Total** | | **17 hours** | |

### Total Estimated Effort

| Phase | Effort |
|-------|--------|
| Phase 1 (P0) | 35 hours |
| Phase 2 (P1) | 10 hours |
| Phase 3 (P2) | 17 hours |
| **Grand Total** | **62 hours** |

---

## Success Metrics

After implementing these gaps, measure:

1. **GLOBAL Mode Accuracy:** Should improve by 20-30% on abstract/thematic queries
2. **Context Richness:** Average entities per query response should increase by 50%
3. **Query Latency:** Should remain under 2x current latency with N-hop expansion
4. **Relation Match Rate:** New metric - percentage of queries that match relations directly

---

## References

- [LightRAG Paper (arXiv:2410.05779v3)](https://arxiv.org/abs/2410.05779)
- [Official LightRAG Repository](https://github.com/HKUDS/LightRAG)
- [Existing Implementation Specs](../006-lightrag-official-impl/spec.md)
