-- V1.1__add_project_ownership.sql
-- Migration script to add owner_id column to projects table for Keycloak-based authorization
-- Part of spec-011-keycloak-api-auth

-- Add owner_id column to projects table
ALTER TABLE rag.projects ADD COLUMN IF NOT EXISTS owner_id VARCHAR(255);

-- Add index for authorization queries (filter by owner)
CREATE INDEX IF NOT EXISTS idx_projects_owner_id ON rag.projects(owner_id);

-- Add comment for documentation
COMMENT ON COLUMN rag.projects.owner_id IS 'Keycloak subject ID (sub claim) of the project creator. NULL for legacy projects.';

-- Note: Existing projects will have NULL owner_id
-- These legacy projects will be:
-- - Readable by all authenticated users
-- - Modifiable only by users with admin role
