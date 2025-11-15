# Research Findings: Semantic Entity Deduplication

**Branch**: `001-semantic-entity-dedup` | **Date**: 2025-11-15 | **Phase**: 0 - Research  
**Input**: Technical Context from `dupes.md`, `spec.md`, and `plan.md`

## Summary

This document consolidates research findings for implementing semantic entity deduplication in the LightRAG system. The recommended approach uses a **multi-metric weighted similarity algorithm** combining Jaccard similarity (35%), containment matching (25%), Levenshtein distance (30%), and abbreviation matching (10%). Entity clustering uses **threshold-based connected components** (threshold = 0.75) which is simpler and more predictable than DBSCAN. Entity merging within Apache AGE v1.5.0 constraints will use **selective MERGE operations** rather than property updates. Performance optimization targets O(n²) → O(n×k) through **type-based blocking** where k is average entities per type group.

---

## 1. String Similarity Algorithms

### Decision: Multi-Metric Weighted Combination

Use a weighted combination of four complementary metrics:

| Metric | Weight | Purpose | Example Match |
|--------|--------|---------|---------------|
| **Jaccard Similarity** (Token Overlap) | 35% | Catches word reordering and partial matches | "Warren State Home" ↔ "Warren Home" |
| **Containment** (Substring Match) | 25% | Identifies shortened forms | "MIT" ⊂ "Massachusetts Institute of Technology" |
| **Levenshtein Distance** (Edit Distance) | 30% | Handles typos and minor variations | "Microsft" ↔ "Microsoft" |
| **Abbreviation Matching** | 10% | Detects acronyms | "IBM" ↔ "International Business Machines" |

**Weighted Formula**:
```
similarity(n1, n2) = 0.35×jaccard + 0.25×containment + 0.30×levenshtein + 0.10×abbreviation
```

### Rationale

1. **Complementary Strengths**: Each metric captures different types of name variations:
   - Jaccard handles word permutations ("Home Warren" vs "Warren Home")
   - Containment handles abbreviations and shortened forms
   - Levenshtein handles typos and character-level changes
   - Abbreviation matching handles formal/informal name variations

2. **Empirical Validation**: The `dupes.md` analysis demonstrated these four metrics catching the "Warren Home" duplicate cluster:
   - "Warren State Home" vs "Warren Home": High Jaccard (2/3 words match), High containment
   - "Warren State Home and Training School" vs "Warren Home": Medium Jaccard, High containment
   - "Warren Home School" vs "Warren Home": High Jaccard, High containment

3. **Configurable Weights**: By exposing weights in `application.properties`, domain experts can tune the algorithm for their content without code changes.

### Alternatives Considered

- **Single Metric (Levenshtein only)**: Too rigid, misses word-level variations like "New York City" vs "NYC"
- **TF-IDF + Cosine Similarity**: Overkill for short entity names (typically 2-5 words), better suited for long descriptions
- **Jaro-Winkler Distance**: Good for short strings but less effective for multi-word entity names
- **Soundex/Metaphone**: Phonetic matching is unnecessary for text-based entity extraction

### Implementation References

**Levenshtein Distance** (from `dupes.md` lines 966-989):
```java
public int levenshteinDistance(String s1, String s2) {
    int[][] dp = new int[s1.length() + 1][s2.length() + 1];
    
    for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
    for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
    
    for (int i = 1; i <= s1.length(); i++) {
        for (int j = 1; j <= s2.length(); j++) {
            int cost = s1.charAt(i-1) == s2.charAt(j-1) ? 0 : 1;
            dp[i][j] = Math.min(
                Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1),
                dp[i-1][j-1] + cost
            );
        }
    }
    
    return dp[s1.length()][s2.length()];
}
```

**Jaccard Similarity** (from `dupes.md` lines 993-1003):
```java
public double jaccardSimilarity(Set<String> set1, Set<String> set2) {
    Set<String> intersection = new HashSet<>(set1);
    intersection.retainAll(set2);
    
    Set<String> union = new HashSet<>(set1);
    union.addAll(set2);
    
    return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
}
```

