# Research: LightRAG Official Implementation Enhancements

**Feature**: 007-lightrag-enhancements  
**Date**: 2025-11-26  
**Purpose**: Resolve technical unknowns and document design decisions before implementation

## Research Topics

### 1. Reranker API Integration

**Question**: Which reranker APIs should be supported and how should they be integrated?

**Official LightRAG Approach** (lightrag.py):
```python
# Configuration
rerank_model_func: Callable[..., object] | None = field(default=None)
min_rerank_score: float = field(default=get_env_value("MIN_RERANK_SCORE", DEFAULT_MIN_RERANK_SCORE, float))

# From rerank.py - supported providers
cohere_rerank(query, chunks, model="rerank-english-v3.0")
jina_rerank(query, chunks, model="jina-reranker-v2-base-multilingual")
ali_rerank(query, chunks)  # Aliyun
```

**Decision**: Implement RerankerService interface with Cohere and Jina implementations

1. Create `Reranker` interface:
```java
public interface Reranker {
    List<RerankedChunk> rerank(String query, List<Chunk> chunks, int topK);
    boolean isAvailable();
}
```

2. Configuration via application.properties:
```properties
lightrag.rerank.enabled=true
lightrag.rerank.provider=cohere  # or jina, none
lightrag.rerank.cohere.api-key=${COHERE_API_KEY}
lightrag.rerank.cohere.model=rerank-english-v3.0
lightrag.rerank.min-score=0.1
lightrag.rerank.fallback-timeout-ms=2000
```

3. Fallback behavior:
   - If reranker unavailable, log warning and return original order
   - Circuit breaker: Open after 5 failures, half-open after 60s
   - Timeout: 2 seconds (from spec SC-006)

**Rationale**:
- Cohere and Jina are the two most commonly used rerankers
- Interface allows future providers without code changes
- Circuit breaker prevents cascading failures when API is down

**Alternatives Rejected**:
- Local reranker (BGE): Requires model hosting, increases deployment complexity
- Hardcoded provider: Inflexible for different deployment scenarios

---

### 2. LLM-Based Description Summarization (Map-Reduce)

**Question**: How should the map-reduce description summarization be implemented?

**Official LightRAG Approach** (operate.py):
```python
async def _handle_entity_relation_summary(
    description_type, entity_or_relation_name, description_list, 
    seperator, global_config, llm_response_cache
) -> tuple[str, bool]:
    """Handle entity relation description summary using map-reduce approach.
    
    1. If total tokens < summary_context_size and len(description_list) < force_llm_summary_on_merge:
       no need to summarize
    2. If total tokens < summary_max_tokens: summarize with LLM directly
    3. Otherwise, split descriptions into chunks that fit within token limits
    4. Summarize each chunk, then recursively process the summaries
    """
```

**Decision**: Enhance existing `DescriptionSummarizer` with map-reduce pattern

1. Configuration thresholds:
```properties
# Skip summarization if below these thresholds
lightrag.description.force-summary-count=6
lightrag.description.summary-context-size=10000

# Map phase: max tokens per batch
lightrag.description.summary-max-tokens=500

# Recursion limit
lightrag.description.max-map-iterations=3
```

2. Algorithm:
```java
public String summarize(String entityName, List<String> descriptions) {
    int totalTokens = tokenUtil.countTokens(descriptions);
    
    // No summarization needed
    if (totalTokens < summaryContextSize && descriptions.size() < forceSummaryCount) {
        return String.join(" | ", descriptions);
    }
    
    // Direct summarization
    if (totalTokens < summaryMaxTokens) {
        return llmSummarize(entityName, descriptions);
    }
    
    // Map-reduce for large description sets
    return mapReduceSummarize(entityName, descriptions);
}
```

3. Map-reduce implementation:
   - Map: Batch descriptions into groups of ~500 tokens each
   - Reduce: Summarize each batch, then recursively summarize summaries
   - Stop condition: Single summary or max iterations reached

