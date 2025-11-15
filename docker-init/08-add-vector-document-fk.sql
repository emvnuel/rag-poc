-- Add foreign key constraint from lightrag_vectors to documents with CASCADE delete
-- This ensures that when a document is deleted, all its vectors are automatically deleted

DO $$
BEGIN
    -- Add FK constraint if it doesn't exist
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'fk_lightrag_vectors_document' 
        AND conrelid = 'ag_catalog.lightrag_vectors'::regclass
    ) THEN
        -- First, clean up any orphaned vectors (vectors without a corresponding document)
        DELETE FROM ag_catalog.lightrag_vectors 
        WHERE document_id IS NOT NULL 
        AND document_id NOT IN (SELECT id FROM ag_catalog.documents);
        
        -- Now add the constraint
        ALTER TABLE ag_catalog.lightrag_vectors 
        ADD CONSTRAINT fk_lightrag_vectors_document 
        FOREIGN KEY (document_id) REFERENCES ag_catalog.documents(id) ON DELETE CASCADE;
        
        RAISE NOTICE 'Added FK constraint fk_lightrag_vectors_document to ag_catalog.lightrag_vectors';
    ELSE
        RAISE NOTICE 'FK constraint fk_lightrag_vectors_document already exists';
    END IF;
END
$$;