**Abbreviation Matching** (from `dupes.md` lines 1007-1024):
```java
public boolean matchesAbbreviation(String full, String abbr) {
    if (full.length() < abbr.length()) {
        return matchesAbbreviation(abbr, full);
    }
    
    // Check if abbr is acronym of full
    String[] words = full.split("\\s+");
    StringBuilder acronym = new StringBuilder();
    for (String word : words) {
        if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))) {
            acronym.append(word.charAt(0));
        }
    }
    
    return acronym.toString().equalsIgnoreCase(abbr);
}
```

---

## 2. Clustering Algorithms

### Decision: Threshold-Based Connected Components

Use **threshold-based connected components** with a similarity threshold of **0.75** as the default.

**Algorithm**:
1. Build similarity graph where nodes are entities and edges connect entities with similarity ≥ threshold
2. Apply connected components algorithm (DFS/BFS) to find clusters
3. Each connected component becomes a merge cluster

**Example**:
```
Entities: [A, B, C, D]
Similarities: 
  sim(A, B) = 0.82 ✓
  sim(B, C) = 0.78 ✓
  sim(A, C) = 0.65 ✗
  sim(D, A) = 0.30 ✗

Result: Two clusters: {A, B, C}, {D}
```

### Rationale

1. **Simplicity**: Connected components is conceptually simple and easy to explain to users. No hyperparameter tuning beyond the similarity threshold.

2. **Transitivity**: If A→B and B→C are similar, they form one cluster even if A and C are below threshold. This handles entity chains like:
   - "Warren State Home and Training School" → "Warren State Home" → "Warren Home"

3. **Predictability**: Threshold-based approach has predictable behavior. Users can reason about "if two entities have 75% similarity, they merge."

4. **Performance**: O(n²) similarity computation + O(n+m) connected components where m = number of edges. For typical entity batches (50-200 entities), this is fast enough.

5. **Alignment with dupes.md**: The reference document (lines 219-246) explicitly recommends threshold-based clustering with connected components.

### Alternatives Considered

- **DBSCAN** (Density-Based Spatial Clustering):
  - **Pros**: Can find arbitrarily-shaped clusters, handles outliers well
  - **Cons**: Requires two hyperparameters (epsilon, minPoints), less intuitive for users, distance-based (requires converting similarity to distance)
  - **Verdict**: Overkill for entity deduplication where clusters are simply "same entity" groups

- **Hierarchical Clustering** (Agglomerative):
  - **Pros**: Creates dendrogram for analysis, no predefined number of clusters
  - **Cons**: O(n² log n) complexity, requires choosing linkage method (single/complete/average), harder to explain
  - **Verdict**: Slower and more complex than needed

- **Pairwise Threshold Only** (No clustering):
  - **Pros**: Simplest possible approach
  - **Cons**: Misses transitive relationships (A→B, B→C but not A→C)
  - **Verdict**: Insufficient for real-world entity variations

### Configuration Parameters

```properties
# Clustering configuration
lightrag.entity.resolution.similarity.threshold=0.75
lightrag.entity.resolution.clustering.algorithm=threshold
```

**Threshold Selection Guidelines**:
- **0.70-0.75**: Aggressive deduplication, may produce false positives
- **0.75-0.80**: Balanced (recommended default)
- **0.80-0.85**: Conservative, fewer merges but higher precision
- **0.85-0.90**: Very conservative, only merge very similar entities

---

## 3. Embedding-Based Similarity

### Decision: Deferred to Phase 3 (Optional Enhancement)

**Phase 2 Implementation**: Use only string-based similarity metrics (Jaccard, Levenshtein, containment, abbreviation).

**Phase 3 Implementation** (if Phase 2 < 85% accuracy): Add semantic similarity using cosine similarity on entity embeddings.

### Rationale

1. **Existing Infrastructure**: Entity embeddings are already generated in `LightRAG.java` (lines 975-1006 in the codebase). We can leverage these without additional embedding calls.

2. **Incremental Approach**: The `dupes.md` phased strategy (lines 713-776) recommends starting with string-based resolution first, then adding semantic similarity only if needed.