**Rationale**:
- Prevents description bloat over time (entity mentioned in 100 documents)
- Maintains semantic coherence vs simple concatenation
- Configurable thresholds for different use cases

**Alternatives Rejected**:
- Always concatenate: Produces incoherent descriptions for frequently-mentioned entities
- Fixed truncation: Loses important information arbitrarily

---

### 3. Document Deletion with Knowledge Graph Rebuild

**Question**: How should document deletion rebuild affected entities/relations?

**Official LightRAG Approach** (lightrag.py):
```python
async def adelete_by_doc_id(self, doc_id: str):
    """Delete document and intelligently rebuild affected entities/relations."""
    
    # 1. Identify affected entities and relations
    affected_entities = get_entities_by_source(doc_id)
    
    for entity in affected_entities:
        remaining_sources = entity.source_ids - {doc_id}
        
        if not remaining_sources:
            # No remaining sources - delete entirely
            entities_to_delete.add(entity.name)
        else:
            # Partial sources remain - rebuild description
            entities_to_rebuild[entity.name] = remaining_sources
    
    # 2. Use cached LLM extraction results to rebuild
    for entity_name, sources in entities_to_rebuild.items():
        cached_extractions = get_cached_extractions(sources)
        new_description = summarize_from_cache(cached_extractions)
        update_entity(entity_name, new_description, sources)
```

**Decision**: Implement `DocumentDeletionService` with rebuild strategy

1. Create deletion workflow:
```java
public class DocumentDeletionService {
    
    public KnowledgeRebuildResult deleteDocument(UUID projectId, UUID documentId) {
        // 1. Get all chunks for this document
        List<Chunk> chunks = chunkStorage.getByDocumentId(documentId);
        
        // 2. Identify affected entities/relations
        Set<String> affectedEntities = graphStorage.getEntitiesBySourceChunks(chunks);
        Set<String> affectedRelations = graphStorage.getRelationsBySourceChunks(chunks);
        
        // 3. Classify: delete vs rebuild
        Map<String, RebuildDecision> decisions = classifyEntities(affectedEntities, chunks);
        
        // 4. Execute deletions
        deleteOrphanedEntities(decisions.entrySet().stream()
            .filter(e -> e.getValue() == RebuildDecision.DELETE)
            .map(Map.Entry::getKey)
            .collect(toSet()));
        
        // 5. Rebuild partial entities using cached extractions
        rebuildPartialEntities(decisions.entrySet().stream()
            .filter(e -> e.getValue() == RebuildDecision.REBUILD)
            .collect(toMap(Map.Entry::getKey, e -> e.getValue().remainingSources)));
        
        // 6. Delete chunks and document
        chunkStorage.deleteByDocumentId(documentId);
        
        return new KnowledgeRebuildResult(deleted, rebuilt, errors);
    }
}
```

2. Rebuild uses cached extractions (from spec-006):
```java
private void rebuildPartialEntities(Map<String, Set<UUID>> entitiesToRebuild) {
    for (Map.Entry<String, Set<UUID>> entry : entitiesToRebuild.entrySet()) {
        String entityName = entry.getKey();
        Set<UUID> remainingChunks = entry.getValue();
        
        // Get cached extractions for remaining chunks
        List<ExtractionCache> cached = cacheStorage.getByChunkIds(remainingChunks);
        
        // Re-extract descriptions from cache
        List<String> descriptions = cached.stream()
            .map(c -> extractEntityDescription(c.getResult(), entityName))
            .filter(Objects::nonNull)
            .collect(toList());
        
        // Summarize and update
        String newDescription = descriptionSummarizer.summarize(entityName, descriptions);
        graphStorage.updateEntityDescription(entityName, newDescription, remainingChunks);
    }
}
```

**Rationale**:
- Maintains KG integrity after document deletion
- Avoids re-calling LLM by using cached extraction results
- Handles both delete (orphaned) and rebuild (shared) cases

