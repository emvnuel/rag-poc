-- Migration: Remove source_id column from lightrag_vectors table
-- Reason: document_id provides sufficient tracking via foreign key constraint
-- Date: 2025-11-20

-- Drop source_id column from lightrag_vectors table if it exists
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_schema = 'rag' 
        AND table_name = 'lightrag_vectors' 
        AND column_name = 'source_id'
    ) THEN
        ALTER TABLE rag.lightrag_vectors DROP COLUMN source_id;
        RAISE NOTICE 'Dropped source_id column from lightrag_vectors';
    ELSE
        RAISE NOTICE 'source_id column does not exist, skipping';
    END IF;
END
$$;
