# Data Model: LightRAG Official Implementation Alignment

**Feature**: 006-lightrag-official-impl  
**Date**: 2025-11-25  
**Purpose**: Define entity changes and new data structures for official LightRAG alignment

## Overview

This document defines data model changes required to align with the official LightRAG implementation. Changes include:
1. **Entity/Relation enhancements** - Add source tracking fields
2. **Chunk enhancements** - Add LLM cache reference list
3. **New entities** - ExtractionCache, KeywordResult, DescriptionSummary

## Entity Changes

### Entity (Modified)

**Current fields** (no changes):
- `entityName: String` (required) - Unique name of the entity
- `entityType: String` (optional) - Category (PERSON, ORGANIZATION, etc.)
- `description: String` (required) - Accumulated description text
- `filePath: String` (optional) - Source document path
- `documentId: String` (optional) - Source document UUID

**New fields**:
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sourceChunkIds` | `List<String>` | No | UUIDs of chunks that contributed to this entity |
| `sourceFilePaths` | `List<String>` | No | File paths of source documents (for citations) |

**Validation rules**:
- `sourceChunkIds` limited to 50 entries (FIFO eviction when exceeded)
- `sourceFilePaths` limited to 50 entries (FIFO eviction when exceeded)
- Both lists maintain insertion order for rebuild priority

**Builder changes**:
```java
public Builder sourceChunkIds(@Nullable List<String> sourceChunkIds) {
    this.sourceChunkIds = sourceChunkIds;
    return this;
}

public Builder sourceFilePaths(@Nullable List<String> sourceFilePaths) {
    this.sourceFilePaths = sourceFilePaths;
    return this;
}
```

**With methods**:
```java
public Entity withSourceChunkIds(@NotNull List<String> newSourceChunkIds);
public Entity addSourceChunkId(@NotNull String chunkId);  // FIFO eviction
public Entity withSourceFilePaths(@NotNull List<String> newSourceFilePaths);
public Entity addSourceFilePath(@NotNull String filePath);  // FIFO eviction
```

---

### Relation (Modified)

**Current fields** (no changes):
- `srcId: String` (required) - Source entity name
- `tgtId: String` (required) - Target entity name
- `description: String` (required) - Relationship description
- `keywords: String` (required) - Relationship keywords
- `weight: double` - Relationship strength
- `filePath: String` (optional) - Source document path
- `documentId: String` (optional) - Source document UUID

**New fields**:
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sourceChunkIds` | `List<String>` | No | UUIDs of chunks that contributed to this relation |
| `sourceFilePaths` | `List<String>` | No | File paths of source documents (for citations) |

**Validation rules**:
- Same FIFO eviction rules as Entity (50 entry limit)

---

### Chunk (Modified)

**Current fields** (no changes):
- `content: String` (required) - Text content
- `filePath: String` (optional) - Source file path
- `chunkId: String` (required) - Unique identifier
- `tokens: int` - Token count

**New fields**:
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `fullDocId` | `String` | No | Full document UUID this chunk belongs to |
| `chunkOrderIndex` | `int` | No | Position in document (0-based) |
| `llmCacheIds` | `List<String>` | No | References to ExtractionCache entries |

**Validation rules**:
- `chunkOrderIndex >= 0`
- `llmCacheIds` tracks initial extraction + gleaning results

---

## New Entities

### ExtractionCache (New)

Stores LLM extraction results for rebuild capability.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | Yes | Primary key (UUID v7) |
| `projectId` | `UUID` | Yes | FK to project (cascade delete) |
| `cacheType` | `CacheType` (enum) | Yes | Type of cached result |
| `chunkId` | `UUID` | No | FK to source chunk (SET NULL on delete) |
| `contentHash` | `String` | Yes | SHA-256 of input content |
| `result` | `String` | Yes | Raw LLM response text |
| `tokensUsed` | `int` | No | LLM tokens consumed |
| `createdAt` | `Instant` | Yes | Creation timestamp |

**CacheType enum**:
```java
public enum CacheType {
    ENTITY_EXTRACTION,   // Initial entity/relation extraction
    GLEANING,            // Follow-up gleaning pass
    SUMMARIZATION,       // Description summarization
    KEYWORD_EXTRACTION   // Query keyword extraction
}
```

**Unique constraint**: `(projectId, cacheType, contentHash)`

**Database schema**:
```sql
CREATE TABLE rag.extraction_cache (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES rag.projects(id) ON DELETE CASCADE,
    cache_type VARCHAR(50) NOT NULL,
    chunk_id UUID REFERENCES rag.vectors(id) ON DELETE SET NULL,
    content_hash VARCHAR(64) NOT NULL,
    result TEXT NOT NULL,
    tokens_used INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, cache_type, content_hash)
);

CREATE INDEX idx_extraction_cache_project ON rag.extraction_cache(project_id);
CREATE INDEX idx_extraction_cache_chunk ON rag.extraction_cache(chunk_id);
```

---

### KeywordResult (New)

