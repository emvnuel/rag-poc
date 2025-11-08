-- Initialize Apache AGE extension
-- This script runs after pgvector to set up graph database capabilities

-- Create AGE extension (if not already exists)
CREATE EXTENSION IF NOT EXISTS age;

-- Load AGE extension into memory
LOAD 'age';

-- Set search path for current session to include ag_catalog
SET search_path = ag_catalog, "$user", public;

-- Set search path at database level for future sessions
ALTER DATABASE ragsaas SET search_path = ag_catalog, "$user", public;

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
