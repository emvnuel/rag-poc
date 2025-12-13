# Implementation Plan: SQLite Storage Port

**Branch**: `009-sqlite-storage-port` | **Date**: 2024-12-13 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/009-sqlite-storage-port/spec.md`

## Summary

Port the existing PostgreSQL-based storage layer to SQLite, enabling lightweight, embedded, single-file deployments without external database dependencies. Uses **sqlite-graph** for Cypher-based graph operations and **sqlite-vector** for vector similarity search, maintaining feature parity with the PostgreSQL implementation while enabling local development, edge deployments, and simplified testing.

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Quarkus 3.28.4, xerial sqlite-jdbc 3.45+, sqlite-graph 0.1.0-alpha.0, sqlite-vector 0.9.52, SmallRye Fault Tolerance  
**Storage**: SQLite 3.35+ with native extensions (sqlite-graph, sqlite-vector)  
**Testing**: JUnit 5 + REST Assured, `./mvnw test`, `./mvnw verify -DskipITs=false`  
**Target Platform**: Linux x86_64 (primary), macOS (secondary), Windows (limited)  
**Project Type**: Single project (Java backend with REST API)  
**Performance Goals**: 10,000 chunks vector search <500ms, 5,000 entity graph traversal <200ms  
**Constraints**: Single writer (WAL mode), <512MB memory during document processing  
**Scale/Scope**: Up to 100,000 chunks, 10,000 entities - larger deployments use PostgreSQL

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Rule | Status | Notes |
|------|--------|-------|
| Jakarta EE annotations | ✅ Pass | Using `@ApplicationScoped`, `@Produces`, `@IfBuildProperty` |
| TDD required | ✅ Pass | Test-first approach for all storage implementations |
| >80% branch coverage | ✅ Plan | Will ensure coverage with parametrized tests for both backends |
| No N+1 queries | ✅ Pass | Batch operations defined in contracts (upsertBatch, getBatch) |
| UUID v7 for IDs | ✅ Pass | Same UUID generation as PostgreSQL implementation |
| Avoid complexity | ✅ Pass | Reuses existing storage interfaces, no new abstractions |
| Interface-driven design | ✅ Pass | Implements existing GraphStorage, VectorStorage interfaces |
| No repository pattern | ✅ Pass | Direct storage implementation, no added abstraction layers |
| ≤3 dependencies per module | ✅ Pass | sqlite-jdbc + sqlite-graph + sqlite-vector |

## Project Structure

### Documentation (this feature)

```text
specs/009-sqlite-storage-port/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command) ✅
├── data-model.md        # Phase 1 output (/speckit.plan command) ✅
├── quickstart.md        # Phase 1 output (/speckit.plan command) ✅
├── contracts/           # Phase 1 output (/speckit.plan command) ✅
│   └── InternalContracts.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/main/java/br/edu/ifba/
├── chat/                           # Chat endpoints (unchanged)
├── document/                       # Document processing (unchanged)
├── exception/                      # Exception handling (unchanged)
├── lightrag/
│   ├── adapters/                   # LLM/Embedding adapters (unchanged)
│   ├── core/                       # Core entities, configs (unchanged)
│   ├── deletion/                   # Document deletion (unchanged)
│   ├── embedding/                  # Embedding function (unchanged)
│   ├── export/                     # KG export (unchanged)
│   ├── llm/                        # LLM functions (unchanged)
│   ├── merge/                      # Entity merge (unchanged)
│   ├── query/                      # Query executors (unchanged)
│   ├── rerank/                     # Reranking (unchanged)
│   ├── storage/
│   │   ├── impl/
│   │   │   ├── AgeGraphStorage.java           # PostgreSQL graph (existing)
│   │   │   ├── InMemoryDocStatusStorage.java  # In-memory status (existing)
│   │   │   ├── SQLiteConnectionManager.java   # NEW: Connection pooling
│   │   │   ├── SQLiteExtensionLoader.java     # NEW: Native lib loader
│   │   │   ├── SQLiteGraphStorage.java        # NEW: Graph storage
│   │   │   ├── SQLiteVectorStorage.java       # NEW: Vector storage
│   │   │   ├── SQLiteExtractionCacheStorage.java  # NEW: Cache storage
│   │   │   ├── SQLiteKVStorage.java           # NEW: Key-value storage
│   │   │   ├── SQLiteDocStatusStorage.java    # NEW: Doc status
│   │   │   └── SQLiteSchemaMigrator.java      # NEW: Schema migrations
│   │   ├── DocStatusStorage.java              # Interface (existing)
│   │   ├── ExtractionCacheStorage.java        # Interface (existing)
│   │   ├── GraphStorage.java                  # Interface (existing)
│   │   ├── KVStorage.java                     # Interface (existing)
│   │   └── VectorStorage.java                 # Interface (existing)
│   ├── utils/                      # Utilities (unchanged)
│   └── LightRAGService.java        # Main service (unchanged)
├── project/                        # Project management (unchanged)
└── shared/                         # Shared utilities (unchanged)