**Alternatives Rejected**:
- Simple cascade delete: Would remove entities mentioned in other documents
- No rebuild: Would leave stale descriptions referencing deleted content

---

### 4. Entity Merging Implementation

**Question**: How should entity merging handle relationships and descriptions?

**Official LightRAG Approach** (lightrag.py):
```python
async def amerge_entities(
    source_entities: list[str],
    target_entity: str,
    merge_strategy: dict = None,
    target_entity_data: dict = None
):
    """Merge multiple source entities into a single target entity."""
    
    # 1. Collect all relationships from source entities
    all_relations = []
    for source in source_entities:
        relations = get_relations_for_entity(source)
        all_relations.extend(relations)
    
    # 2. Redirect relationships to target
    for relation in all_relations:
        if relation.src_id in source_entities:
            relation.src_id = target_entity
        if relation.tgt_id in source_entities:
            relation.tgt_id = target_entity
        
        # Skip self-loops
        if relation.src_id == relation.tgt_id:
            continue
        
        upsert_relation(relation)
    
    # 3. Merge descriptions based on strategy
    descriptions = [get_entity_description(e) for e in source_entities]
    merged_description = apply_merge_strategy(descriptions, merge_strategy)
    
    # 4. Create/update target entity
    upsert_entity(target_entity, merged_description, ...)
    
    # 5. Delete source entities
    for source in source_entities:
        delete_entity(source)
```

**Decision**: Implement `EntityMergeService` with configurable strategies

1. Merge strategies enum:
```java
public enum MergeStrategy {
    CONCATENATE,    // Join all descriptions with separator
    KEEP_FIRST,     // Keep first (or target's existing) description
    KEEP_LONGEST,   // Keep the longest description
    LLM_SUMMARIZE   // Use LLM to create unified description
}
```

2. Merge workflow:
```java
public MergeResult mergeEntities(
    UUID projectId,
    List<String> sourceEntities,
    String targetEntity,
    MergeStrategy descriptionStrategy,
    Map<String, Object> targetEntityData
) {
    // 1. Validate all source entities exist
    validateEntitiesExist(projectId, sourceEntities);
    
    // 2. Collect all relationships
    List<Relation> allRelations = new ArrayList<>();
    for (String source : sourceEntities) {
        allRelations.addAll(graphStorage.getRelationsForEntity(projectId, source));
    }
    
    // 3. Redirect relationships, preventing self-loops
    List<Relation> redirected = redirectRelationships(allRelations, sourceEntities, targetEntity);
    
    // 4. Deduplicate relationships (same src->tgt pair)
    List<Relation> deduped = deduplicateRelations(redirected);
    
    // 5. Merge descriptions
    List<String> descriptions = sourceEntities.stream()
        .map(e -> graphStorage.getEntity(projectId, e))
        .map(Entity::getDescription)
        .collect(toList());
    String mergedDescription = applyStrategy(descriptionStrategy, descriptions, targetEntity);
    
    // 6. Persist changes in transaction
    return executeInTransaction(() -> {
        // Create/update target entity
        Entity target = createOrUpdateTarget(targetEntity, mergedDescription, targetEntityData);
        
        // Save redirected relations
        graphStorage.saveRelations(projectId, deduped);
        
        // Delete source entities and their embeddings
        for (String source : sourceEntities) {
            graphStorage.deleteEntity(projectId, source);
            vectorStorage.deleteEntityEmbedding(projectId, source);
        }
        
        return new MergeResult(target, deduped.size(), sourceEntities.size());
    });
}
```

