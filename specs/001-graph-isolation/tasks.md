# Tasks: Project-Level Graph Isolation

**Input**: Design documents from `/specs/001-graph-isolation/`  
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅  
**Architecture**: Per-project graph partitioning (Option B)

**Tests**: Constitution §II requires TDD - tests MUST be written before implementation

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Project type**: Single backend project (Quarkus Java)
- **Source**: `src/main/java/br/edu/ifba/`
- **Tests**: `src/test/java/br/edu/ifba/`
- **Docker**: `docker-init/`
- **Scripts**: `scripts/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and documentation structure

- [ ] T001 Verify PostgreSQL 14+ with Apache AGE 1.5.0 extension in docker-compose.yaml
- [ ] T002 [P] Create data-model.md in specs/001-graph-isolation/ documenting per-project graph architecture
- [ ] T003 [P] Create contracts/ directory and GraphStorage.interface.md in specs/001-graph-isolation/
- [ ] T004 [P] Create quickstart.md in specs/001-graph-isolation/ with developer testing guide
- [ ] T005 Verify existing project management in src/main/java/br/edu/ifba/project/Project.java has UUID v7 IDs

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core graph lifecycle infrastructure that MUST be complete before ANY user story

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Graph Storage Interface Updates

- [ ] T006 Add graph lifecycle methods to src/main/java/br/edu/ifba/lightrag/storage/GraphStorage.java interface: createProjectGraph(@NotNull String projectId), deleteProjectGraph(@NotNull String projectId), graphExists(@NotNull String projectId)
- [ ] T007 Update GraphStorage.java entity methods to require @NotNull String projectId as first parameter: upsertEntity, getEntity, getEntities, getAllEntities
- [ ] T008 Update GraphStorage.java relation methods to require @NotNull String projectId as first parameter: upsertRelation, getRelationsForEntity, getAllRelations
- [ ] T009 Update GraphStorage.java batch methods to require @NotNull String projectId: upsertEntities, upsertRelations
- [ ] T010 Update GraphStorage.java getStats method signature to require @NotNull String projectId
- [ ] T011 Add Javadoc comments to all new/updated GraphStorage.java methods documenting projectId parameter behavior

### AgeGraphStorage Implementation - Graph Lifecycle

- [ ] T012 Implement getGraphName(String projectId) helper method in src/main/java/br/edu/ifba/lightrag/storage/impl/AgeGraphStorage.java: sanitize UUID (replace hyphens with underscores), truncate to 32 chars, add "graph_" prefix
- [ ] T013 Implement createProjectGraph(String projectId) in AgeGraphStorage.java: validate projectId not null, generate graph name, execute ag_catalog.create_graph(), handle idempotency
- [ ] T014 Implement deleteProjectGraph(String projectId) in AgeGraphStorage.java: validate projectId not null, generate graph name, execute ag_catalog.drop_graph() with cascade=true
- [ ] T015 Implement graphExists(String projectId) in AgeGraphStorage.java: query ag_catalog.ag_graph to check if graph exists
- [ ] T016 Add validation helper validateProjectId(String projectId) in AgeGraphStorage.java: throw IllegalArgumentException if null or invalid UUID v7 format

### AgeGraphStorage Implementation - Query Routing

- [ ] T017 Update executeCypherQuery() helper method in AgeGraphStorage.java to accept projectId parameter and route to correct graph via ag_catalog.cypher('graph_name', $$cypher$$)
- [ ] T018 Update upsertEntity(String projectId, Entity entity) in AgeGraphStorage.java to use project-specific graph via getGraphName()
- [ ] T019 Update getEntity(String projectId, String entityName) in AgeGraphStorage.java to query project-specific graph
- [ ] T020 Update getEntities(String projectId, List<String> entityNames) in AgeGraphStorage.java to query project-specific graph
- [ ] T021 Update getAllEntities(String projectId) in AgeGraphStorage.java to query project-specific graph
- [ ] T022 Update upsertRelation(String projectId, Relation relation) in AgeGraphStorage.java to use project-specific graph
- [ ] T023 Update getRelationsForEntity(String projectId, String entityName) in AgeGraphStorage.java to query project-specific graph
- [ ] T024 Update getAllRelations(String projectId) in AgeGraphStorage.java to query project-specific graph
- [ ] T025 Update upsertEntities(String projectId, List<Entity> entities) in AgeGraphStorage.java to use project-specific graph with batching
- [ ] T026 Update upsertRelations(String projectId, List<Relation> relations) in AgeGraphStorage.java to use project-specific graph with batching
- [ ] T027 Update getStats(String projectId) in AgeGraphStorage.java to query project-specific graph statistics

### Logging & Observability

- [ ] T028 Add INFO log in createProjectGraph() with message "Creating graph for project: {projectId}, graph name: {graphName}"
- [ ] T029 Add INFO log in deleteProjectGraph() with message "Deleting graph for project: {projectId}, graph name: {graphName}"
- [ ] T030 Add DEBUG logs in all AgeGraphStorage query methods showing projectId and graph name used
- [ ] T031 Update existing graph operation logs to include projectId field for structured logging

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Data Isolation Guarantee (Priority: P1) 🎯 MVP

**Goal**: Complete assurance that knowledge graph data (entities and relationships) is isolated from other projects with zero cross-contamination

**Independent Test**: Create two projects with overlapping entity names (e.g., both mention "Apple Inc."), upload documents to each, query both projects to verify results contain only entities/relationships from respective project

### Tests for User Story 1 (TDD Required per Constitution §II)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T032 [P] [US1] Create AgeGraphStorageTest.java in src/test/java/br/edu/ifba/lightrag/storage/ with @QuarkusTest annotation and Testcontainers PostgreSQL setup
- [ ] T033 [P] [US1] Write test testCreateProjectGraphCreatesIsolatedGraph() in AgeGraphStorageTest.java: create two project graphs, verify both exist independently
- [ ] T034 [P] [US1] Write test testEntitiesIsolatedBetweenProjects() in AgeGraphStorageTest.java: insert entity "Apple Inc." in Project A and Project B, verify queries return only project-specific entities
- [ ] T035 [P] [US1] Write test testRelationsIsolatedBetweenProjects() in AgeGraphStorageTest.java: create relations in two projects with same entity names, verify no cross-project contamination
- [ ] T036 [P] [US1] Write test testEntityDeduplicationWithinProjectOnly() in AgeGraphStorageTest.java: insert duplicate entities in same project, verify merged; insert same entity name in different project, verify separate nodes
- [ ] T037 [P] [US1] Write test testCrossProjectQueryReturnsEmpty() in AgeGraphStorageTest.java: create entities in Project A, query Project B, verify zero results

### Implementation for User Story 1

- [ ] T038 [US1] Update LightRAG.java in src/main/java/br/edu/ifba/lightrag/core/ to extract projectId from document context and pass to storage layer operations
- [ ] T039 [US1] Update entity extraction logic in LightRAG.java to pass projectId when calling graphStorage.upsertEntities()
- [ ] T040 [US1] Update relation extraction logic in LightRAG.java to pass projectId when calling graphStorage.upsertRelations()
- [ ] T041 [US1] Update ProjectService.java in src/main/java/br/edu/ifba/project/ to call graphStorage.createProjectGraph(projectId) after project creation
- [ ] T042 [US1] Add error handling in ProjectService.java for graph creation failures: log error, rollback project creation if graph fails
- [ ] T043 [US1] Add validation in AgeGraphStorage entity/relation methods to verify graph exists before operations: throw IllegalStateException if missing with message "Graph not found for project: {projectId}"
- [ ] T044 [US1] Run AgeGraphStorageTest.java unit tests and verify all isolation tests pass with zero cross-project leakage

**Checkpoint**: At this point, User Story 1 should be fully functional - entities and relations physically isolated per project

---

## Phase 4: User Story 2 - Graph Query Scoping (Priority: P2)

**Goal**: All graph queries (local, global, hybrid modes) automatically scope to active project with no manual filtering needed

**Independent Test**: Execute all five query modes (LOCAL, GLOBAL, HYBRID, NAIVE, MIX) against multiple projects with similar content, verify each mode's results contain only active project's data

### Tests for User Story 2 (TDD Required per Constitution §II)

- [ ] T045 [P] [US2] Create ProjectIsolationIT.java in src/test/java/br/edu/ifba/ for end-to-end integration tests with @QuarkusIntegrationTest
- [ ] T046 [P] [US2] Write test testGlobalQueryModeScopedToProject() in ProjectIsolationIT.java: upload "AI documents" to two projects, run global query on each, verify results isolated
- [ ] T047 [P] [US2] Write test testHybridQueryModeScopedToProject() in ProjectIsolationIT.java: hybrid query combines vector + graph, verify both layers filtered to project
- [ ] T048 [P] [US2] Write test testLocalQueryModeScopedToProject() in ProjectIsolationIT.java: local query with entity extraction, verify only project entities returned
- [ ] T049 [P] [US2] Write test testMixQueryModeScopedToProject() in ProjectIsolationIT.java: mix query execution, verify project scoping works
- [ ] T050 [P] [US2] Write test testNaiveQueryModeScopedToProject() in ProjectIsolationIT.java: naive query without graph traversal still respects project scope

### Implementation for User Story 2

- [ ] T051 [P] [US2] Update GlobalQueryExecutor.java in src/main/java/br/edu/ifba/lightrag/query/ to pass projectId to all graphStorage method calls
- [ ] T052 [P] [US2] Update HybridQueryExecutor.java in src/main/java/br/edu/ifba/lightrag/query/ to pass projectId to graph operations
- [ ] T053 [P] [US2] Update LocalQueryExecutor.java in src/main/java/br/edu/ifba/lightrag/query/ to pass projectId to graph operations
- [ ] T054 [P] [US2] Update MixQueryExecutor.java in src/main/java/br/edu/ifba/lightrag/query/ to pass projectId to graph operations
- [ ] T055 [P] [US2] Update NaiveQueryExecutor.java in src/main/java/br/edu/ifba/lightrag/query/ to pass projectId if graph operations used
- [ ] T056 [US2] Update LightRAGService.java in src/main/java/br/edu/ifba/lightrag/ to extract projectId from query context and propagate to query executors
- [ ] T057 [US2] Add validation in each query executor: throw IllegalArgumentException if projectId missing with message "projectId required for graph queries"
- [ ] T058 [US2] Run ProjectIsolationIT.java integration tests and verify all five query modes respect project isolation

**Checkpoint**: At this point, User Stories 1 AND 2 should both work - isolation + automatic query scoping across all modes

---

## Phase 5: User Story 3 - Project Deletion Cleanup (Priority: P3)

**Goal**: Ability to delete project and have all associated graph data permanently removed within 60 seconds

**Independent Test**: Create project, build knowledge graph with 50+ entities, delete project, verify no entities/relationships remain in storage

### Tests for User Story 3 (TDD Required per Constitution §II)

- [ ] T059 [P] [US3] Write test testDeleteProjectRemovesAllGraphData() in AgeGraphStorageTest.java: create project graph with entities/relations, call deleteProjectGraph(), verify graph no longer exists
- [ ] T060 [P] [US3] Write test testDeleteProjectCompletesWithin60Seconds() in AgeGraphStorageTest.java: create graph with 10K entities, time deletion, assert <60s per Success Criteria SC-003
- [ ] T061 [P] [US3] Write test testDeleteOneProjectLeavesOthersIntact() in AgeGraphStorageTest.java: create two projects with entity "Microsoft", delete Project A, verify Project B's "Microsoft" still exists
- [ ] T062 [P] [US3] Write test testDeleteNonExistentProjectDoesNotFail() in AgeGraphStorageTest.java: call deleteProjectGraph() for non-existent project, verify graceful handling

### Implementation for User Story 3

- [ ] T063 [US3] Update ProjectService.deleteProject() in src/main/java/br/edu/ifba/project/ProjectService.java to call graphStorage.deleteProjectGraph(projectId) before deleting project record
- [ ] T064 [US3] Add error handling in ProjectService.deleteProject(): if graph deletion fails, log error but continue with project deletion (graph cleanup can be retried separately)
- [ ] T065 [US3] Add verification in ProjectService.deleteProject(): after graph deletion, call graphExists() to confirm removal, log WARNING if still exists
- [ ] T066 [US3] Update deleteProjectGraph() in AgeGraphStorage.java to handle case where graph doesn't exist: check graphExists() first, skip drop if missing
- [ ] T067 [US3] Add INFO log in ProjectService.deleteProject() showing deletion completion time and entity count removed
- [ ] T068 [US3] Run AgeGraphStorageTest.java deletion tests and verify all pass with <60s completion time

**Checkpoint**: All three user stories should now be independently functional - isolation + scoping + cleanup

---

## Phase 6: Migration & Deployment

**Purpose**: Migrate existing shared graph data to per-project graphs and deploy changes

### Migration Script Creation

- [ ] T069 Create 07-migrate-to-per-project-graphs.sql in docker-init/ with SQL to identify unique projects via document ownership
- [ ] T070 Add step 1 to migration script: query documents table for distinct project_ids linked to graph entities via sourceId
- [ ] T071 Add step 2 to migration script: for each project_id, generate graph name and execute ag_catalog.create_graph()
- [ ] T072 Add step 3 to migration script: create temporary mapping table project_migration_stats with columns (project_id, entity_count, relation_count)
- [ ] T073 Create migrate-graph-isolation.sh in scripts/ to execute migration in batches: loop through projects, copy entities/relations from shared graph to project-specific graphs
- [ ] T074 Add verification queries to migrate-graph-isolation.sh: compare entity/relation counts between shared graph and sum of project graphs
- [ ] T075 Add rollback-graph-isolation.sh in scripts/ to drop all project-specific graphs and restore shared graph from backup (emergency only)

### Migration Execution Strategy

- [ ] T076 Update migrate-graph-isolation.sh to batch entity copying in 1000-node chunks to avoid long transactions per research findings
- [ ] T077 Add progress logging to migrate-graph-isolation.sh: log "Migrated project {N}/{total}: {entity_count} entities, {relation_count} relations"
- [ ] T078 Add validation step in migrate-graph-isolation.sh: after all projects migrated, verify 100% entity count match before dropping shared graph
- [ ] T079 Add final step to migrate-graph-isolation.sh: execute ag_catalog.drop_graph() for shared graph only after 100% verification
- [ ] T080 Test migration script on staging environment with sample data: create 10 projects with overlapping entities, run migration, verify isolation

### Database Schema Updates

- [ ] T081 Update 02-init-age.sql in docker-init/ with comments documenting per-project graph architecture approach
- [ ] T082 Add SQL comment to 02-init-age.sql explaining graph naming convention: graph_{uuid_prefix} with 32-char truncation
- [ ] T083 Update docker-compose.yaml to ensure PostgreSQL 14+ with Apache AGE 1.5.0 extension mounted with migration scripts

---

## Phase 7: Performance Validation & Polish

**Purpose**: Verify performance requirements and add cross-cutting improvements

### Performance Testing

- [ ] T084 [P] Write performance test testGraphCreationUnder100ms() in AgeGraphStorageTest.java: measure createProjectGraph() latency, assert P95 <100ms
- [ ] T085 [P] Write performance test test2HopTraversalUnder300ms() in ProjectIsolationIT.java: execute 2-hop graph query, measure latency, assert P95 <300ms per research findings
- [ ] T086 [P] Write performance test testConcurrent50ProjectUploads() in ProjectIsolationIT.java: simulate 50 concurrent document uploads across different projects, verify no lock contention
- [ ] T087 Run performance test suite and collect metrics: graph creation time, query latency P95/P99, concurrent upload throughput
- [ ] T088 Validate performance against Constitution §IV requirements: P95 <550ms for 2-hop traversals (target: <300ms achieved per research)

### Documentation & Polish

- [ ] T089 [P] Update quickstart.md with complete example: create two projects, upload documents with overlapping entities, query both, show isolation
- [ ] T090 [P] Add troubleshooting section to quickstart.md: common issues like "graph not found" errors, cross-project leakage debugging, performance degradation
- [ ] T091 [P] Document migration procedure in quickstart.md: when to run migration, how to verify success, rollback steps if needed
- [ ] T092 [P] Update README.md in repository root with graph isolation feature: explain per-project graphs, performance characteristics, migration notes
- [ ] T093 Code cleanup in AgeGraphStorage.java: extract repeated graph name logic into helper methods, add final to immutable variables per Constitution §I
- [ ] T094 Add Javadoc comments to all public methods in AgeGraphStorage.java with @param and @return tags per Constitution §I requirements
- [ ] T095 Run ./mvnw clean package to verify build passes with all changes
- [ ] T096 Run ./mvnw test to verify all unit tests pass (target: >80% branch coverage per Constitution §II)
- [ ] T097 Run ./mvnw verify -DskipITs=false to verify integration tests pass with Testcontainers
- [ ] T098 Run quickstart.md validation: execute all commands in quickstart guide, verify outputs match expectations

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - User Story 1 (P1): Can start after Foundational - No dependencies on other stories
  - User Story 2 (P2): Can start after Foundational - No dependencies on US1 (query executors independent)
  - User Story 3 (P3): Can start after Foundational - No dependencies on US1/US2 (deletion is independent)
- **Migration (Phase 6)**: Depends on US1 complete (graph lifecycle must work)
- **Performance & Polish (Phase 7)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Foundation → US1 (data isolation via per-project graphs)
- **User Story 2 (P2)**: Foundation → US2 (query scoping - independent of US1 implementation)
- **User Story 3 (P3)**: Foundation → US3 (deletion - independent of US1/US2 implementation)

**Note**: All three user stories can be implemented in parallel by different developers after Foundational phase completes

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD per Constitution §II)
- GraphStorage interface changes before AgeGraphStorage implementation
- Storage layer changes before query executor updates
- Unit tests before integration tests
- Core implementation before integration with other components

### Parallel Opportunities

- **Setup (Phase 1)**: T002, T003, T004 can run in parallel (different documentation files)
- **Foundational (Phase 2)**: 
  - T006-T011 (interface updates) can run in parallel with T028-T031 (logging)
  - T012-T016 (graph lifecycle) before T017-T027 (query routing)
- **User Story 1 Tests**: T032-T037 can all run in parallel (different test methods)
- **User Story 1 Implementation**: T038-T040 (LightRAG updates) in parallel after storage layer complete
- **User Story 2 Tests**: T045-T050 can all run in parallel (different integration tests)
- **User Story 2 Implementation**: T051-T055 (query executors) can all run in parallel
- **User Story 3 Tests**: T059-T062 can all run in parallel (different deletion test scenarios)
- **Performance Tests**: T084-T086 can run in parallel
- **Documentation**: T089-T092 can all run in parallel (different documentation files)

---

## Parallel Example: User Story 1 Implementation

```bash
# After tests T032-T037 are written and failing:

# Launch storage layer updates in parallel:
Task T038: "Update LightRAG.java entity extraction to pass projectId"
Task T039: "Update LightRAG.java entity extraction logic"
Task T040: "Update LightRAG.java relation extraction logic"

# Then sequentially:
Task T041: "Update ProjectService.java to create graph on project creation"
Task T042: "Add error handling in ProjectService.java"
Task T043: "Add validation in AgeGraphStorage"
Task T044: "Run AgeGraphStorageTest.java and verify tests pass"
```

---

## Parallel Example: User Story 2 Query Executors

```bash
# After integration tests T045-T050 are written and failing:

# Launch all query executor updates in parallel (different files):
Task T051: "Update GlobalQueryExecutor.java"
Task T052: "Update HybridQueryExecutor.java"
Task T053: "Update LocalQueryExecutor.java"
Task T054: "Update MixQueryExecutor.java"
Task T055: "Update NaiveQueryExecutor.java"

# Then sequentially:
Task T056: "Update LightRAGService.java to propagate projectId"
Task T057: "Add validation in query executors"
Task T058: "Run ProjectIsolationIT.java integration tests"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T005)
2. Complete Phase 2: Foundational (T006-T031) - CRITICAL, blocks all stories
3. Complete Phase 3: User Story 1 (T032-T044) - Data isolation guarantee
4. **STOP and VALIDATE**: Run AgeGraphStorageTest.java, verify zero cross-project leakage
5. Deploy/demo MVP: Create two projects, upload documents, prove isolation works