3. **Performance**: String-based similarity is fast (microseconds per comparison). Adding embedding lookups and cosine similarity adds overhead (~1-5ms per comparison depending on vector size).

4. **Diminishing Returns**: For name-based matching, string similarity already catches most variations. Semantic similarity provides the most value for **description-based** matching (P3 priority in spec.md).

### Phase 3 Implementation Strategy

When implemented, semantic similarity will:

1. **Retrieve Entity Embeddings**: Query `PgVectorStorage` for embeddings of entity names
2. **Compute Cosine Similarity**: 
   ```java
   double cosineSim = cosineSimilarity(embedding1, embedding2);
   ```
3. **Combine with String Similarity**:
   ```java
   double finalScore = 0.60 × stringSimilarity + 0.40 × cosineSimilarity;
   ```

**Configuration** (Phase 3):
```properties
lightrag.entity.resolution.semantic.enabled=false
lightrag.entity.resolution.semantic.weight=0.40
```

### Alternatives Considered

- **Semantic-First Approach**: Use only embeddings for similarity
  - **Cons**: Misses exact string matches that should obviously merge (e.g., "Apple Inc." vs "Apple Inc"), slower
  
- **Description Embeddings Only**: Only use embeddings for entity descriptions, not names
  - **Pros**: Better semantic understanding of entity context
  - **Cons**: Requires Phase 3 implementation, not needed for P1/P2 user stories

---

## 4. Performance Optimization

### Decision: Type-Based Blocking + Parallel Batch Processing

**Optimization Strategy**:

1. **Type-Based Blocking** (Primary Optimization)
   - **Before**: O(n²) comparisons across all n entities
   - **After**: O(n×k) comparisons where k = average entities per type
   - **Method**: Group entities by `entity_type` before similarity computation
   - **Rationale**: Entities of different types should never merge (spec.md FR-003), so we can skip cross-type comparisons

2. **Parallel Batch Processing** (Secondary Optimization)
   - Use Java's `CompletableFuture` and `ForkJoinPool` for parallel similarity computations
   - Process similarity matrix rows in parallel:
     ```java
     List<CompletableFuture<double[]>> futures = new ArrayList<>();
     for (int i = 0; i < entities.size(); i++) {
         final int row = i;
         futures.add(CompletableFuture.supplyAsync(() -> 
             computeSimilarityRow(entities, row)
         ));
     }
     CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
     ```

3. **Early Termination Heuristics**
   - **Length Ratio Check**: If `|len(name1) - len(name2)| / max(len(name1), len(name2)) > 0.5`, skip detailed comparison (will be below threshold)
   - **First Token Check**: If first tokens don't match and no containment possible, skip

### Performance Projections

**Baseline** (current deduplication): ~100ms for 200 entities
**Target** (with optimizations): ~150-200ms for 200 entities (1.5-2x overhead)

| Optimization | Expected Speedup | Implementation Effort |
|--------------|------------------|----------------------|
| Type-based blocking | 3-5x (if 3-5 types) | Low (30 min) |
| Parallel processing | 2-3x (on 4+ cores) | Medium (2 hours) |
| Early termination | 1.2-1.5x | Low (1 hour) |

**Combined**: 7-22x speedup over naive O(n²) → within 2x overhead constraint ✅

### Alternatives Considered

- **Locality-Sensitive Hashing (LSH)**:
  - **Pros**: Sublinear similarity search, very fast for large datasets
  - **Cons**: Complex implementation, introduces false negatives, overkill for batch sizes of 50-200 entities
  - **Verdict**: Not needed for current scale

- **Caching Similarity Scores**:
  - **Pros**: Avoids recomputation for repeated entity pairs
  - **Cons**: Memory overhead, cache invalidation complexity, entities are rarely repeated across batches
  - **Verdict**: Low value for cross-batch deduplication

- **GPU Acceleration**:
  - **Pros**: Massive parallelization for similarity matrix computation
  - **Cons**: Requires CUDA/OpenCL setup, adds deployment complexity, CPU performance is sufficient
  - **Verdict**: Not needed