Result of keyword extraction from a query.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `highLevelKeywords` | `List<String>` | Yes | Abstract concepts, themes, relationships |
| `lowLevelKeywords` | `List<String>` | Yes | Specific entities, names, terms |
| `queryHash` | `String` | Yes | SHA-256 of original query |
| `cachedAt` | `Instant` | No | When result was cached |

**Example**:
```json
{
  "highLevelKeywords": ["character evolution", "relationship dynamics", "plot progression"],
  "lowLevelKeywords": ["Charlie Gordon", "Dr. Strauss", "Algernon"],
  "queryHash": "a1b2c3d4...",
  "cachedAt": "2025-11-25T10:30:00Z"
}
```

**Usage**:
- HIGH-LEVEL keywords route to relationship/global search
- LOW-LEVEL keywords route to entity/local search
- Query hash enables cache lookup

---

### DescriptionSummary (New)

Result of LLM-based description summarization.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `entityName` | `String` | Yes | Entity being summarized |
| `originalDescriptions` | `List<String>` | Yes | Input descriptions |
| `summarizedDescription` | `String` | Yes | LLM-generated summary |
| `inputTokens` | `int` | Yes | Total input tokens |
| `outputTokens` | `int` | Yes | Output tokens |

**Usage**:
- Created when accumulated descriptions exceed threshold
- Stored in ExtractionCache with `cacheType=SUMMARIZATION`

---

## State Transitions

### Document Processing States

```
PENDING → PROCESSING → COMPLETED
                   ↘ FAILED

On COMPLETED:
  - Chunks created with llmCacheIds populated
  - ExtractionCache entries created (initial + gleaning)
  - Entities/Relations have sourceChunkIds populated
```

### Entity Description Evolution

```
Initial extraction → description from chunk 1
        ↓
Second chunk same entity → descriptions concatenated (under threshold)
        ↓
Third chunk same entity → LLM summarization triggered (over threshold)
        ↓
Summarized description stored, ExtractionCache entry created
```

---

## Graph Storage Changes

### Entity Properties (Apache AGE)

**Current**:
```cypher
(:Entity {
  name: "Charlie Gordon",
  type: "PERSON",
  description: "..."
})
```

**Enhanced**:
```cypher
(:Entity {
  name: "Charlie Gordon",
  type: "PERSON",
  description: "...",
  source_chunk_ids: ["uuid1", "uuid2", ...],  -- JSON array stored as string
  source_file_paths: ["path1.txt", "path2.pdf", ...]
})
```

### Relation Properties (Apache AGE)

**Current**:
```cypher
[:RELATED_TO {
  keywords: "works_with",
  description: "...",
  weight: 1.0
}]
```

**Enhanced**:
```cypher
[:RELATED_TO {
  keywords: "works_with",
  description: "...",
  weight: 1.0,
  source_chunk_ids: ["uuid1", "uuid2", ...],
  source_file_paths: ["path1.txt", "path2.pdf", ...]
}]
```

---

## Configuration Properties

New application.properties entries:

```properties
# Gleaning configuration (FR-002)
lightrag.extraction.gleaning.max-passes=1
lightrag.extraction.gleaning.enabled=true

# Description merging (FR-009, FR-010)
lightrag.entity.description.max-tokens=500
lightrag.entity.description.separator=" | "

# Source tracking (FR-015, FR-016)
lightrag.entity.max-source-ids=50
lightrag.entity.source-id-strategy=FIFO

# Keyword extraction (FR-012)
lightrag.query.keyword-extraction.enabled=true
lightrag.query.keyword-extraction.cache-ttl=3600

# Token management (FR-018, FR-019, FR-020)
lightrag.query.context.max-tokens=4000
lightrag.query.context.entity-budget-ratio=0.4
lightrag.query.context.relation-budget-ratio=0.3
lightrag.query.context.chunk-budget-ratio=0.3

# Batch operations
lightrag.graph.batch-size=500
lightrag.embedding.batch-size=32
```

---

## Migration Notes

### Backward Compatibility

1. **Entity/Relation changes are additive** - New fields are nullable, existing code continues to work
2. **Chunk changes are additive** - New fields have sensible defaults
3. **ExtractionCache is new table** - No migration of existing data required
4. **Feature flags** - Gleaning, caching, keyword extraction can be disabled

### Migration Script

```sql
-- 11-add-source-tracking-fields.sql

-- Add extraction_cache table
CREATE TABLE IF NOT EXISTS rag.extraction_cache (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES rag.projects(id) ON DELETE CASCADE,
    cache_type VARCHAR(50) NOT NULL,
    chunk_id UUID REFERENCES rag.vectors(id) ON DELETE SET NULL,
    content_hash VARCHAR(64) NOT NULL,
    result TEXT NOT NULL,
    tokens_used INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, cache_type, content_hash)
);

CREATE INDEX IF NOT EXISTS idx_extraction_cache_project 
    ON rag.extraction_cache(project_id);
CREATE INDEX IF NOT EXISTS idx_extraction_cache_chunk 
    ON rag.extraction_cache(chunk_id);

-- Note: Graph schema changes (source_chunk_ids, source_file_paths) 
-- are handled at entity upsert time in AgeGraphStorage.java
-- AGE stores properties as JSON, so no schema migration needed
```
