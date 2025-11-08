# PostgreSQL with PGVector and Apache AGE Setup

## Overview

This project uses a custom PostgreSQL 15 Docker image that includes two powerful extensions:
- **pgvector v0.8.1** - Vector similarity search for RAG applications
- **Apache AGE v1.5.0** - Graph database capabilities using Cypher queries

## Architecture

### Base Image
- **PostgreSQL 15** (Debian-based)
- Image size: ~1.5GB
- Base: `postgres:15` official Docker image

### Extensions Installed

#### 1. PGVector (v0.8.1)
- **Purpose**: Vector similarity search for embeddings
- **Installation**: Via PostgreSQL APT repository (stable package)
- **Vector Dimensions**: Configured for 1536 dimensions (OpenAI standard)
- **Index Type**: HNSW (Hierarchical Navigable Small World)
- **Index Parameters**: 
  - `m = 16` (number of connections per layer)
  - `ef_construction = 64` (size of dynamic candidate list)

#### 2. Apache AGE (v1.5.0)
- **Purpose**: Graph database for knowledge graphs
- **Installation**: Built from source (PG15/1.5.0 branch)
- **Query Language**: Cypher (same as Neo4j)
- **Schema**: `ag_catalog`

## Database Schema

### Tables Created

#### 1. `projects`
```sql
- id: UUID (Primary Key)
- created_at: TIMESTAMP
- updated_at: TIMESTAMP
- name: VARCHAR(255)
```

#### 2. `documents`
```sql
- id: UUID (Primary Key)
- created_at: TIMESTAMP
- updated_at: TIMESTAMP
- type: VARCHAR(255)
- status: VARCHAR(255) DEFAULT 'NOT_PROCESSED'
- file_name: VARCHAR(255)
- content: TEXT
- metadata: JSONB
- project_id: UUID (Foreign Key → projects.id)
```

#### 3. `embeddings`
```sql
- id: UUID (Primary Key)
- created_at: TIMESTAMP
- document_id: UUID (Foreign Key → documents.id)
- chunk_index: INTEGER
- chunk_text: TEXT
- vector: vector(1536) -- PGVector type
- model: VARCHAR(255)
```

### Indexes

**Documents Table:**
- `idx_documents_status` - B-tree on status
- `idx_documents_project_id` - B-tree on project_id
- `idx_documents_type` - B-tree on type

**Embeddings Table:**
- `idx_embeddings_document_id` - B-tree on document_id
- `idx_embeddings_model` - B-tree on model
- `idx_embeddings_vector` - HNSW on vector (cosine similarity)
- `uk_embeddings_document_chunk` - UNIQUE on (document_id, chunk_index)

## Initialization Process

The container runs three initialization scripts in order:

### 1. `01-init-pgvector.sql`
- Creates the `vector` extension
- Verifies installation
- Must run FIRST to avoid conflicts

### 2. `02-init-age.sql`
- Creates the `age` extension
- Loads AGE library
- Sets search path to include `ag_catalog`
- Creates helper function `ensure_graph_exists()`
- Grants necessary permissions

### 3. `03-init-schema.sql`
- Creates application schema
- Creates tables: projects, documents, embeddings
- Creates all indexes including vector index
- Grants permissions

## Building the Image

```bash
# Build the custom PostgreSQL image
docker-compose build postgres

# Or build with no cache
docker-compose build --no-cache postgres
```

## Starting the Container

```bash
# Start PostgreSQL container
docker-compose up -d postgres

# Check logs
docker-compose logs postgres

# Check status
docker-compose ps postgres
```

## Connecting to the Database

### Connection Details
- **Host**: localhost
- **Port**: 5432
- **Database**: ragsaas
- **User**: postgres
- **Password**: postgres

### Using Docker Exec
```bash
# Connect via psql
docker-compose exec postgres psql -U postgres -d ragsaas

# Run a query
docker-compose exec postgres psql -U postgres -d ragsaas -c "\dx"
```

### Using External Client
```bash
psql -h localhost -p 5432 -U postgres -d ragsaas
```

## Verifying Installation

### Check Extensions
```sql
-- List installed extensions
SELECT * FROM pg_extension WHERE extname IN ('vector', 'age');

-- Or use psql command
\dx
```

### Test PGVector
```sql
-- Create test vectors
CREATE TEMP TABLE test_vectors (id INT, vec vector(3));
INSERT INTO test_vectors VALUES 
  (1, '[1,2,3]'),
  (2, '[4,5,6]'),
  (3, '[1,2,4]');

-- Perform similarity search
SELECT id, vec, vec <=> '[1,2,3]' AS distance 
FROM test_vectors 
ORDER BY distance 
LIMIT 2;
```