**MVP Deliverable**: Physical graph isolation preventing data leakage between projects

### Incremental Delivery

1. **Foundation** (Phases 1-2) → Core graph lifecycle working
2. **MVP** (Phase 3) → User Story 1 → Test independently → Deploy/Demo
3. **Enhancement** (Phase 4) → User Story 2 → Test independently → Deploy/Demo (automatic query scoping)
4. **Compliance** (Phase 5) → User Story 3 → Test independently → Deploy/Demo (GDPR deletion)
5. **Production Ready** (Phases 6-7) → Migration + performance validation → Deploy to production

Each story adds value without breaking previous stories.

### Parallel Team Strategy

With multiple developers after Foundational phase (T031) completes:

1. **Team completes Setup + Foundational together** (T001-T031)
2. **Once Foundational is done**:
   - Developer A: User Story 1 (T032-T044) - Data isolation
   - Developer B: User Story 2 (T045-T058) - Query scoping
   - Developer C: User Story 3 (T059-T068) - Deletion cleanup
3. Stories complete and integrate independently
4. **Team reunites** for Migration (T069-T083) and Polish (T084-T098)

---

## Success Validation Checklist

After all tasks complete, verify against spec.md Success Criteria:

- [ ] **SC-001**: Zero cross-project data leakage - Run 1000+ queries across 10 test projects with overlapping content, verify 100% project-scoped results
- [ ] **SC-002**: Graph query performance within 10% - P95 latency for 2-hop traversals <550ms (target: <300ms achieved per research)
- [ ] **SC-003**: Project deletion completes within 60 seconds for 10,000 entities - Run testDeleteProjectCompletesWithin60Seconds()
- [ ] **SC-004**: Migration completes without data loss - Verify 100% entity/relation count match between shared and project graphs
- [ ] **SC-005**: System handles 100 concurrent uploads across 50 projects - Run testConcurrent50ProjectUploads() without isolation failures
- [ ] **SC-006**: All five query modes work with project isolation - Run ProjectIsolationIT.java covering LOCAL, GLOBAL, HYBRID, NAIVE, MIX modes

---

## Notes

- **[P]** tasks = different files, no dependencies, can run in parallel
- **[Story]** label (US1/US2/US3) maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Constitution §II requires TDD: Verify tests fail before implementing
- Constitution §I: Add Javadoc to all public methods, max complexity 15
- Constitution §IV: Performance target P95 <550ms (achieved <300ms per research)
- Research finding: Per-project graphs provide O(1) performance regardless of total system size
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence

---

**Total Tasks**: 98  
**MVP Scope**: Phases 1-3 (Tasks T001-T044) = 44 tasks  
**Task Count by User Story**:
- User Story 1 (P1): 13 tasks (T032-T044) - Data Isolation Guarantee
- User Story 2 (P2): 14 tasks (T045-T058) - Graph Query Scoping
- User Story 3 (P3): 10 tasks (T059-T068) - Project Deletion Cleanup
- Foundational: 26 tasks (T006-T031) - Blocks all user stories
- Migration: 15 tasks (T069-T083)
- Polish: 15 tasks (T084-T098)

**Parallel Opportunities Identified**: 31 tasks marked [P] can run concurrently

**Architecture**: Per-project graph partitioning (Option B from research.md) - physically separate AGE graphs per project for maximum performance and isolation
