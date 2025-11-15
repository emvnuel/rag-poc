# Implementation Plan: Project-Level Graph Isolation

**Branch**: `001-graph-isolation` | **Date**: 2025-11-15 | **Spec**: [spec.md](./spec.md)  
**Input**: Feature specification from `/specs/001-graph-isolation/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Implement project-level isolation for Apache AGE graph storage to prevent data leakage between projects. Currently, while vector storage (PgVector) correctly filters by `project_id`, the graph storage operates on a single shared graph across all projects, causing entity/relationship contamination. This feature adds `project_id` properties to all graph nodes and edges, updates query methods to filter by project, and provides migration paths for existing data.

**Technical Approach**: Add `projectId` field to Entity and Relation models, update GraphStorage interface to accept project filters, modify AgeGraphStorage Cypher queries to include project_id in MERGE/MATCH operations, update LightRAG core to propagate projectId through extraction pipeline, and create database migration scripts.

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Quarkus 3.28.4, Apache AGE (PostgreSQL extension), PostgreSQL 14+, JUnit 5, REST Assured  
**Storage**: PostgreSQL 14+ with Apache AGE extension for graph storage, pgvector for embeddings  
**Testing**: JUnit 5 with `@QuarkusTest`, REST Assured for API tests, Testcontainers for PostgreSQL integration tests  
**Target Platform**: Linux server (Docker containerized)  
**Project Type**: Single backend project (Quarkus)  
**Performance Goals**: Maintain P95 <550ms for 2-hop graph traversals (current: 500ms target, 10% overhead acceptable)  
**Constraints**: Query performance degradation <10%, storage overhead <5%, zero data loss during migration  
**Scale/Scope**: Support hundreds of projects, each with up to 10K entities; handle 100 concurrent uploads across 50 projects

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Code Quality Standards ✅
- **Jakarta EE compliance**: Feature uses existing Jakarta patterns, no new REST endpoints
- **Naming conventions**: Entity.java, Relation.java (existing), AgeGraphStorage (existing) follow standards
- **Error handling**: Will add IllegalArgumentException for missing projectId validation
- **Javadoc**: Public methods in GraphStorage interface will be documented
- **Cyclomatic complexity**: Cypher query builders may approach limit - monitor during implementation

### II. Testing Standards & Test-First Development ✅
- **TDD required**: Tests MUST be written before implementation
- **Test coverage targets**:
  - Unit tests for AgeGraphStorage project filtering methods (>80% branch coverage)
  - Integration tests for end-to-end isolation with real PostgreSQL + AGE
  - Contract tests for GraphStorage interface methods
- **Test independence**: Each test creates isolated projects with unique UUIDs
- **Testcontainers**: Use PostgreSQL container with AGE extension

### III. API Consistency & User Experience ✅
- **No REST API changes**: Feature is internal storage layer only
- **Existing APIs inherit isolation**: DocumentResources, ChatResources already pass projectId
- **UUID v7 for projectId**: Already in use throughout system

### IV. Performance Requirements ✅ VALIDATED
- **Graph query P95 <550ms**: Target 500ms + 10% overhead
- **Research finding**: Per-project graph partitioning maintains <300ms P95 at all scales
- **Document ingestion <30s**: Entity extraction is part of pipeline - must not add significant overhead
- **100 concurrent uploads**: Graph operations must not create bottlenecks
- **Decision**: Use per-project graphs (Option B) - meets all performance requirements

### V. Observability & Debugging ✅
- **Structured logging**: Add INFO logs for project isolation enforcement
- **Metrics tracking**: Add projectId to existing graph query latency metrics
- **Error logging**: Log any cross-project access attempts as WARN
- **Trace propagation**: projectId already flows through system via context

### Performance Standards ✅
- **Database queries**: Per-project graphs eliminate need for property indexing
- **No N+1 queries**: Batch entity operations already implemented
- **Query timeout 5s**: Existing Quarkus connection pool settings apply
- **Project isolation mandatory**: Physical graph separation enforces constitution requirement

### Quality Gates ✅
- **Build**: `./mvnw clean package` must pass
- **Tests**: `./mvnw test` (unit + integration) must pass
- **Coverage**: >80% branch coverage via Jacoco
- **Migration scripts**: Required for database schema changes (07-add-project-id-to-graph.sql)

## Project Structure

### Documentation (this feature)

```text
specs/001-graph-isolation/
├── plan.md              # This file
├── research.md          # Phase 0: AGE property indexing, performance impact
├── data-model.md        # Phase 1: Entity/Relation schema updates
├── quickstart.md        # Phase 1: Developer guide for testing isolation
├── contracts/           # Phase 1: GraphStorage interface contract
└── checklists/
    └── requirements.md  # Spec validation checklist