### Test Apache AGE
```sql
-- Create a graph
SELECT create_graph('test_graph');

-- Create a node
SELECT * FROM cypher('test_graph', $$
  CREATE (p:Person {name: 'Alice', age: 30})
  RETURN p
$$) AS (person agtype);

-- Query the graph
SELECT * FROM cypher('test_graph', $$
  MATCH (p:Person)
  RETURN p.name, p.age
$$) AS (name agtype, age agtype);
```

## Vector Operations

PGVector supports three distance operators:
- `<->` - L2 distance (Euclidean)
- `<#>` - Inner product
- `<=>` - Cosine distance (used in this project)

### Example Query
```sql
-- Find similar embeddings
SELECT 
  id, 
  chunk_text,
  vector <=> '[0.1, 0.2, ...]'::vector AS similarity
FROM embeddings
ORDER BY vector <=> '[0.1, 0.2, ...]'::vector
LIMIT 10;
```

## Graph Operations (AGE)

### Creating a Graph
```sql
SELECT create_graph('knowledge_graph');
```

### Creating Nodes and Relationships
```sql
-- Create nodes with relationships
SELECT * FROM cypher('knowledge_graph', $$
  CREATE (d:Document {id: 'doc1', title: 'Introduction'})
  CREATE (c:Concept {name: 'Machine Learning'})
  CREATE (d)-[:MENTIONS]->(c)
  RETURN d, c
$$) AS (doc agtype, concept agtype);
```

### Querying Graphs
```sql
-- Find related concepts
SELECT * FROM cypher('knowledge_graph', $$
  MATCH (d:Document)-[:MENTIONS]->(c:Concept)
  WHERE d.id = 'doc1'
  RETURN c.name
$$) AS (concept_name agtype);
```

## Troubleshooting

### Issue: Segmentation Fault
**Cause**: AGE loaded before pgvector
**Solution**: Ensure pgvector is created first (01-init-pgvector.sql runs before 02-init-age.sql)

### Issue: Vector Dimension Limit
**Error**: "column cannot have more than 2000 dimensions"
**Solution**: Use 1536 dimensions (OpenAI standard) or upgrade to pgvector 0.7.0+ built from source

### Issue: AGE Extension Not Found
**Solution**: Verify AGE is installed and search path includes ag_catalog:
```sql
SHOW search_path;
-- Should include: ag_catalog, "$user", public
```

### Issue: Container Won't Start
```bash
# Check logs
docker-compose logs postgres

# Remove volumes and restart
docker-compose down -v
docker-compose up -d postgres
```

## Performance Considerations

### Vector Index
- HNSW index provides fast approximate nearest neighbor search
- Trade-off: `m` (accuracy) vs `ef_construction` (build time)
- Current settings: `m=16`, `ef_construction=64` (good balance)

### Query Performance
```sql
-- Set runtime parameter for search quality
SET hnsw.ef_search = 100;

-- Then run similarity search
SELECT * FROM embeddings
ORDER BY vector <=> '[...]'::vector
LIMIT 10;
```

## Maintenance

### Backup
```bash
# Backup database
docker-compose exec postgres pg_dump -U postgres ragsaas > backup.sql

# Restore
docker-compose exec -T postgres psql -U postgres ragsaas < backup.sql
```

### Update Statistics
```sql
-- After inserting many vectors
ANALYZE embeddings;
```

### Reindex Vectors
```sql
-- If index becomes fragmented
REINDEX INDEX idx_embeddings_vector;
```

## Development vs Production

### Development (Current Setup)
- Uses Docker volumes for persistence
- pgvector installed via APT (stable)
- Suitable for local development

### Production Recommendations
1. Use managed PostgreSQL with pgvector support (AWS RDS, Azure, etc.)
2. Or compile pgvector from source for latest features
3. Increase `shared_buffers` and `work_mem` for better performance
4. Set up regular backups
5. Monitor index performance
6. Use connection pooling (PgBouncer)

## Resources

- **PGVector**: https://github.com/pgvector/pgvector
- **Apache AGE**: https://age.apache.org/
- **PostgreSQL Documentation**: https://www.postgresql.org/docs/15/

## Version History

- **v1.0** (2025-11-08)
  - PostgreSQL 15.14
  - pgvector 0.8.1 (from APT repository)
  - Apache AGE 1.5.0 (PG15 branch)
  - Vector dimensions: 1536
  - Index: HNSW with cosine similarity
