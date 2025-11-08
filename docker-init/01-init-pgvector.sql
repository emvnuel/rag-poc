-- Initialize pgvector extension
-- This script runs second to set up vector similarity search capabilities

-- Note: We need to create this before AGE to avoid conflicts
-- This script will actually run first (see 01-init-extensions.sql)
CREATE EXTENSION IF NOT EXISTS vector;

-- Display success message
DO $$
BEGIN
    RAISE NOTICE 'pgvector extension initialized successfully';
    RAISE NOTICE 'Vector similarity search capabilities are now available';
END
$$;
