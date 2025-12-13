# Feature Specification: SQLite Storage Port

**Feature Branch**: `009-sqlite-storage-port`  
**Created**: 2024-12-13  
**Status**: Draft  
**Input**: User description: "Create a sqlite port of the current implementation"

## Overview

Port the existing PostgreSQL-based storage layer (AGE graph storage, pgvector vector storage, extraction cache, and KV storage) to SQLite, enabling lightweight, embedded, single-file deployments without external database dependencies. This enables local development, edge deployments, desktop applications, and simplified testing scenarios.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Local Development Without External Database (Priority: P1)

A developer wants to run and test the RAG application on their local machine without installing or configuring PostgreSQL, Apache AGE, or pgvector extensions. They start the application with a SQLite configuration and immediately begin uploading documents and querying the knowledge graph.

**Why this priority**: This is the fundamental use case that enables all other scenarios. Without local development support, developers cannot easily contribute to or test the application.

**Independent Test**: Start application with SQLite storage, upload a document, verify entities and relationships are extracted, and query the knowledge graph successfully.

**Acceptance Scenarios**:

1. **Given** a fresh application installation with SQLite configuration, **When** a developer starts the application, **Then** the SQLite database file is created automatically with all required tables and indexes.

2. **Given** the application is running with SQLite storage, **When** a developer uploads a PDF document, **Then** the document is processed, chunks are stored with embeddings, and entities/relationships are extracted to the graph storage.

3. **Given** documents have been processed in SQLite storage, **When** a developer queries "What are the main topics?", **Then** the system retrieves relevant chunks and entities and returns a coherent response.

---

### User Story 2 - Seamless Storage Backend Switching (Priority: P1)

An administrator wants to switch between PostgreSQL and SQLite backends using only configuration changes, without modifying application code. They deploy the same application artifact to different environments with different storage requirements.

**Why this priority**: Configuration-based switching ensures the port integrates cleanly with the existing architecture and doesn't require separate application builds.

**Independent Test**: Deploy same application binary with PostgreSQL config in production and SQLite config in development, verify both work identically for basic operations.

**Acceptance Scenarios**:

1. **Given** an application configured for PostgreSQL, **When** the administrator changes configuration to use SQLite and restarts, **Then** the application starts successfully using SQLite storage.

2. **Given** the same set of test documents, **When** processed in both PostgreSQL and SQLite configurations, **Then** the extracted entities, relationships, and chunks are functionally equivalent.

3. **Given** a running SQLite configuration, **When** a query is executed, **Then** the response quality is comparable to PostgreSQL (allowing for vector search precision differences).

---

### User Story 3 - Portable Knowledge Base Export/Import (Priority: P2)

A user wants to export their complete knowledge base (documents, chunks, entities, relationships, and embeddings) as a single portable SQLite file that can be shared, backed up, or imported into another instance.

**Why this priority**: Data portability is a key differentiator for SQLite deployments, enabling offline sharing and simple backup strategies.

**Independent Test**: Export knowledge base to SQLite file, transfer to new machine, import and verify all data is accessible and queryable.

**Acceptance Scenarios**:

1. **Given** a populated knowledge base, **When** the user exports to SQLite format, **Then** a single `.db` file is created containing all project data.

2. **Given** an exported SQLite file, **When** imported into a fresh application instance, **Then** all documents, chunks, entities, relationships, and embeddings are restored.

3. **Given** an exported SQLite file, **When** opened with standard SQLite tools, **Then** the schema and data are readable and queryable using standard SQL.

---

### User Story 4 - Resource-Constrained Edge Deployment (Priority: P2)

An operator deploys the RAG application to an edge device or container with limited resources where running PostgreSQL is not feasible. The application runs efficiently with SQLite, consuming minimal memory and disk I/O.

**Why this priority**: Edge deployment expands the application's deployment options significantly, though it builds on the core SQLite implementation.

**Independent Test**: Deploy to container with 256MB memory limit, process documents, and verify stable operation under resource constraints.

**Acceptance Scenarios**:

1. **Given** an edge deployment with 256MB memory, **When** the application processes documents, **Then** memory usage remains stable without out-of-memory errors.

2. **Given** limited disk bandwidth, **When** performing vector similarity searches, **Then** query latency remains acceptable for interactive use.

3. **Given** a containerized deployment, **When** the container restarts, **Then** all data persists and the application resumes normal operation.

---

### User Story 5 - Multi-Project Isolation in SQLite (Priority: P3)

A user manages multiple independent projects within the same SQLite database. Each project's documents, entities, relationships, and embeddings are completely isolated from other projects.

**Why this priority**: Project isolation is required for feature parity with PostgreSQL but builds on the core implementation.

**Independent Test**: Create two projects, add documents to each, verify queries on one project never return data from the other.

**Acceptance Scenarios**:

1. **Given** two projects in the same SQLite database, **When** documents are uploaded to Project A, **Then** Project B's queries return no results from Project A's data.

2. **Given** a project with entities, **When** the project is deleted, **Then** all associated entities, relationships, chunks, and embeddings are removed.

3. **Given** projects A and B, **When** both have an entity named "Apple Inc.", **Then** these are stored and queried as separate entities.

---

### Edge Cases

