# Data Model: SQLite Storage Port

**Feature Branch**: `009-sqlite-storage-port`  
**Created**: 2024-12-13

## Overview

This document defines the SQLite database schema for the storage port, including tables for vectors, graph data, extraction cache, key-value storage, and document status.

---

## 1. Database Configuration

### SQLite Pragmas (Applied on Connection)

```sql
PRAGMA journal_mode = WAL;           -- Write-Ahead Logging for concurrent reads
PRAGMA synchronous = NORMAL;         -- Balance durability and performance
PRAGMA cache_size = -64000;          -- 64MB page cache
PRAGMA busy_timeout = 30000;         -- 30 second timeout for locks
PRAGMA foreign_keys = ON;            -- Enforce foreign key constraints
PRAGMA temp_store = MEMORY;          -- Store temp tables in memory
```

---

## 2. Core Tables

### 2.1 Schema Version

Tracks database schema version for migrations.

```sql
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL DEFAULT (datetime('now')),
    description TEXT
);

-- Initial version
INSERT INTO schema_version (version, description) 
VALUES (1, 'Initial SQLite storage schema');
```

### 2.2 Projects

Stores project metadata for multi-tenancy.

```sql
CREATE TABLE IF NOT EXISTS projects (
    id TEXT PRIMARY KEY,              -- UUID v7 as text
    name TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_projects_created_at ON projects(created_at);
```

### 2.3 Documents

Stores document content and metadata.

```sql
CREATE TABLE IF NOT EXISTS documents (
    id TEXT PRIMARY KEY,              -- UUID v7 as text
    project_id TEXT NOT NULL,
    type TEXT NOT NULL,               -- PDF, DOCX, TXT, HTML, etc.
    status TEXT NOT NULL DEFAULT 'NOT_PROCESSED',  -- NOT_PROCESSED, PROCESSING, PROCESSED, FAILED
    file_name TEXT,
    content TEXT,                     -- Full document text
    metadata TEXT,                    -- JSON metadata
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

CREATE INDEX idx_documents_project_id ON documents(project_id);
CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_project_status ON documents(project_id, status);
```

---

## 3. Vector Storage Tables

### 3.1 Vectors Table

Stores embedding vectors with metadata.

```sql
CREATE TABLE IF NOT EXISTS vectors (
    id TEXT PRIMARY KEY,              -- UUID v7 as text
    project_id TEXT NOT NULL,
    type TEXT NOT NULL,               -- 'chunk', 'entity', 'relation'
    content TEXT NOT NULL,            -- Chunk text or entity name
    vector BLOB NOT NULL,             -- Float32 vector as binary blob
    document_id TEXT,                 -- Optional FK to documents
    chunk_index INTEGER,              -- Position in document
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

-- Indexes for filtering
CREATE INDEX idx_vectors_project_id ON vectors(project_id);
CREATE INDEX idx_vectors_type ON vectors(type);
CREATE INDEX idx_vectors_project_type ON vectors(project_id, type);
CREATE INDEX idx_vectors_document_id ON vectors(document_id);
```

### 3.2 Vector Initialization (via sqlite-vector)

On startup, initialize the vector extension:

```sql
-- Load extension
SELECT load_extension('/path/to/vector');

-- Initialize vector column (call per connection)
SELECT vector_init('vectors', 'vector', 
    'type=FLOAT32,dimension=384,distance=COSINE');

-- After bulk insert, quantize for fast search
SELECT vector_quantize('vectors', 'vector');
SELECT vector_quantize_preload('vectors', 'vector');
```

---

## 4. Graph Storage Tables (sqlite-graph)

### 4.1 Graph Virtual Table

Create graph virtual table with backing tables.

```sql
-- Load extension
SELECT load_extension('/path/to/libgraph');

-- Create virtual table
CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_graph USING graph();
```

This automatically creates backing tables:
- `knowledge_graph_nodes(id INTEGER, properties TEXT, labels TEXT)`
- `knowledge_graph_edges(id INTEGER, source INTEGER, target INTEGER, edge_type TEXT, properties TEXT)`

### 4.2 Entity Node Schema (within graph)

Properties stored as JSON in `knowledge_graph_nodes.properties`:

```json
{
    "name": "Entity Name",
    "entity_type": "PERSON|ORGANIZATION|LOCATION|...",
    "description": "Entity description text",
    "document_id": "uuid-of-source-document",
    "source_chunk_ids": ["chunk-uuid-1", "chunk-uuid-2"],
    "project_id": "uuid-of-project"
}
```

Node labels: `Entity`

### 4.3 Relation Edge Schema (within graph)

Properties stored as JSON in `knowledge_graph_edges.properties`:

```json
{
    "description": "Relationship description",
    "keywords": "keyword1, keyword2",
    "weight": 1.0,
    "document_id": "uuid-of-source-document",
    "source_chunk_ids": ["chunk-uuid-1"],
    "project_id": "uuid-of-project"
}
```

Edge type: `RELATED_TO`

### 4.4 Graph Operations Examples

```sql
-- Create entity node
SELECT cypher_execute('
    CREATE (e:Entity {
        name: "Alice", 
        entity_type: "PERSON",
        description: "A researcher",
        project_id: "project-uuid"
    })
');

-- Create relationship
SELECT cypher_execute('
    MATCH (a:Entity {name: "Alice", project_id: "p1"}),
          (b:Entity {name: "Bob", project_id: "p1"})
    CREATE (a)-[:RELATED_TO {
        description: "colleagues",
        keywords: "work, research",
        weight: 0.8,
        project_id: "p1"
    }]->(b)
');

-- Query entities by project
SELECT cypher_execute('
    MATCH (e:Entity)
    WHERE e.project_id = "project-uuid"
    RETURN e
');

-- Get relations for entity
SELECT cypher_execute('
    MATCH (e:Entity {name: "Alice", project_id: "p1"})-[r:RELATED_TO]-(other)
    RETURN r, other
');
```