3. Relationship deduplication:
```java
private List<Relation> deduplicateRelations(List<Relation> relations) {
    Map<String, Relation> byPair = new HashMap<>();
    
    for (Relation r : relations) {
        String key = r.getSrcId() + "->" + r.getTgtId();
        
        if (byPair.containsKey(key)) {
            // Merge: combine weights, concatenate descriptions
            Relation existing = byPair.get(key);
            existing.setWeight(existing.getWeight() + r.getWeight());
            existing.setDescription(existing.getDescription() + " | " + r.getDescription());
        } else {
            byPair.put(key, r);
        }
    }
    
    return new ArrayList<>(byPair.values());
}
```

**Rationale**:
- Handles all edge cases: self-loops, duplicate relations, missing entities
- Configurable strategy allows different use cases
- Transaction ensures atomicity

**Alternatives Rejected**:
- No deduplication: Would create redundant relationships
- Hard-coded strategy: Inflexible for different domains

---

### 5. Token Usage Tracking

**Question**: How should token usage be tracked and exposed?

**Official LightRAG Approach** (utils.py):
```python
class TokenTracker:
    def __init__(self):
        self.reset()
    
    def reset(self):
        self._usage = {"input_tokens": 0, "output_tokens": 0}
    
    def add(self, input_tokens: int, output_tokens: int):
        self._usage["input_tokens"] += input_tokens
        self._usage["output_tokens"] += output_tokens
    
    def get_usage(self) -> dict:
        return self._usage.copy()
    
    # Context manager support
    def __enter__(self): return self
    def __exit__(self, *args): pass
```

**Decision**: Implement `TokenTracker` as request-scoped CDI bean

1. Token usage model:
```java
public record TokenUsage(
    String operationType,  // INGESTION, QUERY, SUMMARIZATION, KEYWORD_EXTRACTION
    String modelName,
    int inputTokens,
    int outputTokens,
    Instant timestamp
) {}
```

2. TokenTracker implementation:
```java
@RequestScoped
public class TokenTracker {
    private final List<TokenUsage> usages = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalInput = new AtomicInteger(0);
    private final AtomicInteger totalOutput = new AtomicInteger(0);
    
    public void track(TokenUsage usage) {
        usages.add(usage);
        totalInput.addAndGet(usage.inputTokens());
        totalOutput.addAndGet(usage.outputTokens());
    }
    
    public TokenSummary getSummary() {
        return new TokenSummary(
            totalInput.get(),
            totalOutput.get(),
            usages.stream()
                .collect(groupingBy(TokenUsage::operationType, summingInt(u -> u.inputTokens() + u.outputTokens())))
        );
    }
    
    public void reset() {
        usages.clear();
        totalInput.set(0);
        totalOutput.set(0);
    }
}
```

3. Integration with LLM calls:
```java
// In QuarkusLLMAdapter
public String complete(String prompt, String systemPrompt) {
    int inputTokens = tokenUtil.countTokens(prompt + systemPrompt);
    
    String response = llmClient.complete(prompt, systemPrompt);
    
    int outputTokens = tokenUtil.countTokens(response);
    tokenTracker.track(new TokenUsage("LLM_COMPLETION", modelName, inputTokens, outputTokens, Instant.now()));
    
    return response;
}
```

4. Expose via response headers:
```java
@GET @Path("/chat")
public Response chat(ChatRequest request) {
    String response = chatService.chat(request);
    
    TokenSummary summary = tokenTracker.getSummary();
    return Response.ok(response)
        .header("X-Token-Input", summary.totalInput())
        .header("X-Token-Output", summary.totalOutput())
        .build();
}
```

**Rationale**:
- Request-scoped ensures isolation between concurrent requests
- Thread-safe for parallel LLM calls during batch processing
- Headers provide token info without changing response body

**Alternatives Rejected**:
- Global singleton: Would mix token counts across requests
- Response body field: Would break existing API contracts

---

### 6. Chunk Selection Methods (WEIGHT)

**Question**: How should weighted polling chunk selection be implemented?

