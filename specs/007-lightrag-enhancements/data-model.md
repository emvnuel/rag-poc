# Data Model: LightRAG Official Implementation Enhancements

**Feature**: 007-lightrag-enhancements  
**Date**: 2025-11-26  
**Source**: [spec.md](./spec.md), [research.md](./research.md)

## New Entities

### TokenUsage

Represents token consumption for a single LLM operation.

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| operationType | String | Type of operation | INGESTION, QUERY, SUMMARIZATION, KEYWORD_EXTRACTION, RERANK |
| modelName | String | LLM model used | Not null |
| inputTokens | int | Tokens sent to LLM | >= 0 |
| outputTokens | int | Tokens received from LLM | >= 0 |
| timestamp | Instant | When operation occurred | Not null |

**Notes**: Implemented as Java record (immutable). Not persisted to database - exists only in memory during request lifecycle.

---

### TokenSummary

Aggregated token usage for a session/request.

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| totalInputTokens | int | Sum of all input tokens | >= 0 |
| totalOutputTokens | int | Sum of all output tokens | >= 0 |
| byOperationType | Map<String, Integer> | Breakdown by operation type | Not null |

**Notes**: Computed from List<TokenUsage>. Exposed in response headers.

---

### MergeStrategy (Enum)

Strategy for merging entity descriptions during entity merge operations.