```

### Source Code (repository root)

```text
src/main/java/br/edu/ifba/
├── lightrag/
│   ├── core/
│   │   ├── Entity.java                    # NO CHANGES (projectId not needed)
│   │   ├── Relation.java                  # NO CHANGES (projectId not needed)
│   │   └── LightRAG.java                  # UPDATE to pass projectId to storage layer
│   ├── storage/
│   │   ├── GraphStorage.java              # UPDATE interface: add graph lifecycle methods
│   │   └── impl/
│   │       └── AgeGraphStorage.java       # UPDATE: add graph management, use project-specific graphs
│   └── query/
│       ├── GlobalQueryExecutor.java       # UPDATE to pass projectId to graph ops
│       ├── HybridQueryExecutor.java       # UPDATE to pass projectId to graph ops
│       └── MixQueryExecutor.java          # UPDATE to pass projectId to graph ops
│
├── project/
│   ├── ProjectService.java                # UPDATE: create graph on project creation
│   └── ProjectResources.java              # UPDATE: delete graph on project deletion
│
└── document/
    └── DocumentService.java                # Already passes projectId - no changes

src/test/java/br/edu/ifba/
├── lightrag/
│   └── storage/
│       └── AgeGraphStorageTest.java       # NEW: Unit tests for per-project graphs
└── ProjectIsolationIT.java                # NEW: End-to-end integration tests

docker-init/
├── 02-init-age.sql                         # UPDATE: Document per-project graph approach
└── 07-migrate-to-per-project-graphs.sql   # NEW: Migration script

scripts/
├── migrate-graph-isolation.sh             # NEW: Multi-project graph migration
└── rollback-graph-isolation.sh            # NEW: Rollback to shared graph (emergency only)
```

**Structure Decision**: Single backend project (Option 1) - this is a storage layer enhancement affecting existing Quarkus Java backend. No frontend changes needed. Test structure follows existing pattern: unit tests under `src/test/java` matching source package structure, integration tests with `IT` suffix.

**Architecture Decision**: Per-project graph partitioning eliminates need for Entity/Relation model changes. Complexity concentrated in AgeGraphStorage graph routing logic and ProjectService lifecycle management.

## Complexity Tracking

> **No violations** - feature aligns with all constitution principles. Implementation adds necessary complexity (graph lifecycle management, graph name routing) to satisfy constitution's scalability requirement: "Graph storage: Use project-specific AGE subgraphs to prevent cross-tenant leakage" (Constitution §IV).

**Complexity Justification**:
- **Added**: Graph name resolution logic (`projectId` → `graph_<uuid_prefix>`)
- **Added**: Graph lifecycle methods in GraphStorage interface
- **Reason**: Per-project graphs provide best performance/security trade-off per research findings
- **Alternative rejected**: Property-based filtering (GIN indexes) fails constitution performance requirements at scale
- **Cyclomatic complexity**: Graph name resolution is O(1) string manipulation (complexity=2, under limit of 15)

## Phase 0: Research & Discovery

**Status**: ✅ **COMPLETE** - See `research.md` for full findings

### Key Findings

1. **Apache AGE Property Indexing**: ❌ NOT SUPPORTED
   - AGE does not support native property indexes
   - `MATCH (e:Entity {project_id: 'uuid'})` uses sequential scans
   - Performance degrades 10-50x beyond 200K nodes without indexes

2. **Architecture Decision**: ⭐ **Per-Project Graph Partitioning (Option B)**
   - Create separate AGE graph per project: `graph_<project_uuid>`
   - No `project_id` filtering needed - entire graph is project-scoped
   - Performance: <300ms P95 at all scales (meets constitution requirements)
   - Isolation: Physical separation provides strongest security guarantee

3. **Cypher MERGE Semantics**: ✅ VALIDATED
   - `MERGE (e:Entity {name: 'X', project_id: 'Y'})` creates distinct nodes per project
   - Entity deduplication operates within project scope only
   - Relationships MUST include `project_id` to prevent cross-project contamination

4. **Migration Strategy**: ✅ SAFE
   - Create new graph per project, copy entities/relations from shared graph
   - Batch migrations in 1000-node chunks (10-15 min for 10K entities)
   - Full PostgreSQL transaction support with rollback capability

5. **Concurrency Impact**: ✅ NO ISSUES
   - Per-project graphs eliminate cross-project lock contention
   - 50 concurrent project uploads: <10ms lock wait time average

### Architecture Impact

**Updated Design Approach**:
- ~~ADD `projectId` field to Entity/Relation models~~ **NOT NEEDED**
- **ADD** graph lifecycle methods: `createProjectGraph()`, `deleteProjectGraph()`
- **UPDATE** all GraphStorage queries to use project-specific graph name
- **SIMPLIFY** Cypher queries - no `project_id` property filtering required

**Performance Validation**: ✅ Meets Constitution §IV requirements at all scales

## Phase 1: Design & Data Model

**Prerequisites**: research.md complete with performance validation

### 1.1 Data Model Changes

**File**: `data-model.md`

Document the per-project graph architecture:

**Entity Model** (src/main/java/br/edu/ifba/lightrag/core/Entity.java):
```
Entity {
  - entityName: String (existing)
  - entityType: String (existing)
  - description: String (existing)
  - sourceId: String (existing - chunk ID)
  NO CHANGES - projectId not needed (graph-level isolation)
}
```

**Relation Model** (src/main/java/br/edu/ifba/lightrag/core/Relation.java):
```
Relation {
  - srcId: String (existing - entity name)
  - tgtId: String (existing - entity name)
  - description: String (existing)
  - keywords: String (existing)
  - sourceId: String (existing - chunk ID)
  - weight: double (existing)
  NO CHANGES - projectId not needed (graph-level isolation)
}
```

**Graph Naming Convention**:
```java
// Map project UUID to graph name
String projectId = "01933b8f-7a5e-7890-abcd-1234567890ab";  // UUID v7
String graphName = "graph_01933b8f_7a5e_7890_abcd";  // Truncated to 32 chars

