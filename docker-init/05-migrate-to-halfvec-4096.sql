-- Migration script to upgrade from vector(1024) to halfvec(4000)
-- This script migrates the lightrag_vectors table to support 4000-dimensional embeddings
-- using halfvec type for better performance and storage efficiency

-- Purpose:
--   1. Change vector column from vector(1024) to halfvec(4000)
--   2. Recreate HNSW index using halfvec_cosine_ops
--   3. Clear existing vectors (they are incompatible with new dimensions)

-- Technical Details:
--   - halfvec uses 2 bytes per dimension vs 4 bytes for vector
--   - Storage: 4000 dims * 2 bytes = ~8 KB per vector
--   - HNSW index natively supports halfvec up to 4,000 dimensions (hard limit)
--   - Slightly lower precision than full vector (acceptable for embeddings)

-- WARNING: This will delete all existing vectors as they cannot be converted
-- The documents will remain, but need to be reprocessed to generate new 4096-dim embeddings

BEGIN;

-- Step 1: Drop the existing HNSW index (required before altering column type)
DO $$
BEGIN
    DROP INDEX IF EXISTS ag_catalog.lightrag_vectors_vector_idx;
    RAISE NOTICE '[1/4] Dropped existing HNSW vector index';
EXCEPTION
    WHEN undefined_table THEN
        RAISE NOTICE '[1/4] Index does not exist, skipping';
END
$$;

-- Step 2: Truncate the table to remove incompatible 1024-dim vectors
-- This is necessary because:
--   - We cannot convert 1024-dim vectors to 4096-dim vectors
--   - The new embedding model produces different vector spaces
--   - Documents will need to be reprocessed with the new model
DO $$
DECLARE
    row_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO row_count FROM ag_catalog.lightrag_vectors;
    TRUNCATE TABLE ag_catalog.lightrag_vectors CASCADE;
    RAISE NOTICE '[2/4] Truncated table (removed % vectors - will be regenerated)', row_count;
END
$$;

-- Step 3: Alter the vector column to use halfvec(4000)
DO $$
BEGIN
    ALTER TABLE ag_catalog.lightrag_vectors 
        ALTER COLUMN vector TYPE halfvec(4000);
    RAISE NOTICE '[3/4] Changed vector column type to halfvec(4000)';
EXCEPTION
    WHEN undefined_object THEN
        RAISE EXCEPTION 'halfvec type not available - ensure pgvector 0.8.0+ is installed';
END
$$;

-- Step 4: Recreate HNSW index with halfvec_cosine_ops
-- Index parameters:
--   - m=16: Number of connections per layer (trade-off: speed vs accuracy)
--   - ef_construction=64: Size of dynamic candidate list (trade-off: build time vs accuracy)
DO $$
BEGIN
    CREATE INDEX lightrag_vectors_vector_idx 
        ON ag_catalog.lightrag_vectors 
        USING hnsw (vector halfvec_cosine_ops)
        WITH (m = 16, ef_construction = 64);
    RAISE NOTICE '[4/4] Created HNSW index with halfvec_cosine_ops';
END
$$;

COMMIT;

-- Display migration summary
DO $$
BEGIN
    RAISE NOTICE '';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Vector Migration to halfvec(4000) Complete!';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Changes:';
    RAISE NOTICE '  - Vector type: vector(1024) → halfvec(4000)';
    RAISE NOTICE '  - Storage per vector: ~4 KB → ~8 KB';
    RAISE NOTICE '  - Index ops: vector_cosine_ops → halfvec_cosine_ops';
    RAISE NOTICE '';
    RAISE NOTICE 'Next Steps:';
    RAISE NOTICE '  1. Update LIGHTRAG_VECTOR_DIMENSION=4000 in .env';
    RAISE NOTICE '  2. Update EMBEDDING_MODEL to support 4000 dims';
    RAISE NOTICE '  3. Restart application to apply new config';
    RAISE NOTICE '  4. Reprocess documents to generate new embeddings';
    RAISE NOTICE '';
    RAISE NOTICE 'All existing vectors have been removed.';
    RAISE NOTICE 'Documents are preserved and ready for reprocessing.';
    RAISE NOTICE '========================================';
END
$$;