| Value | Description |
|-------|-------------|
| CONCATENATE | Join all descriptions with " \| " separator |
| KEEP_FIRST | Keep the first description (or target's existing) |
| KEEP_LONGEST | Keep the longest description |
| LLM_SUMMARIZE | Use LLM to create unified description |

**Notes**: Passed as parameter to merge API. Default: CONCATENATE.

---

### MergeResult

Result of an entity merge operation.

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| targetEntity | Entity | The merged entity | Not null |
| relationsRedirected | int | Count of redirected relationships | >= 0 |
| sourceEntitiesDeleted | int | Count of deleted source entities | >= 0 |
| relationsDeduped | int | Count of merged duplicate relations | >= 0 |

**Notes**: Returned from EntityMergeService. Not persisted.

---

### KnowledgeRebuildResult

Result of document deletion with KG rebuild.

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| documentId | UUID | Deleted document ID | Not null |
| entitiesDeleted | Set<String> | Fully removed entities | Not null |
| entitiesRebuilt | Set<String> | Entities with updated descriptions | Not null |
| relationsDeleted | int | Fully removed relations | >= 0 |
| relationsRebuilt | int | Relations with updated descriptions | >= 0 |
| errors | List<String> | Any errors during rebuild | Not null (may be empty) |

**Notes**: Returned from DocumentDeletionService.

---

### RerankedChunk

A chunk with its reranker relevance score.

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| chunk | Chunk | Original chunk | Not null |
| relevanceScore | double | Reranker score (0.0 - 1.0) | 0.0 <= score <= 1.0 |
| originalRank | int | Position before reranking | >= 0 |
| newRank | int | Position after reranking | >= 0 |

**Notes**: Intermediate representation during query processing.

---

### RerankerConfig

Configuration for reranker providers.

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| provider | String | Provider name (cohere, jina, none) | Not null |
| apiKey | String | API key for provider | Required if provider != none |
| modelName | String | Model to use | Provider-specific default |
| minScore | double | Minimum score threshold | 0.0 - 1.0, default 0.1 |
| timeoutMs | int | Timeout in milliseconds | > 0, default 2000 |

**Notes**: Loaded from application.properties.

---

### ExportConfig

Configuration for knowledge graph export.

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| format | String | Export format (csv, xlsx, md, txt) | Not null |
| includeVectors | boolean | Include embedding vectors | Default false |
| includeChunks | boolean | Include source chunks | Default false |
| batchSize | int | Batch size for streaming | > 0, default 1000 |
| outputPath | String | File path to write | Not null |

**Notes**: Passed to GraphExporter implementations.

---

## Modified Entities

### Entity (lightrag/core/Entity.java)

**New Methods**:

| Method | Signature | Description |
|--------|-----------|-------------|
| mergeWith | `Entity mergeWith(Entity other, MergeStrategy strategy)` | Merge descriptions using strategy |
| hasSourceChunk | `boolean hasSourceChunk(UUID chunkId)` | Check if entity sourced from chunk |
| removeSourceChunk | `Entity removeSourceChunk(UUID chunkId)` | Remove chunk from sourceIds |

---

### Relation (lightrag/core/Relation.java)

**New Methods**:

| Method | Signature | Description |
|--------|-----------|-------------|
| redirect | `Relation redirect(String oldEntityName, String newEntityName)` | Redirect src/tgt |
| mergeWith | `Relation mergeWith(Relation other)` | Merge weights and descriptions |
| isSelfLoop | `boolean isSelfLoop()` | Check if src == tgt |

---

### GraphStorage (lightrag/storage/GraphStorage.java)

**New Methods**:

| Method | Signature | Description |
|--------|-----------|-------------|
| getEntitiesBySourceChunks | `Set<String> getEntitiesBySourceChunks(UUID projectId, List<UUID> chunkIds)` | Find entities sourced from chunks |
| getRelationsBySourceChunks | `Set<String> getRelationsBySourceChunks(UUID projectId, List<UUID> chunkIds)` | Find relations sourced from chunks |
| deleteEntities | `void deleteEntities(UUID projectId, Set<String> entityNames)` | Batch delete entities |
| deleteRelations | `void deleteRelations(UUID projectId, Set<String> relationKeys)` | Batch delete relations |
| getEntitiesBatch | `List<Entity> getEntitiesBatch(UUID projectId, int offset, int limit)` | Paginated entity fetch |
| getRelationsBatch | `List<Relation> getRelationsBatch(UUID projectId, int offset, int limit)` | Paginated relation fetch |
| updateEntityDescription | `void updateEntityDescription(UUID projectId, String entityName, String description, Set<UUID> sourceIds)` | Update entity description and sources |

---

### VectorStorage (lightrag/storage/VectorStorage.java)

**New Methods**:

| Method | Signature | Description |
|--------|-----------|-------------|
| deleteEntityEmbeddings | `void deleteEntityEmbeddings(UUID projectId, Set<String> entityNames)` | Batch delete entity vectors |
| deleteChunkEmbeddings | `void deleteChunkEmbeddings(UUID projectId, Set<UUID> chunkIds)` | Batch delete chunk vectors |

---

## Database Schema Changes

### No new tables required

All new entities are either:
1. In-memory only (TokenUsage, MergeResult, KnowledgeRebuildResult)
2. Configuration-based (RerankerConfig, ExportConfig)
3. Transient (RerankedChunk)

### New indexes recommended

```sql
-- Optimize source chunk lookups for deletion
CREATE INDEX IF NOT EXISTS idx_entities_source_ids 
ON rag.entities USING GIN (source_ids);

CREATE INDEX IF NOT EXISTS idx_relations_source_ids 
ON rag.relations USING GIN (source_ids);
```

---

## State Transitions

### Entity Merge Flow

```
[Source Entities Exist] 
    │
    ▼ (validate all exist)
[Collect Relationships]
    │
    ▼ (redirect src/tgt)
[Redirect Relationships]
    │
    ▼ (same src->tgt pair)
[Deduplicate Relations]
    │
    ▼ (apply strategy)
[Merge Descriptions]
    │
    ▼ (create/update)
[Upsert Target Entity]
    │
    ▼ (remove sources)
[Delete Source Entities]
    │
    ▼
[Merge Complete]
```

### Document Deletion Flow

```
[Document Exists]
    │
    ▼ (get chunks)
[Identify Chunks]
    │
    ▼ (find entities/relations by source)
[Classify Affected]
    │
    ├──► [No remaining sources] ──► DELETE entity/relation
    │
    └──► [Partial sources remain] ──► REBUILD from cache
    │
    ▼ (delete chunks)
[Remove Chunks & Embeddings]
    │
    ▼
[Deletion Complete]
```

---

## Validation Rules

### Entity Merge

1. All source entities must exist in the same project
2. Target entity name must not be empty
3. Source entities list must not be empty
4. Cannot merge entity with itself
5. At least one source entity must have relationships (or merge has no effect)

### Document Deletion

1. Document must exist
2. Document must belong to specified project
3. User must have delete permission on project

### Export

1. Format must be one of: csv, xlsx, md, txt
2. Output path must be writable
3. Project must exist
4. Batch size must be > 0

### Reranker

1. If enabled, API key must be configured
2. Min score must be in range [0.0, 1.0]
3. Timeout must be positive
