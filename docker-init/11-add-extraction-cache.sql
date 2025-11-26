-- Migration: Add extraction_cache table for LLM result caching
-- Feature: 006-lightrag-official-impl
-- Date: 2025-11-25
-- Purpose: Enable knowledge graph rebuild without re-calling LLM

-- Create extraction_cache table
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

-- Index for project lookups
CREATE INDEX IF NOT EXISTS idx_extraction_cache_project 
    ON rag.extraction_cache(project_id);

-- Index for chunk lookups (used in rebuild operations)
CREATE INDEX IF NOT EXISTS idx_extraction_cache_chunk 
    ON rag.extraction_cache(chunk_id);

-- Index for cache type filtering
CREATE INDEX IF NOT EXISTS idx_extraction_cache_type 
    ON rag.extraction_cache(cache_type);

-- Comment on table
COMMENT ON TABLE rag.extraction_cache IS 'Stores LLM extraction results for knowledge graph rebuild capability';

-- Comments on columns
COMMENT ON COLUMN rag.extraction_cache.id IS 'Primary key (UUID v7)';
COMMENT ON COLUMN rag.extraction_cache.project_id IS 'FK to project (cascade delete)';
COMMENT ON COLUMN rag.extraction_cache.cache_type IS 'Type: ENTITY_EXTRACTION, GLEANING, SUMMARIZATION, KEYWORD_EXTRACTION';
COMMENT ON COLUMN rag.extraction_cache.chunk_id IS 'FK to source chunk (SET NULL on delete)';
COMMENT ON COLUMN rag.extraction_cache.content_hash IS 'SHA-256 of input content for cache invalidation';
COMMENT ON COLUMN rag.extraction_cache.result IS 'Raw LLM response text';
COMMENT ON COLUMN rag.extraction_cache.tokens_used IS 'LLM tokens consumed (nullable)';
COMMENT ON COLUMN rag.extraction_cache.created_at IS 'Creation timestamp';
