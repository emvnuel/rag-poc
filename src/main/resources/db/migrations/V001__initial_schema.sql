-- SQLite Initial Schema for RAG-SaaS Storage
-- Version: 1
-- Created: 2024-12-13
-- Description: Initial SQLite storage schema with support for vectors, graph, cache, and KV storage

-- =============================================================================
-- Schema Version Tracking
-- =============================================================================
CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL DEFAULT (datetime('now')),
    description TEXT
);

-- =============================================================================
-- Projects Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS projects (
    id TEXT PRIMARY KEY,              -- UUID v7 as text
    name TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_projects_created_at ON projects(created_at);

-- =============================================================================
-- Documents Table
-- =============================================================================
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

CREATE INDEX IF NOT EXISTS idx_documents_project_id ON documents(project_id);
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_project_status ON documents(project_id, status);

-- =============================================================================
-- Vectors/Embeddings Table
-- NOTE: This table is created dynamically by SQLiteVectorStorage using the
-- table name from LIGHTRAG_VECTOR_TABLE_NAME env var (default: 'vectors').
-- This allows flexible naming (e.g., 'embeddings') without migration changes.
-- =============================================================================

-- =============================================================================
-- Graph Storage Tables (for sqlite-graph extension)
-- Entities and Relations stored as rows with JSON properties
-- =============================================================================

-- Entity nodes table
CREATE TABLE IF NOT EXISTS graph_entities (
    id TEXT PRIMARY KEY,              -- UUID v7 as text
    project_id TEXT NOT NULL,
    name TEXT NOT NULL,               -- Entity name (normalized to lowercase)
    entity_type TEXT NOT NULL,        -- PERSON, ORGANIZATION, LOCATION, etc.
    description TEXT,                 -- Entity description
    document_id TEXT,                 -- Source document UUID
    source_chunk_ids TEXT,            -- JSON array of chunk IDs
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    UNIQUE (project_id, name)
);

CREATE INDEX IF NOT EXISTS idx_graph_entities_project_id ON graph_entities(project_id);
CREATE INDEX IF NOT EXISTS idx_graph_entities_name ON graph_entities(name);
CREATE INDEX IF NOT EXISTS idx_graph_entities_type ON graph_entities(entity_type);
CREATE INDEX IF NOT EXISTS idx_graph_entities_document_id ON graph_entities(document_id);

-- Relation edges table
CREATE TABLE IF NOT EXISTS graph_relations (
    id TEXT PRIMARY KEY,              -- UUID v7 as text
    project_id TEXT NOT NULL,
    source_entity TEXT NOT NULL,      -- Source entity name
    target_entity TEXT NOT NULL,      -- Target entity name
    relation_type TEXT NOT NULL DEFAULT 'RELATED_TO',
    description TEXT,                 -- Relationship description
    keywords TEXT,                    -- Comma-separated keywords
    weight REAL DEFAULT 1.0,          -- Relationship weight/strength
    document_id TEXT,                 -- Source document UUID
    source_chunk_ids TEXT,            -- JSON array of chunk IDs
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    UNIQUE (project_id, source_entity, target_entity)
);

CREATE INDEX IF NOT EXISTS idx_graph_relations_project_id ON graph_relations(project_id);
CREATE INDEX IF NOT EXISTS idx_graph_relations_source ON graph_relations(source_entity);
CREATE INDEX IF NOT EXISTS idx_graph_relations_target ON graph_relations(target_entity);
CREATE INDEX IF NOT EXISTS idx_graph_relations_document_id ON graph_relations(document_id);

-- =============================================================================
-- Extraction Cache Table
-- =============================================================================
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

CREATE INDEX IF NOT EXISTS idx_extraction_cache_project ON extraction_cache(project_id);
CREATE INDEX IF NOT EXISTS idx_extraction_cache_chunk ON extraction_cache(chunk_id);
CREATE INDEX IF NOT EXISTS idx_extraction_cache_lookup ON extraction_cache(project_id, cache_type, content_hash);

-- =============================================================================
-- Key-Value Storage Table
-- =============================================================================
CREATE TABLE IF NOT EXISTS kv_store (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_kv_store_prefix ON kv_store(key);

-- =============================================================================
-- Document Status Table
-- =============================================================================
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

CREATE INDEX IF NOT EXISTS idx_doc_status_status ON document_status(processing_status);

-- =============================================================================
-- Insert Initial Schema Version
-- =============================================================================
INSERT OR IGNORE INTO schema_version (version, description) 
VALUES (1, 'Initial SQLite storage schema');