- What happens when the SQLite database file is locked by another process?
- How does the system handle concurrent write operations given SQLite's write locking?
- What happens when the SQLite file becomes corrupted or is deleted during operation?
- How does the system behave when disk space is exhausted during document processing?
- What happens when the vector dimension configuration mismatches existing data?

## Requirements *(mandatory)*

### Functional Requirements

#### Core Storage Operations

- **FR-001**: System MUST implement all GraphStorage interface methods using SQLite tables for entity and relationship storage.
- **FR-002**: System MUST implement all VectorStorage interface methods using SQLite with blob storage for embeddings.
- **FR-003**: System MUST implement all ExtractionCacheStorage interface methods using SQLite tables.
- **FR-004**: System MUST implement all KVStorage interface methods using SQLite key-value tables.
- **FR-005**: System MUST implement all DocStatusStorage interface methods using SQLite tables.

#### Feature Parity

- **FR-006**: System MUST support all graph traversal operations (BFS, shortest path) using recursive SQL queries.
- **FR-007**: System MUST support vector similarity search using cosine distance calculation.
- **FR-008**: System MUST support batch operations (upsertBatch, deleteBatch) for all storage types.
- **FR-009**: System MUST maintain per-project data isolation identical to PostgreSQL implementation.
- **FR-010**: System MUST support cascade deletion of project data (documents, chunks, entities, relationships).

#### Configuration and Initialization

- **FR-011**: System MUST allow selection of storage backend (PostgreSQL or SQLite) via configuration property.
- **FR-012**: System MUST automatically create SQLite database file and schema on first startup.
- **FR-013**: System MUST support configurable SQLite database file location.
- **FR-014**: System MUST apply appropriate SQLite pragmas for performance (journal mode, synchronous, cache size).

#### Data Integrity

- **FR-015**: System MUST support atomic transactions for multi-step operations.
- **FR-016**: System MUST handle concurrent read operations safely.
- **FR-017**: System MUST serialize write operations to prevent database corruption.
- **FR-018**: System MUST validate schema version and apply migrations on startup.

#### Vector Search

- **FR-019**: System MUST store embedding vectors as binary blobs in SQLite.
- **FR-020**: System MUST compute cosine similarity in application code or using SQLite extensions.
- **FR-021**: System MUST support configurable vector dimensions matching embedding model output.
- **FR-022**: System MUST return top-K results sorted by similarity score.

### Key Entities

- **SQLiteGraphStorage**: Implementation of GraphStorage using SQLite tables for nodes and edges, with recursive CTEs for graph traversal.
- **SQLiteVectorStorage**: Implementation of VectorStorage using SQLite blobs for vectors, with application-side similarity computation.
- **SQLiteExtractionCacheStorage**: Implementation of ExtractionCacheStorage using standard SQLite tables.
- **SQLiteKVStorage**: Implementation of KVStorage using key-value table pattern.
- **SQLiteDocStatusStorage**: Implementation of DocStatusStorage using SQLite tables.
- **SQLiteStorageProvider**: CDI producer/factory that creates appropriate storage implementations based on configuration.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All existing storage interface unit tests pass with SQLite implementations.
- **SC-002**: All existing integration tests pass when configured to use SQLite storage.
- **SC-003**: Application starts and processes a 10-page document within 60 seconds using SQLite storage.
- **SC-004**: Vector similarity search returns top-10 results within 500ms for databases with 10,000 chunks.
- **SC-005**: Knowledge graph queries traverse 3 hops in under 200ms for graphs with 5,000 entities.
- **SC-006**: SQLite database file size is within 20% of equivalent PostgreSQL data size.
- **SC-007**: Memory usage during document processing stays under 512MB for typical documents.
- **SC-008**: Concurrent read operations (10 simultaneous queries) complete without errors.
- **SC-009**: Data persists correctly across application restarts with no data loss.
- **SC-010**: Query results from SQLite and PostgreSQL are semantically equivalent for the same input data.

## Assumptions

1. **Vector Search Precision**: SQLite vector search using brute-force cosine similarity is acceptable for the expected data volumes (up to 100,000 chunks). For larger deployments, PostgreSQL with pgvector remains the recommended option.

2. **Write Concurrency**: SQLite's single-writer model is acceptable for the expected workload patterns (primarily batch writes during document ingestion, concurrent reads during queries).

3. **No Graph Extensions**: Unlike Apache AGE, SQLite has no native graph extensions. Graph operations will be implemented using relational tables with recursive CTEs, which is sufficient for the query patterns used.

4. **Existing In-Memory Implementations**: The existing InMemoryGraphStorage and InMemoryVectorStorage provide reference implementations and test coverage that can guide the SQLite implementation.

5. **CDI/Quarkus Compatibility**: SQLite storage implementations will use the same CDI injection patterns as PostgreSQL implementations, selected via `@IfBuildProperty` or similar mechanisms.

6. **JDBC Driver**: The SQLite JDBC driver (org.xerial:sqlite-jdbc) is the standard driver used, compatible with Quarkus and virtual threads.

## Out of Scope

- Full-text search using SQLite FTS5 (existing implementation uses vector search)
- SQLite clustering or replication
- Real-time synchronization between SQLite and PostgreSQL
- Custom SQLite extensions for vector operations (using application-side computation)
- Support for SQLite versions older than 3.35 (required for RETURNING clause and JSON functions)
