-- Initialize Apache AGE extension
-- This script runs after pgvector to set up graph database capabilities

-- Create AGE extension (if not already exists)
CREATE EXTENSION IF NOT EXISTS age;

-- Load AGE extension into memory
LOAD 'age';

-- NOTE: We do NOT modify search_path globally to avoid polluting application table creation
-- Application tables (projects, documents, lightrag_vectors) should go in 'public' schema
-- AGE automatically uses 'ag_catalog' schema for its own tables (ag_graph, ag_label) and graph data

-- Create helper function to ensure graph exists
CREATE OR REPLACE FUNCTION ensure_graph_exists(graph_name TEXT)
RETURNS VOID AS $$
BEGIN
    BEGIN
        PERFORM ag_catalog.create_graph(graph_name);
    EXCEPTION
        WHEN duplicate_object THEN
            -- Graph already exists, do nothing
            NULL;
    END;
END;
$$ LANGUAGE plpgsql;

-- Grant permissions for AGE catalog
GRANT ALL PRIVILEGES ON SCHEMA ag_catalog TO postgres;

-- Display success message
DO $$
BEGIN
    RAISE NOTICE 'Apache AGE extension initialized successfully';
    RAISE NOTICE 'Graph database capabilities are now available';
END
$$;
