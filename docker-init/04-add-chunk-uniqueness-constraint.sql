-- Add unique constraint to prevent duplicate chunks for same document
-- This migration adds a partial unique index on (document_id, chunk_index, type)
-- Only applies to chunks (type='chunk') with non-NULL document_id
-- This prevents the race condition that caused 58x duplicate chunk insertions

-- Drop existing constraint if it exists (for idempotency)
DROP INDEX IF EXISTS ag_catalog.uk_lightrag_vectors_chunk_unique;

-- Create partial unique index for chunks
-- This prevents inserting duplicate (document_id, chunk_index) pairs
-- Entities are excluded (they have NULL chunk_index)
CREATE UNIQUE INDEX uk_lightrag_vectors_chunk_unique
ON ag_catalog.lightrag_vectors (document_id, chunk_index, type)
WHERE type = 'chunk' AND document_id IS NOT NULL;

-- Display success message
DO $$
BEGIN
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Added Chunk Uniqueness Constraint';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Constraint: uk_lightrag_vectors_chunk_unique';
    RAISE NOTICE 'Protects: (document_id, chunk_index, type)';
    RAISE NOTICE 'Applies to: chunks only (type = ''chunk'')';
    RAISE NOTICE 'Purpose: Prevent duplicate chunk insertions';
    RAISE NOTICE '========================================';
END
$$;