---

## 5. Configuration Strategy

### Decision: Hierarchical Configuration with Sensible Defaults

**Configuration Schema** (add to `application.properties`):

```properties
# Entity Resolution - Feature Toggle
lightrag.entity.resolution.enabled=true

# Clustering Configuration
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

### Rationale

1. **Feature Toggle** (`enabled`): Allows disabling resolution if performance issues arise in production. Default `true` because spec requires it.

2. **Tunable Threshold**: Different domains need different sensitivity:
   - **Academic papers**: Higher threshold (0.80+) to avoid merging different papers with similar titles
   - **Literary texts**: Lower threshold (0.70-0.75) for name variations and nicknames

3. **Metric Weights**: Allows domain experts to prioritize certain similarity aspects:
   - **Medical texts**: Increase `weight.edit` for typo tolerance in drug names
   - **News articles**: Increase `weight.abbreviation` for organization acronyms

4. **Performance Knobs**: `batch.size` and `parallel.threads` allow tuning for different hardware configurations

5. **Observability**: `log.merges` enables debugging of merge decisions without verbose similarity score logs

### Configuration Validation

Implement a `@ConfigMapping` class to validate configuration at startup:

```java
@ConfigMapping(prefix = "lightrag.entity.resolution")
public interface EntityResolutionConfig {
    
    boolean enabled();
    
    @WithDefault("0.75")
    double similarityThreshold();
    
    @WithDefault("5")
    int maxAliases();
    