---

## 5. Extraction Cache Table

Caches LLM extraction results for rebuild without re-calling LLM.

```sql
CREATE TABLE IF NOT EXISTS extraction_cache (
    id TEXT PRIMARY KEY,              -- UUID v7
    project_id TEXT NOT NULL,
    cache_type TEXT NOT NULL,         -- ENTITY_EXTRACTION, GLEANING, SUMMARIZATION, KEYWORD_EXTRACTION
    chunk_id TEXT,                    -- Optional FK to vectors
    content_hash TEXT NOT NULL,       -- SHA-256 of input
    result TEXT NOT NULL,             -- Raw LLM response (JSON)
    tokens_used INTEGER,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    UNIQUE (project_id, cache_type, content_hash)
);

CREATE INDEX idx_extraction_cache_project ON extraction_cache(project_id);
CREATE INDEX idx_extraction_cache_chunk ON extraction_cache(chunk_id);
CREATE INDEX idx_extraction_cache_lookup ON extraction_cache(project_id, cache_type, content_hash);
```

---

## 6. Key-Value Storage Table

General-purpose key-value storage.

```sql
CREATE TABLE IF NOT EXISTS kv_store (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_kv_store_prefix ON kv_store(key);
```

---

## 7. Document Status Table

Tracks document processing status.

```sql
CREATE TABLE IF NOT EXISTS document_status (
    doc_id TEXT PRIMARY KEY,
    processing_status TEXT NOT NULL,  -- PENDING, PROCESSING, COMPLETED, FAILED
    chunk_count INTEGER DEFAULT 0,
    entity_count INTEGER DEFAULT 0,
    relation_count INTEGER DEFAULT 0,
    error_message TEXT,
    started_at TEXT,
    completed_at TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_doc_status_status ON document_status(processing_status);
```

---

## 8. Entity Relationships

```
┌─────────────┐     1:N     ┌────────────┐
│  projects   │────────────▶│ documents  │
└─────────────┘             └────────────┘
       │                          │
       │ 1:N                      │ 1:N
       ▼                          ▼
┌─────────────┐             ┌────────────┐
│  vectors    │◀────────────│   (FK)     │
└─────────────┘             └────────────┘
       │
       │ 1:N
       ▼
┌──────────────────┐
│ extraction_cache │
└──────────────────┘

┌─────────────────────────────────────────┐
│          knowledge_graph (virtual)       │
│  ┌────────────────────────────────────┐ │
│  │ knowledge_graph_nodes (entities)   │ │
│  │   - project_id in properties       │ │
│  └────────────────────────────────────┘ │
│  ┌────────────────────────────────────┐ │
│  │ knowledge_graph_edges (relations)  │ │
│  │   - project_id in properties       │ │
│  └────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

---

## 9. Data Type Mappings

### PostgreSQL to SQLite

| PostgreSQL Type | SQLite Type | Notes |
|-----------------|-------------|-------|
| UUID | TEXT | Store as hyphenated string |
| TIMESTAMP | TEXT | ISO 8601 format |
| JSONB | TEXT | Store as JSON string |
| halfvec(N) | BLOB | Float32 array via sqlite-vector |
| agtype | TEXT | JSON via sqlite-graph |
| BOOLEAN | INTEGER | 0 = false, 1 = true |
| SERIAL | INTEGER | SQLite ROWID auto-increment |

### Vector Storage Comparison

| Aspect | PostgreSQL (pgvector) | SQLite (sqlite-vector) |
|--------|----------------------|------------------------|
| Vector type | halfvec(4096) | BLOB (Float32) |
| Index type | HNSW | Quantization |
| Distance | `<=>` operator | vector_quantize_scan |
| Dimension | Configurable | Configurable |

---

## 10. Migration Scripts

### Migration 1: Initial Schema

```sql
-- Applied on first startup
-- Creates all tables defined above
```

### Migration 2: Add indexes (example future migration)

```sql
-- Example: Add composite index for common query pattern
CREATE INDEX IF NOT EXISTS idx_vectors_project_type_doc 
    ON vectors(project_id, type, document_id);
```

---

## 11. Cleanup Operations

### Delete Project (Cascades)

```sql
-- Foreign keys cascade, so just:
DELETE FROM projects WHERE id = ?;

-- For graph data (not cascaded):
SELECT cypher_execute('
    MATCH (e:Entity) WHERE e.project_id = "project-uuid" DELETE e
');
```

### Delete Document

```sql
-- Foreign keys cascade vectors
DELETE FROM documents WHERE id = ?;

-- Clean graph data
SELECT cypher_execute('
    MATCH (e:Entity) WHERE e.document_id = "doc-uuid" DELETE e
');
```

---

## 12. Performance Considerations

1. **Batch Inserts**: Use transactions for bulk inserts
   ```sql
   BEGIN;
   INSERT INTO vectors ...;
   INSERT INTO vectors ...;
   COMMIT;
   ```

2. **Vector Quantization**: Call after bulk inserts
   ```sql
   SELECT vector_quantize('vectors', 'vector');
   ```

3. **WAL Checkpointing**: Periodic checkpoint for large writes
   ```sql
   PRAGMA wal_checkpoint(TRUNCATE);
   ```

4. **Index Maintenance**: VACUUM after large deletes
   ```sql
   VACUUM;
   ```