**Official LightRAG Approach** (operate.py):
```python
kg_chunk_pick_method = global_config.get("kg_chunk_pick_method", "VECTOR")

if kg_chunk_pick_method == "VECTOR":
    # Vector similarity-based selection
    selected_chunk_ids = await pick_by_vector_similarity(query, entities_with_chunks)
elif kg_chunk_pick_method == "WEIGHT":
    # Weighted polling based on chunk occurrence count
    selected_chunk_ids = pick_by_weighted_polling(entities_with_chunks, max_related_chunks)

def pick_by_weighted_polling(entities_with_chunks, max_chunks):
    """Select chunks by how often they're referenced by entities."""
    chunk_counts = Counter()
    for entity in entities_with_chunks:
        for chunk_id in entity.source_ids:
            chunk_counts[chunk_id] += 1
    
    # Round-robin selection, higher count = higher priority
    selected = []
    while len(selected) < max_chunks and chunk_counts:
        top_chunk = chunk_counts.most_common(1)[0][0]
        selected.append(top_chunk)
        del chunk_counts[top_chunk]
    
    return selected
```

**Decision**: Implement ChunkSelector interface with VECTOR and WEIGHT strategies

1. Interface:
```java
public interface ChunkSelector {
    List<Chunk> selectChunks(String query, List<Entity> entities, int maxChunks);
}
```

2. WeightedChunkSelector implementation:
```java
@ApplicationScoped
@Named("weight")
public class WeightedChunkSelector implements ChunkSelector {
    
    @Override
    public List<Chunk> selectChunks(String query, List<Entity> entities, int maxChunks) {
        // Count chunk occurrences across entities
        Map<String, Integer> chunkCounts = new HashMap<>();
        for (Entity entity : entities) {
            for (String chunkId : entity.getSourceIds()) {
                chunkCounts.merge(chunkId, 1, Integer::sum);
            }
        }
        
        // Select top chunks by count
        List<String> selectedIds = chunkCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(maxChunks)
            .map(Map.Entry::getKey)
            .collect(toList());
        
        // Fetch actual chunks
        return chunkStorage.getByIds(selectedIds);
    }
}
```

3. Configuration:
```properties
lightrag.query.chunk-selection-method=vector  # or weight
lightrag.query.max-related-chunks=20
```

**Rationale**:
- WEIGHT method favors chunks referenced by multiple entities
- More likely to contain contextually important information
- Useful when entities cluster around specific document sections

**Alternatives Rejected**:
- Only VECTOR: May miss important chunks that aren't semantically similar to query
- Hardcoded method: Different queries benefit from different strategies

---

### 7. Knowledge Graph Export

**Question**: How should large graph exports be handled efficiently?

**Official LightRAG Approach** (lightrag.py):
```python
def export_data(output_file: str, file_format: str = "csv", include_vector_data: bool = False):
    """Export knowledge graph to various formats."""
    
    # Supported formats: csv, excel, md, txt
    if file_format == "csv":
        export_csv(output_file, include_vector_data)
    elif file_format == "excel":
        export_excel(output_file, include_vector_data)
    # ...
```

**Decision**: Implement streaming export with GraphExporter interface

1. Interface:
```java
public interface GraphExporter {
    void export(UUID projectId, OutputStream output, ExportConfig config);
    String getFormat();  // csv, xlsx, md
}
```

2. Streaming CSV export:
```java
@ApplicationScoped
@Named("csv")
public class CsvGraphExporter implements GraphExporter {
    
    private static final int BATCH_SIZE = 1000;
    
    @Override
    public void export(UUID projectId, OutputStream output, ExportConfig config) {
        try (PrintWriter writer = new PrintWriter(output)) {
            // Write headers
            writer.println("entity_name,entity_type,description,source_ids,file_paths");
            
            // Stream entities in batches
            int offset = 0;
            List<Entity> batch;
            while (!(batch = graphStorage.getEntitiesBatch(projectId, offset, BATCH_SIZE)).isEmpty()) {
                for (Entity entity : batch) {
                    writer.println(toCsvLine(entity, config.includeVectors()));
                }
                offset += BATCH_SIZE;
            }
            
            // Separator
            writer.println();
            writer.println("# Relations");
            writer.println("source,target,keywords,description,weight,source_ids");
            
            // Stream relations
            offset = 0;
            List<Relation> relBatch;
            while (!(relBatch = graphStorage.getRelationsBatch(projectId, offset, BATCH_SIZE)).isEmpty()) {
                for (Relation relation : relBatch) {
                    writer.println(toCsvLine(relation, config.includeVectors()));
                }
                offset += BATCH_SIZE;
            }
        }
    }
}
```