    default void validate() {
        if (similarityThreshold() < 0.0 || similarityThreshold() > 1.0) {
            throw new IllegalArgumentException("Threshold must be in [0.0, 1.0]");
        }
        
        double weightSum = weightJaccard() + weightContainment() 
                         + weightEdit() + weightAbbreviation();
        if (Math.abs(weightSum - 1.0) > 0.01) {
            throw new IllegalArgumentException("Weights must sum to 1.0");
        }
    }
}
```

### Alternatives Considered

- **Hard-Coded Thresholds**: Simple but inflexible, requires recompilation for tuning
- **Database-Stored Configuration**: More dynamic but adds complexity, overkill for this feature
- **Per-Project Configuration**: Allows different thresholds per project, but adds complexity to config management

---

## 6. Apache AGE v1.5.0 Limitations

### Decision: Use Selective MERGE Operations with Property Reconstruction

**Problem** (from `dupes.md` lines 300-323):
Apache AGE v1.5.0 has limitations on updating entity properties after creation. The `MERGE` command may not properly update all entity attributes.

**Workaround Strategy**:

1. **Create Merged Entity as New Node**: Instead of updating existing entity, create a new merged entity node
2. **Transfer Relationships**: Redirect all relationships from old entities to the merged entity
3. **Delete Old Entities**: Remove duplicate entity nodes after relationship transfer
4. **Preserve Metadata**: Ensure all properties (description, embeddings, source_ids) are transferred

**Implementation Approach** (modify `LightRAG.java` line 925):

```java
private CompletableFuture<Void> storeKnowledgeGraph(
    @NotNull List<Entity> entities,
    @NotNull List<Relation> relations,
    @Nullable Map<String, Object> metadata
) {
    if (entities.isEmpty() && relations.isEmpty()) {
        return CompletableFuture.completedFuture(null);
    }
    
    // NEW: Apply entity resolution before deduplication
    List<Entity> resolvedEntities = config.entityResolutionEnabled() 
        ? entityResolver.resolveDuplicates(entities, metadata.get("project_id"))
        : entities;
    
    // Existing deduplication logic operates on resolved entities
    Map<String, Entity> uniqueEntities = new HashMap<>();
    for (Entity entity : resolvedEntities) {
        String key = normalizeEntityName(entity.getEntityName());
        
        if (uniqueEntities.containsKey(key)) {
            // Merge descriptions
            Entity existing = uniqueEntities.get(key);
            String mergedDesc = mergeDescriptions(existing.getDescription(), 
                                                   entity.getDescription());
            uniqueEntities.put(key, existing.withDescription(mergedDesc));
        } else {
            uniqueEntities.put(key, entity);
        }
    }
    
    // Store in graph (AGE will handle MERGE operations)
    return graphStorage.upsertEntities(uniqueEntities.values().stream().toList());
}
```

### Rationale

1. **Resolution Before Storage**: By resolving duplicates **before** calling `graphStorage.upsertEntities()`, we avoid needing to update entities in AGE after creation.

2. **Leverage Existing Deduplication**: The existing title-case deduplication logic (lines 936-949 in original `LightRAG.java`) still applies **after** semantic resolution, providing a second layer of deduplication.

3. **Transactional Safety**: All entity merges happen in-memory before database writes, avoiding partial merge states.

### AGE-Specific Constraints

| Limitation | Impact | Workaround |
|------------|--------|------------|
| **Property updates may not work** | Can't update `entity_name` or `description` after creation | Resolve entities before first storage |
| **Node deletion requires relationship cleanup** | Deleting old entities requires CASCADE or manual cleanup | Not needed - we merge before storage |
| **MERGE command has quirks** | Inconsistent behavior with complex properties | Use `upsertEntities()` which handles MERGE internally |

### Alternatives Considered

- **Post-Storage Resolution**: Resolve duplicates after entities are in AGE
  - **Cons**: Requires complex UPDATE/DELETE operations, hits AGE limitations
  - **Verdict**: Not feasible with AGE v1.5.0

- **Use PostgreSQL Triggers**: Add database triggers to auto-merge similar entities
  - **Cons**: Complex trigger logic, debugging difficulty, performance overhead
  - **Verdict**: Breaks separation of concerns

---

## 7. Implementation Risks & Mitigation

### High-Priority Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| **False Positive Merges** | Medium | High | Start with conservative threshold (0.80), add type constraints, log all merges for auditing |
| **Performance Overhead >2x** | Low | High | Implement type-based blocking, benchmark early, add feature flag for rollback |
| **Threshold Tuning Difficulty** | High | Medium | Provide clear documentation with examples, create test harness for threshold testing |

### Medium-Priority Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| **Domain-Specific Failures** | Medium | Medium | Make weights configurable, collect domain-specific test datasets |
| **Memory Spikes with Large Batches** | Low | Medium | Cap batch size at 200 entities, implement streaming for very large documents |

---

## 8. Test Dataset Requirements

### Required Test Cases (from `dupes.md` Appendix A)

1. **Exact Duplicates**: "Warren Home" × 2 → 1 entity
2. **Substring Variations**: "Warren State Home and Training School", "Warren Home", "Warren State Home" → 1 entity
3. **Abbreviations**: "Massachusetts Institute of Technology", "MIT" → 1 entity
4. **Different Entities**: "Warren Home", "Warwick Home" → 2 entities (no merge)
5. **Person Names**: "James Gordon", "Jim Gordon", "Commissioner Gordon" → 1 entity

### Test Data Sources

1. **Flores para Algernon** (Portuguese literary text): Contains character name variations
2. **Academic papers corpus**: Contains institution name abbreviations
3. **News articles**: Contains organization acronyms and full names

---

## Next Steps

1. ✅ **Complete**: Research findings documented
2. ⏭️ **Next**: Generate `data-model.md` (Phase 1)
3. ⏭️ **Next**: Generate `contracts/EntityResolver.interface.md` (Phase 1)
4. ⏭️ **Next**: Generate `quickstart.md` (Phase 1)
5. ⏭️ **Next**: Run agent context update
6. ⏭️ **Next**: Begin TDD implementation with `EntitySimilarityCalculatorTest.java`

---

## References

- **Primary Reference**: `dupes.md` (detailed implementation guidance)
- **Specification**: `specs/001-semantic-entity-dedup/spec.md`
- **Implementation Plan**: `specs/001-semantic-entity-dedup/plan.md`
- **Algorithm Papers**:
  - Levenshtein Distance: Original paper (1966)
  - Jaccard Similarity: "On the Generalized Distance in Statistics" (1901)
  - Connected Components: Tarjan's algorithm (1972)
