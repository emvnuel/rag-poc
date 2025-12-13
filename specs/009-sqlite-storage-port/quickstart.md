# Quickstart: SQLite Storage Port

**Feature Branch**: `009-sqlite-storage-port`  
**Created**: 2024-12-13  
**Updated**: 2024-12-13

## Overview

This guide helps developers get started with the SQLite storage backend for the RAG-SaaS application. The implementation uses **pure Java/JDBC** without native extensions - no additional libraries required beyond sqlite-jdbc.

---

## Prerequisites

1. **Java 21+** installed
2. **Maven 3.8+** installed

**Note**: No native extensions needed! The implementation uses pure SQL cosine similarity.

---

## 1. Quick Setup

### Step 1: Configure Application Properties

Edit `src/main/resources/application.properties`:

```properties
# Enable SQLite storage backend
lightrag.storage.backend=sqlite

# SQLite database file location
lightrag.storage.sqlite.path=data/rag.db

# Vector configuration (must match your embedding model)
lightrag.storage.sqlite.vector.dimension=384
```

### Step 2: Run the Application

```bash
# Build and run with SQLite profile
./mvnw quarkus:dev -Dquarkus.profile=sqlite
```

Or build and run the JAR:

```bash
./mvnw clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

---

## 2. Configuration Reference

### Storage Backend Selection

```properties
# Options: postgresql (default), sqlite
lightrag.storage.backend=sqlite
```

### SQLite-Specific Settings

```properties
# Database file path (relative to working directory)
lightrag.storage.sqlite.path=data/rag.db

# Use :memory: for in-memory database (testing)
# lightrag.storage.sqlite.path=:memory:

# Enable WAL mode for concurrent reads (default: true)
lightrag.storage.sqlite.wal-mode=true

# Connection pool size for read operations (default: 4)
lightrag.storage.sqlite.read-pool-size=4

# Busy timeout in milliseconds (default: 30000)
lightrag.storage.sqlite.busy-timeout=30000

# Vector dimension (must match your embedding model: 384, 768, 1536, etc.)
lightrag.storage.sqlite.vector.dimension=384
```

---

## 3. Development Profiles

### SQLite Profile (application-sqlite.properties)

```properties
# SQLite development profile (already included in project)
lightrag.storage.backend=sqlite
lightrag.storage.sqlite.path=data/dev.db
```

Run with profile:

```bash
./mvnw quarkus:dev -Dquarkus.profile=sqlite
```

### Edge Profile for Resource-Constrained Devices (application-edge.properties)

```properties
# Edge deployment optimizations (already included in project)
lightrag.storage.backend=sqlite
lightrag.storage.sqlite.path=data/edge.db
lightrag.storage.sqlite.read-pool-size=2
lightrag.storage.sqlite.busy-timeout=60000
```

Run with edge profile:

```bash
./mvnw quarkus:dev -Dquarkus.profile=edge
```

---

## 4. Verify Installation

### Start Application and Check Logs

```bash
./mvnw quarkus:dev -Dquarkus.profile=sqlite

# Look for initialization messages:
# INFO: Initialized SQLiteVectorStorage with dimension 384
# INFO: SQLiteGraphStorage initialized successfully
```

### Test API Endpoints

```bash
# Create a project
curl -X POST http://localhost:8080/projects \
    -H "Content-Type: application/json" \
    -d '{"name": "Test Project"}'

# Get project ID from response, then upload a document:
curl -X POST "http://localhost:8080/projects/{projectId}/documents" \
    -F "file=@test.pdf"

# Query the knowledge base
curl "http://localhost:8080/projects/{projectId}/chat?q=What%20is%20this%20about"

# Export the knowledge graph
curl "http://localhost:8080/projects/{projectId}/export?format=text"
```

---

## 5. Testing

### Run SQLite-Specific Tests

```bash
# All SQLite unit tests (170 tests)
./mvnw test -Dtest="SQLite*"

# Project isolation tests
./mvnw test -Dtest="SQLiteProjectIsolation*"

# Performance tests
./mvnw test -Dtest="SQLitePerformance*,SQLiteMemoryBenchmark*"
```

### Run Contract Tests (Both Backends)

```bash
./mvnw test -Dtest="SQLiteStorageContractTest"
```

---

## 6. Database Inspection

### Using SQLite CLI

```bash
# Open database
sqlite3 data/rag.db

# Check tables
.tables

# Check schema
.schema vectors