3. Excel export (Apache POI with streaming):
```java
@ApplicationScoped
@Named("xlsx")
public class ExcelGraphExporter implements GraphExporter {
    
    @Override
    public void export(UUID projectId, OutputStream output, ExportConfig config) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {  // 100 rows in memory
            // Entities sheet
            Sheet entitiesSheet = workbook.createSheet("Entities");
            writeEntities(entitiesSheet, projectId, config);
            
            // Relations sheet
            Sheet relationsSheet = workbook.createSheet("Relations");
            writeRelations(relationsSheet, projectId, config);
            
            workbook.write(output);
        }
    }
}
```

4. Configuration:
```java
public record ExportConfig(
    boolean includeVectors,
    boolean includeChunks,
    int batchSize
) {
    public static ExportConfig defaults() {
        return new ExportConfig(false, false, 1000);
    }
}
```

**Rationale**:
- Streaming prevents OOM for large graphs (50k+ entities per SC-005)
- SXSSFWorkbook uses disk-backed buffering for Excel
- Batch fetching avoids loading entire graph into memory

**Alternatives Rejected**:
- Load all into memory: Would fail for large graphs
- Single format: Would not meet FR-025 requirements

---

## Summary of Decisions

| Topic | Decision | Config Property |
|-------|----------|-----------------|
| Reranker | Cohere + Jina with circuit breaker fallback | `lightrag.rerank.provider` |
| Description Summarization | Map-reduce pattern with configurable thresholds | `lightrag.description.summary-max-tokens` |
| Document Deletion | Classify + delete/rebuild with cached extractions | N/A (service logic) |
| Entity Merging | Configurable strategies, relationship deduplication | N/A (API parameter) |
| Token Tracking | Request-scoped tracker, exposed via headers | N/A (always enabled) |
| Chunk Selection | VECTOR + WEIGHT strategies | `lightrag.query.chunk-selection-method` |
| Graph Export | Streaming with batch fetching | `lightrag.export.batch-size` |

## Dependencies Identified

1. **Existing**: LLMFunction, EmbeddingFunction, AgeGraphStorage, PgVectorStorage, ExtractionCacheStorage
2. **New External**: 
   - Apache POI 5.x (Excel export)
   - MicroProfile REST Client (Cohere/Jina APIs)
3. **New Internal**: RerankerService, DocumentDeletionService, EntityMergeService, GraphExporter, TokenTracker

## Configuration Properties Added

```properties
# Reranker
lightrag.rerank.enabled=true
lightrag.rerank.provider=cohere
lightrag.rerank.cohere.api-key=${COHERE_API_KEY}
lightrag.rerank.cohere.model=rerank-english-v3.0
lightrag.rerank.jina.api-key=${JINA_API_KEY}
lightrag.rerank.jina.model=jina-reranker-v2-base-multilingual
lightrag.rerank.min-score=0.1
lightrag.rerank.fallback-timeout-ms=2000

# Description Summarization
lightrag.description.force-summary-count=6
lightrag.description.summary-context-size=10000
lightrag.description.summary-max-tokens=500
lightrag.description.max-map-iterations=3

# Chunk Selection
lightrag.query.chunk-selection-method=vector
lightrag.query.max-related-chunks=20

# Export
lightrag.export.batch-size=1000
```