// Sanitization rules:
// 1. Replace hyphens with underscores (AGE naming requirement)
// 2. Truncate to first 32 characters (63-char limit, reserve prefix)
// 3. Collision probability: 2^64 (~10^18 combinations, safe for <1M projects)
```

**Validation Rules**:
- All GraphStorage operations MUST include valid `projectId` parameter
- Graph MUST exist before entity/relation operations (created during project creation)
- Entity uniqueness: `(name)` within graph (graph itself provides project scope)
- Relation uniqueness: `(srcId + tgtId)` within graph

**State Transitions**:
- Project creation → Create graph: `ag_catalog.create_graph('graph_<uuid_prefix>')`
- Project deletion → Drop graph: `ag_catalog.drop_graph('graph_<uuid_prefix>', true)`
- Migration: Copy entities/relations from shared graph to project-specific graphs

### 1.2 API Contracts

**File**: `contracts/GraphStorage.interface.md`

Document GraphStorage interface changes for per-project graphs:

```java
public interface GraphStorage {
    // Graph Lifecycle (NEW)
    CompletableFuture<Void> createProjectGraph(@NotNull String projectId);
    CompletableFuture<Void> deleteProjectGraph(@NotNull String projectId);
    CompletableFuture<Boolean> graphExists(@NotNull String projectId);
    
    // Entity Operations (UPDATED - projectId now required)
    CompletableFuture<Void> upsertEntity(@NotNull String projectId, @NotNull Entity entity);
    CompletableFuture<Entity> getEntity(@NotNull String projectId, @NotNull String entityName);
    CompletableFuture<List<Entity>> getEntities(@NotNull String projectId, @NotNull List<String> entityNames);
    CompletableFuture<List<Entity>> getAllEntities(@NotNull String projectId);
    
    // Relation Operations (UPDATED - projectId now required)
    CompletableFuture<Void> upsertRelation(@NotNull String projectId, @NotNull Relation relation);
    CompletableFuture<List<Relation>> getRelationsForEntity(@NotNull String projectId, @NotNull String entityName);
    CompletableFuture<List<Relation>> getAllRelations(@NotNull String projectId);
    
    // Batch Operations (UPDATED - projectId now required)
    CompletableFuture<Void> upsertEntities(@NotNull String projectId, @NotNull List<Entity> entities);
    CompletableFuture<Void> upsertRelations(@NotNull String projectId, @NotNull List<Relation> relations);
    
