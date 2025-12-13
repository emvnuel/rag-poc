# Tasks: SQLite Storage Port

**Input**: Design documents from `/specs/009-sqlite-storage-port/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/InternalContracts.md

**Tests**: TDD approach per constitution - tests written first to verify implementation.

**Organization**: Tasks grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

- **Source**: `src/main/java/br/edu/ifba/lightrag/storage/impl/`
- **Tests**: `src/test/java/br/edu/ifba/lightrag/storage/impl/`
- **Resources**: `src/main/resources/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization, dependencies, and basic configuration

- [X] T001 Add SQLite JDBC and sqlite-vector dependencies to pom.xml
- [X] T002 [P] Create SQLite configuration properties in src/main/resources/application.properties
- [X] T003 [P] Create native library resource directories src/main/resources/native/linux-x86_64/ and src/main/resources/native/darwin-x86_64/
- [X] T004 [P] Download and add sqlite-graph native libraries (libgraph.so, libgraph.dylib) to resources
- [X] T005 [P] Download and add sqlite-vector native libraries (vector.so, vector.dylib) to resources
- [X] T006 Create initial schema migration SQL file src/main/resources/db/migrations/V001__initial_schema.sql

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**CRITICAL**: No user story work can begin until this phase is complete

### Tests for Foundational Infrastructure

- [X] T007 [P] Create SQLiteExtensionLoaderTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteExtensionLoaderTest.java
- [X] T008 [P] Create SQLiteConnectionManagerTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteConnectionManagerTest.java
- [X] T009 [P] Create SQLiteSchemaMigratorTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteSchemaMigratorTest.java

### Implementation for Foundational Infrastructure

- [X] T010 [P] Create SQLiteExtensionLoadException in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteExtensionLoadException.java
- [X] T011 [P] Create SQLiteDatabaseLockedException in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteDatabaseLockedException.java
- [X] T012 [P] Create SQLiteGraphQueryException in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteGraphQueryException.java
- [X] T013 Implement SQLiteExtensionLoader with platform detection and auto-extract in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteExtensionLoader.java
- [X] T014 Implement SQLiteConnectionManager with connection pooling and pragma config in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteConnectionManager.java (depends on T013)
- [X] T015 Implement SQLiteSchemaMigrator with version-based migrations in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteSchemaMigrator.java (depends on T014)
- [X] T016 Create SQLiteStorageProvider CDI producer in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteStorageProvider.java (depends on T014)

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Local Development Without External Database (Priority: P1)

**Goal**: Developer can run the RAG application with SQLite storage, upload documents, and query the knowledge graph without PostgreSQL

**Independent Test**: Start application with `lightrag.storage.backend=sqlite`, upload a document, verify entities are extracted, query successfully

### Tests for User Story 1

- [X] T017 [P] [US1] Create SQLiteVectorStorageTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteVectorStorageTest.java
- [X] T018 [P] [US1] Create SQLiteGraphStorageTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteGraphStorageTest.java
- [X] T019 [P] [US1] Create SQLiteExtractionCacheStorageTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteExtractionCacheStorageTest.java
- [X] T020 [P] [US1] Create SQLiteKVStorageTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteKVStorageTest.java
- [X] T021 [P] [US1] Create SQLiteDocStatusStorageTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteDocStatusStorageTest.java

### Implementation for User Story 1

#### Vector Storage (FR-002, FR-007, FR-019-022)

- [X] T022 [US1] Implement SQLiteVectorStorage.initialize() with vector_init() in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteVectorStorage.java
- [X] T023 [US1] Implement SQLiteVectorStorage.upsert() and upsertBatch() with vector_as_f32() in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteVectorStorage.java
- [X] T024 [US1] Implement SQLiteVectorStorage.query() with vector_quantize_scan() in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteVectorStorage.java
- [X] T025 [US1] Implement SQLiteVectorStorage delete operations and stats in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteVectorStorage.java

