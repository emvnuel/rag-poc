-- Migration: 12-add-source-ids-indexes.sql
-- Purpose: Add GIN indexes for efficient source_ids lookups (spec-007)
-- Required for: Document deletion with KG regeneration, entity merging

-- GIN indexes enable efficient containment queries on array columns
-- Used by getEntitiesBySourceChunks and getRelationsBySourceChunks

-- Note: These indexes work with the source_ids array stored in Apache AGE vertex/edge properties
-- AGE stores JSON properties, so we use jsonb GIN indexing via expression indexes

-- Index for entity source_ids lookups (find entities by source chunk)
-- This enables efficient: WHERE source_ids @> ARRAY[chunk_id]::uuid[]
CREATE INDEX IF NOT EXISTS idx_lightrag_vectors_source_ids 
ON rag.lightrag_vectors USING GIN ((metadata->>'source_ids'));

-- For the Apache AGE graph, indexes are created on the graph schema
-- AGE entities and relations store source_ids in their properties

-- Extraction cache lookup index for document deletion rebuild
CREATE INDEX IF NOT EXISTS idx_extraction_cache_chunk_id 
ON rag.extraction_cache (chunk_id);

-- Document ID index for cascading deletions
CREATE INDEX IF NOT EXISTS idx_extraction_cache_document_id 
ON rag.extraction_cache (document_id);
