# Research: LightRAG Official Implementation Alignment

**Feature**: 006-lightrag-official-impl  
**Date**: 2025-11-25  
**Purpose**: Resolve technical unknowns and document design decisions before implementation

## Research Topics

### 1. Iterative Gleaning Implementation

**Question**: How should gleaning (multiple extraction passes) be implemented to capture missed entities?

**Official LightRAG Approach** (operate.py):
```python
# Initial extraction
entities_data, relations_data = await _extract_entities_and_relations(chunk)

# Gleaning loop - continue until no new entities found or max iterations
for glean_index in range(config.entity_extract_max_gleaning):
    glean_prompt = f"Many entities and relations were missed. Add them below:\n{chunk}"
    new_entities, new_relations = await _extract_entities_and_relations(glean_prompt)
    
    if not new_entities and not new_relations:
        break  # No more to extract
    
    entities_data.extend(new_entities)
    relations_data.extend(new_relations)
```

**Decision**: Implement configurable gleaning with default of 1 additional pass
- Configuration: `lightrag.extraction.gleaning.max-passes=1`
- Gleaning prompt emphasizes missed entities
- Stop early if gleaning returns empty results (save LLM costs)
- Cache both initial and gleaning results separately for rebuild

**Rationale**: 
- Gleaning improves extraction recall by 15-30% for complex documents (from LightRAG benchmarks)
- Single gleaning pass is the sweet spot for cost/quality tradeoff
- Configurable allows tuning for different use cases

**Alternatives Rejected**:
- No gleaning (current): Misses implicit entities mentioned in context
- Fixed 3 passes: Too expensive for most documents, diminishing returns after 1-2 passes

---

### 2. Description Merging Strategy

**Question**: How should entity descriptions from multiple chunks be merged?

**Official LightRAG Approach** (operate.py):
```python
async def _merge_descriptions(descriptions: list[str], max_tokens: int) -> str:
    if sum_tokens(descriptions) < max_tokens:
        return "\n".join(descriptions)  # Simple concatenation under threshold
    
    # LLM-based map-reduce summarization
    summaries = []
    for batch in batch_descriptions(descriptions, batch_size=10):
        summary = await llm_summarize(batch)
        summaries.append(summary)
    
    return await llm_summarize(summaries)  # Final reduce step
```

**Decision**: Implement threshold-based description merging
1. **Under threshold** (default 500 tokens): Concatenate with separator `" | "`
2. **Over threshold**: Use LLM summarization with map-reduce for very long lists
3. Configuration: `lightrag.entity.description.max-tokens=500`

**Rationale**:
- Concatenation is fast and preserves all information for short descriptions
- LLM summarization produces coherent descriptions for entities mentioned many times
- Map-reduce handles unbounded description growth from large document sets

**Alternatives Rejected**:
- Always concatenate: Produces incoherent multi-page descriptions for popular entities
- Always summarize: Expensive for simple cases, loses detail for entities with few mentions

---

### 3. Keyword Extraction for Query Routing

**Question**: How should high-level and low-level keywords be extracted from queries?

**Official LightRAG Approach** (operate.py):
```python
KEYWORD_EXTRACTION_PROMPT = """
Given the query: {query}

Extract keywords that would help find relevant information:

1. HIGH-LEVEL keywords: Abstract concepts, themes, relationships, patterns
   Examples: "evolution of AI", "impact on society", "relationship between X and Y"

2. LOW-LEVEL keywords: Specific entities, names, technical terms, concrete nouns
   Examples: "GPT-4", "OpenAI", "transformer architecture"

Output format:
HIGH_LEVEL: keyword1, keyword2, keyword3
LOW_LEVEL: entity1, entity2, entity3
"""
```

**Decision**: Implement LLM-based keyword extraction with caching
1. Create `KeywordExtractor` class with prompt template matching official format
2. Parse HIGH_LEVEL/LOW_LEVEL from response
3. Cache results by query hash (queries often repeat)
4. Configuration: `lightrag.query.keyword-extraction.cache-ttl=3600`

**Rationale**:
- LLM understands semantic intent better than regex/NLP heuristics
- High-level keywords find relationships, low-level find entities (proper routing)
- Caching avoids redundant LLM calls for repeated queries

**Alternatives Rejected**:
- Named Entity Recognition (NER): Only extracts low-level, misses thematic concepts
- TF-IDF keywords: Statistical approach misses semantic intent

---

### 4. Round-Robin Context Merging

**Question**: How should results from different sources be merged for hybrid/mix queries?

**Official LightRAG Approach** (operate.py):
```python
def _interleave_results(sources: list[list[str]], max_items: int) -> list[str]:
    """Round-robin merge from multiple sources."""
    result = []
    source_iters = [iter(s) for s in sources]
    
    while len(result) < max_items and source_iters:
        for i, it in enumerate(source_iters[:]):
            try:
                item = next(it)
                result.append(item)
                if len(result) >= max_items:
                    break
            except StopIteration:
                source_iters.remove(it)
    
    return result
```

**Decision**: Implement round-robin interleaving utility
1. Create `ContextMerger` utility class
2. Round-robin from: entity results, relation results, chunk results
3. Apply token budget during merge (truncate at limit)

**Rationale**:
- Ensures diversity - doesn't over-represent any single source
- Respects token limits - stops when budget exhausted
- Simple algorithm that matches official behavior

**Alternatives Rejected**:
- Concatenate all sources: May exhaust token budget on first source
- Score-based ranking: More complex, doesn't guarantee source diversity

---

### 5. Source Chunk Tracking

**Question**: How should chunk provenance be tracked for entities and relations?