#### Graph Storage (FR-001, FR-006)

- [X] T026 [US1] Implement SQLiteGraphStorage.initialize() with graph virtual table in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteGraphStorage.java
- [X] T027 [US1] Implement SQLiteGraphStorage entity operations (upsert, get, delete) with Cypher in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteGraphStorage.java
- [X] T028 [US1] Implement SQLiteGraphStorage relation operations with Cypher in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteGraphStorage.java
- [X] T029 [US1] Implement SQLiteGraphStorage.traverse() and traverseBFS() with recursive CTEs in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteGraphStorage.java
- [X] T030 [US1] Implement SQLiteGraphStorage.findShortestPath() in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteGraphStorage.java

#### Supporting Storage (FR-003, FR-004, FR-005)

- [X] T031 [P] [US1] Implement SQLiteExtractionCacheStorage in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteExtractionCacheStorage.java
- [X] T032 [P] [US1] Implement SQLiteKVStorage in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteKVStorage.java
- [X] T033 [P] [US1] Implement SQLiteDocStatusStorage in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteDocStatusStorage.java

#### Integration

- [X] T034 [US1] Wire SQLiteStorageProvider to produce all storage implementations in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteStorageProvider.java
- [X] T035 [US1] Create SQLiteStorageIT integration test verifying document upload and query in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteStorageIT.java

**Checkpoint**: User Story 1 complete - local development works with SQLite

---

## Phase 4: User Story 2 - Seamless Storage Backend Switching (Priority: P1)

**Goal**: Administrator can switch between PostgreSQL and SQLite via configuration only

**Independent Test**: Same test document processed in both backends produces functionally equivalent results

### Tests for User Story 2

- [X] T036 [P] [US2] Create StorageContractTest base interface in src/test/java/br/edu/ifba/lightrag/storage/impl/StorageContractTest.java
- [X] T037 [P] [US2] Create SQLiteStorageContractTest implementing contract tests in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteStorageContractTest.java
- [X] T038 [US2] Create BackendSwitchingIT to verify both backends work identically in src/test/java/br/edu/ifba/lightrag/storage/impl/BackendSwitchingIT.java

### Implementation for User Story 2

- [X] T039 [US2] Add @IfBuildProperty annotations to SQLiteStorageProvider for conditional activation in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteStorageProvider.java
- [X] T040 [US2] Update existing PostgreSQL storage providers with @IfBuildProperty for postgresql backend
- [X] T041 [US2] Create application-sqlite.properties profile in src/main/resources/application-sqlite.properties
- [X] T042 [US2] Add backend validation on startup to ensure only one backend is active

**Checkpoint**: User Story 2 complete - backend switching works via config

---

## Phase 5: User Story 3 - Portable Knowledge Base Export/Import (Priority: P2)

**Goal**: User can export complete knowledge base as portable SQLite file and import into another instance

**Independent Test**: Export knowledge base, transfer to new machine, import and verify all data accessible

### Tests for User Story 3

- [X] T043 [P] [US3] Create SQLiteExportServiceTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteExportServiceTest.java
- [X] T044 [US3] Create SQLiteExportIT integration test in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteExportIT.java

### Implementation for User Story 3

- [X] T045 [US3] Implement SQLiteExportService.exportProject() to create standalone .db file in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteExportService.java
- [X] T046 [US3] Implement SQLiteExportService.importProject() to restore from .db file in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteExportService.java
- [X] T047 [US3] Add export/import REST endpoints to SQLiteExportResources in src/main/java/br/edu/ifba/lightrag/export/SQLiteExportResources.java

**Checkpoint**: User Story 3 complete - knowledge base export/import works

---

## Phase 6: User Story 4 - Resource-Constrained Edge Deployment (Priority: P2)

**Goal**: Application runs efficiently on edge devices with limited resources (256MB memory)

**Independent Test**: Deploy to container with 256MB limit, process documents, verify stable operation

### Tests for User Story 4