# Query entities
SELECT name, entity_type, description 
FROM graph_entities 
LIMIT 10;

# Check vector count
SELECT COUNT(*) FROM vectors;

# Check project isolation
SELECT project_id, COUNT(*) as count 
FROM vectors 
GROUP BY project_id;
```

### Using Database Browser

SQLite databases can be opened with tools like:
- [DB Browser for SQLite](https://sqlitebrowser.org/)
- [DBeaver](https://dbeaver.io/)
- [DataGrip](https://www.jetbrains.com/datagrip/)

---

## 7. Export/Import Knowledge Bases

### Export a Project

```bash
# Download project as SQLite file
curl -o exported.db "http://localhost:8080/sqlite/export/{projectId}"
```

### Import a Project

```bash
# Upload SQLite file to create new project
curl -X POST "http://localhost:8080/sqlite/import" \
    -F "file=@exported.db"
```

---

## 8. Switching Between Backends

### Development: SQLite

```properties
# application.properties (or use -sqlite profile)
lightrag.storage.backend=sqlite
lightrag.storage.sqlite.path=data/dev.db
```

### Production: PostgreSQL

```properties
# application.properties (or use -prod profile)
lightrag.storage.backend=postgresql
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=user
quarkus.datasource.password=password
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/ragdb
```

---

## 9. Troubleshooting

### Database Locked

```
Error: database is locked
```

**Solution**: Ensure only one write operation at a time. Increase busy timeout:

```properties
lightrag.storage.sqlite.busy-timeout=60000
```

### Vector Dimension Mismatch

```
Error: vector dimension mismatch (expected 384, got 1536)
```

**Solution**: Ensure `lightrag.storage.sqlite.vector.dimension` matches your embedding model output.

### Slow Vector Queries

**Cause**: SQLite uses linear scan for vector similarity (no ANN index).

**Solutions**:
1. For <100K vectors: Performance is acceptable
2. For >100K vectors: Consider PostgreSQL with pgvector HNSW index
3. Use smaller batches: Reduce batch size in queries

### Application Fails to Start

**Check**:
1. Verify `lightrag.storage.backend=sqlite` is set correctly
2. Check database file path permissions
3. Ensure parent directory exists for the database file

---

## 10. Performance Characteristics

| Operation | SQLite (1K vectors) | SQLite (10K vectors) | Note |
|-----------|---------------------|----------------------|------|
| Vector Query (Top-10) | <50ms | <500ms | Linear scan |
| Entity Upsert | <5ms | <5ms | Indexed |
| Graph Traversal (depth=2) | <50ms | <100ms | BFS with limit |
| Batch Insert (100 vectors) | <100ms | <100ms | Chunked |

**Recommendations**:
- SQLite is ideal for local development, testing, and edge deployment with <100K vectors
- For production with >100K vectors, use PostgreSQL with pgvector HNSW indexing

---

## 11. Next Steps

1. **Read the Specification**: `specs/009-sqlite-storage-port/spec.md`
2. **Understand the Data Model**: `specs/009-sqlite-storage-port/data-model.md`
3. **Review API Contracts**: `specs/009-sqlite-storage-port/contracts/InternalContracts.md`
4. **Check Configuration in AGENTS.md**: See "SQLite Storage Backend" section

---

## 12. Key Implementation Details

### Storage Classes

| Class | Purpose |
|-------|---------|
| `SQLiteConnectionManager` | Connection pooling, WAL mode, pragmas |
| `SQLiteVectorStorage` | Vector BLOB storage with cosine similarity |
| `SQLiteGraphStorage` | Entity/relation storage with BFS traversal |
| `SQLiteExtractionCacheStorage` | LLM extraction caching |
| `SQLiteKVStorage` | Key-value storage |
| `SQLiteDocStatusStorage` | Document processing status |
| `SQLiteExportService` | Project export/import |
| `SQLiteSchemaMigrator` | Schema version management |

### Vector Similarity

The implementation uses pure SQL cosine similarity calculation:

```sql
-- Cosine similarity formula in SQL
SELECT id, content, 
       (dot_product / (norm_a * norm_b)) as similarity
FROM vectors
WHERE project_id = ?
ORDER BY similarity DESC
LIMIT ?
```

This works well for datasets up to ~100K vectors. For larger scale, PostgreSQL with pgvector provides ANN indexing.

### Project Isolation

All queries filter by `project_id` and foreign keys cascade on delete:
- Deleting a project automatically removes all associated vectors, entities, relations, and cache entries
