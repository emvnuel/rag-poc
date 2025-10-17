CREATE SCHEMA IF NOT EXISTS rag;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS documents (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    type VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL DEFAULT 'NOT_PROCESSED',
    file_name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB
);

CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);

CREATE TABLE IF NOT EXISTS embeddings (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    document_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_text TEXT NOT NULL,
    vector vector(1024) NOT NULL,
    model VARCHAR(255) NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT uk_embeddings_document_chunk UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_embeddings_document_id ON embeddings(document_id);
CREATE INDEX IF NOT EXISTS idx_embeddings_vector ON embeddings USING ivfflat (vector vector_cosine_ops) WITH (lists = 100);