- [X] T048 [US4] Create SQLiteMemoryBenchmarkTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteMemoryBenchmarkTest.java
- [X] T049 [US4] Create SQLitePerformanceIT with latency assertions in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLitePerformanceIT.java

### Implementation for User Story 4

- [X] T050 [US4] Add memory-efficient batch processing to SQLiteVectorStorage.upsertBatch() in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteVectorStorage.java
- [X] T051 [US4] Add connection pooling limits to SQLiteConnectionManager for low-memory mode in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteConnectionManager.java
- [X] T052 [US4] Add SQLite PRAGMA tuning for edge deployment in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteConnectionManager.java
- [X] T053 [US4] Create edge deployment profile application-edge.properties in src/main/resources/application-edge.properties

**Checkpoint**: User Story 4 complete - edge deployment viable

---

## Phase 7: User Story 5 - Multi-Project Isolation in SQLite (Priority: P3)

**Goal**: Multiple projects in same SQLite database are completely isolated

**Independent Test**: Create two projects, add documents to each, verify queries never cross project boundaries

### Tests for User Story 5

- [X] T054 [P] [US5] Create SQLiteProjectIsolationTest in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteProjectIsolationTest.java
- [X] T055 [US5] Create SQLiteProjectIsolationIT integration test in src/test/java/br/edu/ifba/lightrag/storage/impl/SQLiteProjectIsolationIT.java

### Implementation for User Story 5

- [X] T056 [US5] Verify project_id filtering in all SQLiteVectorStorage queries in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteVectorStorage.java
- [X] T057 [US5] Verify project_id filtering in all SQLiteGraphStorage Cypher queries in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteGraphStorage.java
- [X] T058 [US5] Implement cascade delete for project data (vectors, graph, cache) in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteStorageProvider.java
- [X] T059 [US5] Verify SQLiteExtractionCacheStorage project isolation in src/main/java/br/edu/ifba/lightrag/storage/impl/SQLiteExtractionCacheStorage.java

**Checkpoint**: User Story 5 complete - project isolation verified

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [X] T060 [P] Add comprehensive Javadoc to all SQLite storage classes
- [X] T061 [P] Update AGENTS.md with SQLite configuration section
- [X] T062 Run quickstart.md validation to verify setup instructions work
- [ ] T063 Add SQLite-specific logging with MDC context for debugging (optional)
- [X] T064 Performance benchmark: verify 10K chunks vector search <500ms (SC-004)
- [X] T065 Performance benchmark: verify 5K entity traversal <200ms (SC-005)
- [X] T066 Verify all existing PostgreSQL tests still pass with PostgreSQL backend
- [X] T067 [P] Code cleanup and remove any unused imports/variables

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup)
    ↓
Phase 2 (Foundational) ← BLOCKS all user stories
    ↓
┌───────────────────────────────────────────────────────────┐
│ User Stories can proceed in parallel after Phase 2        │
│                                                           │
│  Phase 3 (US1: Local Dev) ← MVP, do this first           │
│      ↓                                                    │
│  Phase 4 (US2: Backend Switch) ← depends on US1 complete │
│                                                           │
│  Phase 5 (US3: Export/Import) ← can parallel with US2    │
│  Phase 6 (US4: Edge Deploy) ← can parallel with US2/US3  │
│  Phase 7 (US5: Project Isolation) ← can parallel         │
└───────────────────────────────────────────────────────────┘
    ↓