    // Stats & Admin
    CompletableFuture<GraphStats> getStats(@NotNull String projectId);
}
```

**Contract Rules**:
- `@NotNull String projectId`: ALL operations require project scope (no null support)
- `createProjectGraph`: Idempotent - succeeds if graph already exists
- `deleteProjectGraph`: Drops entire graph (all entities + relations)
- `graphExists`: Returns false if graph not created yet
- All query methods automatically scoped to project-specific graph (no cross-project leakage possible)

### 1.3 Database Schema Migration

**File**: `contracts/age-migration-schema.sql`

Document the per-project graph migration approach:

**Step 1: Identify Projects in Shared Graph**
```sql
-- Query shared graph to find all unique projects (via document ownership)
SELECT DISTINCT d.project_id 
FROM documents d
WHERE d.id IN (
    SELECT DISTINCT (properties->>'sourceId')::uuid 
    FROM ag_catalog.ag_vertex 
    WHERE label = 'Entity'
);
```

**Step 2: Create Per-Project Graphs**
```sql
-- For each project_id, create new graph
SELECT ag_catalog.create_graph('graph_<project_uuid_prefix>');
```

**Step 3: Copy Entities to Project Graphs**
```cypher
-- For each project, copy entities from shared graph
MATCH (e:Entity)
WHERE e.sourceId IN [/* chunk IDs for project */]
RETURN e.entityName, e.entityType, e.description, e.sourceId

-- Insert into project-specific graph
MERGE (e:Entity {name: '<entityName>'})
SET e.type = '<entityType>', 
    e.description = '<description>',
    e.source_id = '<sourceId>'
```

**Step 4: Copy Relations to Project Graphs**
```cypher
-- For each project, copy relations from shared graph
MATCH (src:Entity)-[r:RELATED_TO]->(tgt:Entity)
WHERE src.sourceId IN [/* chunk IDs for project */]
RETURN src.entityName, tgt.entityName, r.description, r.keywords, r.weight

-- Insert into project-specific graph
MATCH (src:Entity {name: '<srcName>'})
MATCH (tgt:Entity {name: '<tgtName>'})
MERGE (src)-[r:RELATED_TO]->(tgt)
SET r.description = '<description>',
    r.keywords = '<keywords>',
    r.weight = <weight>
```

**Step 5: Verification**
```sql
-- Verify entity count matches across all project graphs
SELECT 
    (SELECT COUNT(*) FROM ag_catalog.ag_vertex WHERE label = 'Entity') AS shared_graph_total,
    (SELECT SUM(entity_count) FROM project_migration_stats) AS project_graphs_total;
