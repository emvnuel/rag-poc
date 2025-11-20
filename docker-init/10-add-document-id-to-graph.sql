-- Migration: Add document_id tracking to Apache AGE graph entities and relations
-- This enables automatic cleanup of graph data when documents are deleted
-- Date: 2025-11-20

-- Note: Apache AGE stores vertex and edge properties as JSONB
-- The document_id property is added at application level when creating/updating entities and relations
-- This migration serves as documentation and guidance

DO $$
BEGIN
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Graph Document Tracking Configuration';
    RAISE NOTICE '========================================';
    RAISE NOTICE '';
    RAISE NOTICE 'Apache AGE Graph Document Tracking:';
    RAISE NOTICE '  - Entities now include document_id property';
    RAISE NOTICE '  - Relations now include document_id property';
    RAISE NOTICE '  - document_id links graph data to source documents';
    RAISE NOTICE '';
    RAISE NOTICE 'Cleanup Behavior:';
    RAISE NOTICE '  - When a document is deleted:';
    RAISE NOTICE '    1. All vectors are deleted (ON DELETE CASCADE FK)';
    RAISE NOTICE '    2. All graph entities with that document_id are deleted';
    RAISE NOTICE '    3. All graph relations with that document_id are deleted';
    RAISE NOTICE '';
    RAISE NOTICE 'Query Pattern for Deletion:';
    RAISE NOTICE '  MATCH (e:Entity) WHERE e.document_id = ''<uuid>'' DETACH DELETE e';
    RAISE NOTICE '  MATCH ()-[r:RELATED_TO]->() WHERE r.document_id = ''<uuid>'' DELETE r';
    RAISE NOTICE '';
    RAISE NOTICE 'Application Changes:';
    RAISE NOTICE '  - Entity model: Added documentId field';
    RAISE NOTICE '  - Relation model: Added documentId field';
    RAISE NOTICE '  - AgeGraphStorage: Stores document_id in Cypher SET clauses';
    RAISE NOTICE '  - DocumentService: Calls graph cleanup on delete';
    RAISE NOTICE '';
    RAISE NOTICE 'Migration complete - document_id tracking enabled';
    RAISE NOTICE '========================================';
END
$$;