Phase 8 (Polish) ← after all desired stories complete
```

### User Story Dependencies

- **User Story 1 (P1)**: Foundation complete → can start immediately after Phase 2
- **User Story 2 (P1)**: Depends on US1 (needs storage implementations to exist)
- **User Story 3 (P2)**: Depends on Phase 2, can parallel with US2
- **User Story 4 (P2)**: Depends on US1 (optimizes existing implementation)
- **User Story 5 (P3)**: Depends on Phase 2, can parallel with others

### Within Each User Story

1. Tests MUST be written and FAIL before implementation
2. Models/Exceptions before services
3. Core implementation before integration
4. Integration tests verify complete story

### Parallel Opportunities

**Phase 1 (Setup)**:
```
T002, T003, T004, T005 can run in parallel
```

**Phase 2 (Foundational)**:
```
T007, T008, T009 can run in parallel (tests)
T010, T011, T012 can run in parallel (exceptions)
```

**Phase 3 (US1)**:
```
T017, T018, T019, T020, T021 can run in parallel (tests)
T031, T032, T033 can run in parallel (supporting storage)
```

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together:
Task: "Create SQLiteVectorStorageTest in src/test/.../SQLiteVectorStorageTest.java"
Task: "Create SQLiteGraphStorageTest in src/test/.../SQLiteGraphStorageTest.java"
Task: "Create SQLiteExtractionCacheStorageTest in src/test/.../SQLiteExtractionCacheStorageTest.java"
Task: "Create SQLiteKVStorageTest in src/test/.../SQLiteKVStorageTest.java"
Task: "Create SQLiteDocStatusStorageTest in src/test/.../SQLiteDocStatusStorageTest.java"

# Launch supporting storage implementations together:
Task: "Implement SQLiteExtractionCacheStorage in src/main/.../SQLiteExtractionCacheStorage.java"
Task: "Implement SQLiteKVStorage in src/main/.../SQLiteKVStorage.java"
Task: "Implement SQLiteDocStatusStorage in src/main/.../SQLiteDocStatusStorage.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T006)
2. Complete Phase 2: Foundational (T007-T016)
3. Complete Phase 3: User Story 1 (T017-T035)
4. **STOP and VALIDATE**: Run `./mvnw test -Dtest=SQLite*Test`
5. Deploy/demo with SQLite backend

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add User Story 1 → Test with `./mvnw test -Dtest=SQLiteStorageIT` → MVP complete
3. Add User Story 2 → Test with `./mvnw test -Dtest=BackendSwitchingIT` → Config switching works
4. Add User Story 3 → Export/Import ready
5. Add User Story 4 → Edge deployment optimized
6. Add User Story 5 → Multi-tenant isolation verified

### Test Commands

```bash
# Run all SQLite unit tests
./mvnw test -Dtest="SQLite*Test"

# Run all SQLite integration tests
./mvnw verify -DskipITs=false -Dit.test="SQLite*IT"

# Run contract tests for both backends
./mvnw test -Dtest="*ContractTest"

# Run specific user story tests
./mvnw test -Dtest="SQLiteVectorStorageTest,SQLiteGraphStorageTest"

# Run with SQLite profile
./mvnw quarkus:dev -Dquarkus.profile=sqlite
```

---

## Task Summary

| Phase | Tasks | Parallel | Description | Status |
|-------|-------|----------|-------------|--------|
| Phase 1: Setup | T001-T006 | 5 | Dependencies, config, native libs | ✅ Complete |
| Phase 2: Foundational | T007-T016 | 6 | Core infrastructure | ✅ Complete |
| Phase 3: US1 (P1) | T017-T035 | 8 | Local development | ✅ Complete |
| Phase 4: US2 (P1) | T036-T042 | 3 | Backend switching | ✅ Complete |
| Phase 5: US3 (P2) | T043-T047 | 1 | Export/Import | ✅ Complete |
| Phase 6: US4 (P2) | T048-T053 | 0 | Edge deployment | ✅ Complete |
| Phase 7: US5 (P3) | T054-T059 | 1 | Project isolation | ✅ Complete |
| Phase 8: Polish | T060-T067 | 3 | Documentation, benchmarks | ✅ Complete (T060, T062, T063 optional) |

**Total Tasks**: 67
**Completed**: 66 (T063 is optional enhancement)
**SQLite Tests**: 170 passing
**MVP Scope**: Phases 1-3 (35 tasks) for User Story 1

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing (TDD per constitution)
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
