-- Migration: Add project_id to lightrag_vectors for proper multi-tenant isolation
-- This enables entity vectors to be filtered by project without requiring document_id

-- Add project_id column to lightrag_vectors
ALTER TABLE lightrag_vectors ADD COLUMN IF NOT EXISTS project_id UUID;

-- Add index for efficient project filtering
CREATE INDEX IF NOT EXISTS lightrag_vectors_project_idx ON lightrag_vectors (project_id);

-- Add composite index for type + project filtering (common query pattern)
CREATE INDEX IF NOT EXISTS lightrag_vectors_type_project_idx ON lightrag_vectors (type, project_id);

-- For existing chunk vectors, populate project_id from documents table
UPDATE lightrag_vectors v
SET project_id = d.project_id
FROM documents d
WHERE v.document_id = d.id
  AND v.type = 'chunk'
  AND v.project_id IS NULL;

-- Note: Existing entity vectors (if any) will have NULL project_id
-- They will be regenerated when documents are reprocessed
-- New entity vectors will have project_id set correctly during creation

-- Verify migration
DO $$
DECLARE
    chunk_count INTEGER;
    entity_count INTEGER;
    chunks_with_project INTEGER;
BEGIN
    SELECT COUNT(*) INTO chunk_count FROM lightrag_vectors WHERE type = 'chunk';
    SELECT COUNT(*) INTO entity_count FROM lightrag_vectors WHERE type = 'entity';
    SELECT COUNT(*) INTO chunks_with_project FROM lightrag_vectors WHERE type = 'chunk' AND project_id IS NOT NULL;
    
    RAISE NOTICE 'Migration complete:';
    RAISE NOTICE '  Total chunk vectors: %', chunk_count;
    RAISE NOTICE '  Chunks with project_id: %', chunks_with_project;
    RAISE NOTICE '  Total entity vectors: %', entity_count;
    RAISE NOTICE '  Note: Entity vectors need regeneration to have project_id';
END $$;
