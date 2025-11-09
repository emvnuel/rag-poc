-- Initialize RAG SaaS application schema and tables
-- This script runs third to create application-specific database objects

-- Create schema for organization
CREATE SCHEMA IF NOT EXISTS rag;

-- Projects table
CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    name VARCHAR(255) NOT NULL
);

-- Documents table
CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    type VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL DEFAULT 'NOT_PROCESSED',
    file_name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB,
    project_id UUID NOT NULL,
    CONSTRAINT fk_documents_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
);

-- Indexes for documents table
CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_project_id ON documents(project_id);
CREATE INDEX IF NOT EXISTS idx_documents_type ON documents(type);

-- Embeddings table with vector support (legacy - not actively used)
-- Note: LightRAG uses ag_catalog.lightrag_vectors table instead
CREATE TABLE IF NOT EXISTS embeddings (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    document_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    vector halfvec(4000) NOT NULL,
    model VARCHAR(255) NOT NULL,
    CONSTRAINT fk_embeddings_document FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT uk_embeddings_document_chunk UNIQUE (document_id, chunk_index)
);

-- Indexes for embeddings table
CREATE INDEX IF NOT EXISTS idx_embeddings_document_id ON embeddings(document_id);
CREATE INDEX IF NOT EXISTS idx_embeddings_model ON embeddings(model);

-- Vector index for similarity search (HNSW algorithm)
-- Note: Using halfvec(4000) - maximum supported dimensions with HNSW index
-- halfvec provides efficient storage (2 bytes per dimension)
-- HNSW provides fast approximate nearest neighbor search
CREATE INDEX IF NOT EXISTS idx_embeddings_vector 
ON embeddings USING hnsw (vector halfvec_cosine_ops) 
WITH (m = 16, ef_construction = 64);

-- Grant necessary permissions
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO postgres;

-- Display success message
DO $$
BEGIN
    RAISE NOTICE '========================================';
    RAISE NOTICE 'RAG SaaS Database Initialization Complete';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Created tables:';
    RAISE NOTICE '  - projects';
    RAISE NOTICE '  - documents';
    RAISE NOTICE '  - embeddings';
    RAISE NOTICE '';
    RAISE NOTICE 'Extensions enabled:';
    RAISE NOTICE '  - Apache AGE (graph database)';
    RAISE NOTICE '  - pgvector (vector similarity)';
    RAISE NOTICE '';
    RAISE NOTICE 'Database is ready for use!';
    RAISE NOTICE '========================================';
END
$$;