```

**Step 6: Drop Shared Graph**
```sql
-- After verification, drop shared graph
SELECT ag_catalog.drop_graph('graph_name', true);
```

**Rollback Strategy**:
1. Keep shared graph until verification complete
2. If migration fails, drop incomplete project graphs
3. Retry migration with corrected logic
4. Only drop shared graph after 100% verification

### 1.4 Quickstart Guide

**File**: `quickstart.md`

Developer guide with these sections:

1. **Setup Local Environment**
   - Start PostgreSQL + AGE: `docker-compose up -d`
   - Run migration: `./scripts/migrate-graph-to-project-isolation.sh`
   - Verify: Check logs for "Migration complete: N entities updated"

2. **Testing Project Isolation**
   ```bash
   # Create two projects
   curl -X POST http://localhost:8080/api/v1/projects -d '{"name":"Project A"}'
   curl -X POST http://localhost:8080/api/v1/projects -d '{"name":"Project B"}'
   
   # Upload documents with same entity names
   curl -X POST http://localhost:8080/api/v1/documents \
     -F "file=@apple-tech.pdf" \
     -F "projectId=<PROJECT_A_ID>"
   
   curl -X POST http://localhost:8080/api/v1/documents \
     -F "file=@apple-fruit.pdf" \
     -F "projectId=<PROJECT_B_ID>"
   
   # Query each project - should return different results
   curl "http://localhost:8080/api/v1/chat?q=What is Apple?&projectId=<PROJECT_A_ID>"
   curl "http://localhost:8080/api/v1/chat?q=What is Apple?&projectId=<PROJECT_B_ID>"
   ```

3. **Running Tests**
   ```bash
   # Unit tests only
   ./mvnw test -Dtest=AgeGraphStorageTest
   
   # Integration tests (requires Docker)
   ./mvnw verify -DskipITs=false -Dit.test=ProjectIsolationIT
   
   # All tests
   ./mvnw verify
   ```

4. **Troubleshooting**
   - Cross-project leakage: Check AgeGraphStorage logs for projectId in Cypher queries
   - Performance degradation: Enable query logging, check for missing indexes
   - Migration failures: Restore from backup `backup_age_*.sql`

### 1.5 Update Agent Context

**Action**: Run `.specify/scripts/bash/update-agent-context.sh opencode`

This will update `AGENTS.md` with:
- New files: Entity.java, Relation.java (projectId field)
- Updated interfaces: GraphStorage.java
- Migration scripts: 07-add-project-id-to-graph.sql
- Test patterns: Project isolation testing with multiple UUIDs

## Phase 2: Post-Design Constitution Verification

**Status**: PENDING (re-check after Phase 1 complete)

### Verification Checklist

- [ ] **Code Quality**: All new methods have Javadoc
- [ ] **Testing**: Unit + integration tests written and passing
- [ ] **API Consistency**: GraphStorage interface backward compatible (`@Nullable projectId`)
- [ ] **Performance**: Benchmarks confirm <10% overhead
- [ ] **Observability**: Logs include projectId in all graph operations
- [ ] **Migration Scripts**: 07-add-project-id-to-graph.sql tested on staging
- [ ] **Documentation**: README.md updated with isolation guarantee

### Performance Validation

Re-run benchmarks from Phase 0 research with final implementation:

| Metric | Before | After | Delta | Status |
|--------|--------|-------|-------|--------|
| P95 2-hop traversal | 500ms | <550ms | <10% | ✅/❌ |
| Entity insert (batch 100) | 1.2s | <1.32s | <10% | ✅/❌ |
| Query all entities (10K) | 800ms | <880ms | <10% | ✅/❌ |

## Implementation Notes

### Critical Path

1. **Phase 0 Research** (BLOCKING): Must validate AGE performance with project_id filtering before proceeding
2. **Data Model Updates** (DEPENDENCY): Entity/Relation models MUST be updated before storage layer
3. **Storage Layer** (CORE): AgeGraphStorage changes are the heart of this feature
4. **Query Executors** (DEPENDENT): Can only update after GraphStorage interface changes
5. **Migration** (FINAL): Run only after all code changes tested and validated

### Risk Mitigation

**Risk 1: Performance Degradation >10%**
- Mitigation: Research phase includes benchmarking with fallback to application-layer caching
- Fallback: If AGE filtering too slow, implement in-memory project scope cache with TTL

**Risk 2: Data Loss During Migration**
- Mitigation: Backup script creates `backup_age_*.sql` before any changes
- Rollback: Tested rollback script restores pre-migration state

**Risk 3: Legacy Data Ambiguity**
- Mitigation: Tag legacy data with `project_id='legacy'`, provide admin tool to reassign
- Long-term: Gradually migrate legacy entities to actual projects via document ownership

### Testing Strategy

**Unit Tests** (src/test/java/br/edu/ifba/lightrag/storage/AgeGraphStorageTest.java):
- Test entity isolation across projects
- Test relation isolation across projects
- Test deleteByProjectId removes all project data
- Test null projectId returns all entities (admin mode)
- Test invalid projectId throws IllegalArgumentException

**Integration Tests** (src/test/java/br/edu/ifba/ProjectIsolationIT.java):
- End-to-end: Upload documents to different projects, verify query isolation
- Concurrent writes: 50 projects uploading simultaneously, verify no cross-contamination
- Performance: Measure P95 latency with isolation vs. without
- Migration: Apply migration script, verify no data loss

**Contract Tests** (GraphStorage interface):
- Validate all methods respect projectId filter
- Verify projectId=null behaves correctly (returns all projects)
- Confirm Entity/Relation models reject null projectId

## Next Steps

1. **Execute Phase 0 Research**: Run research tasks, create research.md
2. **Review Research Findings**: Go/no-go decision based on performance results
3. **Execute Phase 1 Design**: Create data-model.md, contracts/, quickstart.md
4. **Update Agent Context**: Run update-agent-context.sh
5. **Proceed to `/speckit.tasks`**: Generate detailed task breakdown for implementation

**Estimated Timeline**:
- Phase 0 Research: 1-2 days (benchmarking + AGE documentation review)
- Phase 1 Design: 1 day (documentation + contract design)
- Implementation (Phase 2 via tasks): 3-5 days (code changes + tests + migration)
- Total: ~1 week for complete feature delivery

**Blockers**: None identified - all dependencies are internal and under team control.