**Official LightRAG Approach** (storage):
```python
# Entity storage schema
entity_schema = {
    "name": str,
    "type": str,
    "description": str,
    "source_ids": list[str],  # Chunk IDs that contributed
    "file_paths": list[str],   # Source document paths
}

# On entity update
existing.source_ids = existing.source_ids + [new_chunk_id]
existing.source_ids = existing.source_ids[:max_source_ids]  # FIFO limit
```

**Decision**: Add source tracking fields to Entity and Relation models
1. `sourceIds: List<String>` - chunk UUIDs that contributed to this entity
2. `filePaths: List<String>` - source document paths for citation
3. Configuration: `lightrag.entity.max-source-ids=50` (FIFO when exceeded)

**Rationale**:
- Enables document deletion with proper entity/relation rebuild
- Provides citation trail for generated responses
- FIFO limit prevents unbounded growth for frequently-mentioned entities

**Alternatives Rejected**:
- No tracking: Cannot rebuild on deletion, no citations
- Unlimited tracking: Memory explosion for popular entities

---

### 6. LLM Cache Storage Schema

**Question**: What schema should be used for caching extraction results?

**Official LightRAG Approach** (storage):
```python
llm_cache_schema = {
    "cache_type": str,  # "entity_extraction", "gleaning", "summarization"
    "chunk_id": str,    # Reference to source chunk
    "content_hash": str, # Hash of input for cache invalidation
    "result": str,       # Raw LLM response
    "created_at": datetime,
    "tokens_used": int,
}
```

**Decision**: Create `ExtractionCache` table in PostgreSQL
```sql
CREATE TABLE rag.extraction_cache (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES rag.projects(id) ON DELETE CASCADE,
    cache_type VARCHAR(50) NOT NULL,  -- entity_extraction, gleaning, summarization, keywords
    chunk_id UUID REFERENCES rag.vectors(id) ON DELETE SET NULL,
    content_hash VARCHAR(64) NOT NULL,  -- SHA-256 of input
    result TEXT NOT NULL,
    tokens_used INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, cache_type, content_hash)
);
```

**Rationale**:
- Relational storage integrates with existing PostgreSQL infrastructure
- Foreign key to project enables cascade delete
- Content hash enables cache reuse across identical inputs
- Unique constraint prevents duplicate cache entries

**Alternatives Rejected**:
- Redis/external cache: Adds operational complexity, not persistent
- KV storage only: Harder to query/manage, no foreign key benefits

---

### 7. Batch Operations for Graph Storage

**Question**: How should batch operations be implemented for improved performance?

**From IMPLEMENTATION-COMPARISON.md** - Official has:
- `get_nodes_batch(node_ids, batch_size=1000)`
- `node_degrees_batch(node_ids, batch_size=500)`
- `get_edges_batch(pairs, batch_size=500)`

**Decision**: Add batch methods to GraphStorage interface
```java
// New batch methods
CompletableFuture<Map<String, Entity>> getEntitiesBatch(
    String projectId, List<String> entityNames, int batchSize);

CompletableFuture<Map<String, Integer>> getNodeDegreesBatch(
    String projectId, List<String> entityNames, int batchSize);
```

Implementation uses Cypher `IN` clause for batching:
```cypher
MATCH (e:Entity) WHERE e.name IN ['Entity1', 'Entity2', ...] RETURN e
```

**Rationale**:
- Reduces database round trips (N queries → N/batchSize queries)
- Cypher IN clause is well-optimized in AGE
- Batch size limits prevent memory issues with large result sets

**Alternatives Rejected**:
- Individual queries: O(N) database calls, connection pool exhaustion
- Single mega-query: Memory issues with large entity lists

---

### 8. Connection Health Validation

**Question**: How should database connection health be validated?

**From IMPLEMENTATION-COMPARISON.md** - Official has:
```python
self._pool_reconnect_lock = asyncio.Lock()
async def _reset_pool(self) -> None:
    async with self._pool_reconnect_lock:
        if self.pool is not None:
            await asyncio.wait_for(self.pool.close(), timeout=self.pool_close_timeout)
```

**Decision**: Add connection validation to AgeConfig.getConnection()
```java
public Connection getConnection() throws SQLException {
    Connection conn = dataSource.getConnection();
    if (!conn.isValid(5)) {  // 5 second timeout
        conn.close();
        throw new SQLTransientConnectionException("Connection validation failed");
    }
    return conn;
}
```

**Rationale**:
- Quarkus DataSource doesn't validate on checkout by default
- `isValid()` is cheap (just sends ping)
- Throwing SQLTransientException triggers retry logic

**Alternatives Rejected**:
- No validation: Stale connections cause intermittent failures
- Pool reset logic: Quarkus handles pool management, just validate on checkout

---

## Summary of Decisions

| Topic | Decision | Config Property |
|-------|----------|-----------------|
| Gleaning | 1 additional pass, stop on empty | `lightrag.extraction.gleaning.max-passes=1` |
| Description Merge | Threshold-based (concat <500 tokens, LLM summarize above) | `lightrag.entity.description.max-tokens=500` |
| Keyword Extraction | LLM-based with caching | `lightrag.query.keyword-extraction.cache-ttl=3600` |
| Context Merging | Round-robin interleaving | N/A (algorithm choice) |
| Source Tracking | List fields with FIFO limit | `lightrag.entity.max-source-ids=50` |
| Cache Storage | PostgreSQL table with FK to project | N/A (schema choice) |
| Batch Operations | IN clause batching, configurable size | `lightrag.graph.batch-size=500` |
| Connection Health | isValid() check on checkout | N/A (always enabled) |

## Dependencies Identified

1. **Existing**: LLMFunction, EmbeddingFunction, AgeGraphStorage, PgVectorStorage
2. **New External**: None (all implementations use existing stack)
3. **New Internal**: KeywordExtractor, DescriptionSummarizer, ContextMerger, ExtractionCache