src/main/resources/
├── application.properties          # Add SQLite config options
├── native/                         # NEW: Bundled native libraries
│   ├── linux-x86_64/
│   │   ├── libgraph.so
│   │   └── vector.so
│   └── darwin-x86_64/
│       ├── libgraph.dylib
│       └── vector.dylib
└── db/migrations/                  # NEW: SQLite schema migrations
    └── V001__initial_schema.sql

src/test/java/br/edu/ifba/
├── lightrag/
│   ├── storage/
│   │   └── impl/
│   │       ├── AgeGraphStorageRetryIT.java    # Existing PostgreSQL tests
│   │       ├── AgeGraphStorageTest.java       # Existing PostgreSQL tests
│   │       ├── PgVectorStorageRetryIT.java    # Existing PostgreSQL tests
│   │       ├── SQLiteGraphStorageTest.java    # NEW: Graph unit tests
│   │       ├── SQLiteVectorStorageTest.java   # NEW: Vector unit tests
│   │       ├── SQLiteStorageIT.java           # NEW: Integration tests
│   │       └── StorageContractTest.java       # NEW: Contract tests (both backends)
│   └── ...
└── ...
```

**Structure Decision**: Single project structure maintained. New SQLite storage implementations added to `lightrag/storage/impl/` package alongside existing PostgreSQL implementations. Backend selection via Quarkus `@IfBuildProperty` or runtime configuration. Test classes verify contract compliance for both backends.

## Complexity Tracking

No constitution violations requiring justification.

## Implementation Phases

### Phase 1: Core Infrastructure (Week 1)
1. Add Maven dependencies (sqlite-jdbc, sqlite-vector)
2. Implement SQLiteConnectionManager with extension loading
3. Implement SQLiteExtensionLoader with platform detection
4. Implement SQLiteSchemaMigrator with initial schema
5. Add configuration properties for SQLite backend

### Phase 2: Storage Implementations (Week 2)
1. Implement SQLiteVectorStorage with sqlite-vector
2. Implement SQLiteGraphStorage with sqlite-graph
3. Implement SQLiteExtractionCacheStorage
4. Implement SQLiteKVStorage
5. Implement SQLiteDocStatusStorage

### Phase 3: Testing & Integration (Week 3)
1. Create StorageContractTest for both backends
2. Implement SQLiteStorageIT integration tests
3. Verify project isolation
4. Performance benchmarks
5. Update documentation

## Dependencies

### Maven (pom.xml additions)

```xml
<!-- SQLite JDBC Driver -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.45.0.0</version>
</dependency>

<!-- sqlite-vector Java bindings -->
<dependency>
    <groupId>ai.sqlite</groupId>
    <artifactId>vector</artifactId>
    <version>0.9.52</version>
</dependency>
```

### Native Libraries (bundled in JAR)

| Library | Platform | Version |
|---------|----------|---------|
| libgraph.so | Linux x86_64 | 0.1.0-alpha.0 |
| libgraph.dylib | macOS | 0.1.0-alpha.0 |
| vector.so | Linux x86_64 | 0.9.52 |
| vector.dylib | macOS | 0.9.52 |

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| sqlite-graph alpha status | Medium | API is stable for our use cases; fallback to recursive CTEs for missing features |
| Platform compatibility | Medium | Primary support for Linux x86_64, document limitations for other platforms |
| Elastic License for sqlite-vector | Low | Acceptable for embedded use; not a managed service |
| Write concurrency bottleneck | Medium | WAL mode + write serialization; document as expected behavior |

## References

- [sqlite-graph](https://github.com/agentflare-ai/sqlite-graph) - Graph database extension
- [sqlite-vector](https://github.com/sqliteai/sqlite-vector) - Vector search extension
- [xerial sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) - JDBC driver
- [research.md](research.md) - Detailed research findings
- [data-model.md](data-model.md) - Database schema
- [contracts/InternalContracts.md](contracts/InternalContracts.md) - Java interface contracts
