-- Create keycloak schema for Keycloak database tables
-- This allows Keycloak to use the same PostgreSQL instance as the RAG-SaaS application
-- but with isolated tables in a separate schema.

CREATE SCHEMA IF NOT EXISTS keycloak;

-- Grant permissions to postgres user
GRANT ALL PRIVILEGES ON SCHEMA keycloak TO postgres;

-- Add comment for documentation
COMMENT ON SCHEMA keycloak IS 'Schema for Keycloak identity and access management tables';
