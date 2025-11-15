# Data Model: Per-Project Graph Architecture

**Feature**: Project-Level Graph Isolation  
**Architecture**: Per-Project Graph Partitioning (Option B)  
**Created**: 2025-11-15  
**Status**: Implementation Ready

## Overview

This document defines the data model for implementing physical graph isolation using Apache AGE's multi-graph capabilities. Each project gets its own dedicated graph, ensuring complete data isolation without relying on property-based filtering.

## Architecture Decision

**Selected Approach**: Per-Project Graph Partitioning

**Rationale** (from research.md):
- ✅ O(1) constant-time performance regardless of total system size
- ✅ P95 latency <300ms for 2-hop traversals (well under 550ms requirement)
- ✅ Physical isolation provides strongest security guarantee
- ✅ No index limitations (Apache AGE doesn't support property indexes)
- ❌ Rejected: Property-based filtering would degrade 10-50x beyond 200K nodes

## Graph Naming Convention

### Format

```
graph_<project_uuid_prefix>
```

### Rules

1. **Prefix**: Always `graph_` to identify managed graphs
2. **UUID Processing**:
   - Take project's UUID v7 (e.g., `01938cc9-7c5e-7890-abcd-1234567890ab`)
   - Remove hyphens: `01938cc97c5e7890abcd1234567890ab`
   - Truncate to 32 characters (AGE graph name limit: 63 chars, leaves room for prefix)
   - Result: `graph_01938cc97c5e7890abcd123456789`

3. **Validation**: Graph names must match `^graph_[a-f0-9]{32}$`

### Examples

| Project UUID | Graph Name |
|--------------|------------|
| `01938cc9-7c5e-7890-abcd-1234567890ab` | `graph_01938cc97c5e7890abcd123456789` |
| `01938cca-1234-7000-8000-abcdef123456` | `graph_01938cca12347000800abcdef1234` |

## Graph Lifecycle

### 1. Creation (Project Creation Event)

**Trigger**: New project created via `ProjectService.createProject()`

**Actions**:
1. Persist project record in PostgreSQL `projects` table
2. Call `GraphStorage.createProjectGraph(projectId)`
3. Execute: `SELECT ag_catalog.create_graph('graph_<uuid_prefix>');`
4. Log: `"Creating graph for project: {projectId}, graph name: {graphName}"`

**Idempotency**: Check if graph exists before creation to handle retries

**Error Handling**: If graph creation fails, rollback project creation

### 2. Usage (Document Upload & Query)

**Entity/Relation Operations**: All operations scoped to project's graph

**Cypher Query Routing**:
```sql
SELECT * FROM ag_catalog.cypher('graph_<uuid_prefix>', $$
    MATCH (n:Entity {name: $entityName})
    RETURN n
$$) as (n agtype);
```

**No Model Changes**: Entity and Relation classes remain unchanged - no `projectId` properties

### 3. Deletion (Project Deletion Event)

**Trigger**: Project deleted via `ProjectService.deleteProject()`

**Actions**:
1. Call `GraphStorage.deleteProjectGraph(projectId)`
2. Execute: `SELECT ag_catalog.drop_graph('graph_<uuid_prefix>', true);` (cascade=true)
3. Delete project record from `projects` table
4. Log: `"Deleting graph for project: {projectId}, graph name: {graphName}"`

**Performance**: <60 seconds for 10K entities (Success Criteria SC-003)

**Error Handling**: Log errors but continue with project deletion (orphaned graphs can be cleaned up separately)

## Data Isolation Guarantees

### Physical Separation

- Each graph is a separate Apache AGE namespace
- Cypher queries cannot cross graph boundaries
- No shared vertex/edge ID space between graphs
- Complete isolation at the database level

### Entity Deduplication Scope

**Within Project**:
```cypher
MERGE (n:Entity {name: $name})
ON CREATE SET n.description = $desc
ON MATCH SET n.description = n.description + '\n\n' + $desc
```
Result: Single merged entity node

**Across Projects**:
- Same entity name in Project A and Project B creates TWO separate nodes
- Each project's graph maintains its own entity namespace
- No deduplication across project boundaries

### Query Isolation

All query modes automatically scoped to project's graph:
- **LOCAL**: Entity extraction + local graph traversal
- **GLOBAL**: Community detection within project graph
- **HYBRID**: Vector search + project graph traversal
- **NAIVE**: Vector-only (no cross-project concerns)
- **MIX**: Combined approach scoped to project

## Storage Layer Impact

### GraphStorage Interface Changes

**New Lifecycle Methods**:
```java
CompletableFuture<Void> createProjectGraph(@NotNull String projectId);
CompletableFuture<Void> deleteProjectGraph(@NotNull String projectId);
CompletableFuture<Boolean> graphExists(@NotNull String projectId);
```

**Updated Entity/Relation Methods** (projectId as first parameter):
```java
CompletableFuture<Void> upsertEntity(@NotNull String projectId, @NotNull Entity entity);
CompletableFuture<Entity> getEntity(@NotNull String projectId, @NotNull String entityName);
CompletableFuture<List<Entity>> getAllEntities(@NotNull String projectId);
// ... similar for relations
```

### No Entity/Relation Model Changes

**Current Models** (unchanged):
```java
public class Entity {
    private String name;
    private String type;
    private String description;
    private String sourceId;
    // No projectId field needed - isolation at graph level
}

public class Relation {
    private String srcId;
    private String tgtId;
    private String description;
    private String keywords;
    private double weight;
    private String sourceId;
    // No projectId field needed - isolation at graph level
}
```

**Rationale**: Physical graph separation eliminates need for property-based filtering

## Performance Characteristics

### Time Complexity

- **Graph Creation**: O(1) - constant time
- **Entity Lookup**: O(log n) where n = entities in PROJECT (not total system)
- **2-Hop Traversal**: O(d²) where d = average degree in PROJECT graph
- **Project Deletion**: O(n) where n = entities in PROJECT

### Scalability

| System Size | Project Size | Query P95 | Notes |
|-------------|--------------|-----------|-------|
| 100 projects | 1K entities | <100ms | Small scale |
| 1,000 projects | 10K entities | <300ms | Medium scale |
| 10,000 projects | 100K entities | <300ms | Large scale - no degradation! |

**Key Insight**: Performance independent of total projects in system

## Migration Strategy

### From Shared Graph to Per-Project Graphs

**Phase 1: Identify Projects**
```sql
SELECT DISTINCT project_id 
FROM documents 
WHERE id IN (
    SELECT DISTINCT source_id::uuid 
    FROM ag_catalog.cypher('graph_name', $$
        MATCH (n:Entity)
        RETURN n.sourceId
    $$) as (source_id agtype)
);
```

**Phase 2: Create Project Graphs**
```sql
-- For each project_id
SELECT ag_catalog.create_graph('graph_<uuid_prefix>');
```

**Phase 3: Copy Entities/Relations**
```cypher
-- Batch copy in 1000-node chunks
MATCH (n:Entity)
WHERE n.sourceId IN $sourceIdsForProject
WITH n
CREATE (new:Entity {
    name: n.name,
    type: n.type,
    description: n.description,
    sourceId: n.sourceId
})
```

**Phase 4: Verify & Drop Shared Graph**
```sql
-- Verify counts match
SELECT COUNT(*) FROM old_graph_entities;
SELECT SUM(entity_count) FROM project_graphs;

-- After 100% verification
SELECT ag_catalog.drop_graph('old_shared_graph', true);
```

**Batching**: 1000 nodes per transaction to avoid long locks

## Monitoring & Observability

### Key Metrics

1. **Graph Count**: `SELECT COUNT(*) FROM ag_catalog.ag_graph;`
2. **Per-Project Stats**: `GraphStorage.getStats(projectId)` → entity/relation counts
3. **Orphaned Graphs**: Graphs without corresponding project in `projects` table
4. **Creation Latency**: Time to create new graph (target: <100ms)
5. **Deletion Latency**: Time to drop graph (target: <60s for 10K entities)

### Logging

**Structured Log Fields**:
- `projectId`: UUID of project
- `graphName`: Generated graph name
- `operation`: CREATE | DELETE | QUERY
- `entityCount`: Number of entities in graph
- `relationCount`: Number of relations in graph
- `durationMs`: Operation duration

**Example Log Entry**:
```
INFO: Creating graph for project: 01938cc9-7c5e-7890-abcd-1234567890ab, graph name: graph_01938cc97c5e7890abcd123456789
```

## Edge Cases & Constraints

### Graph Name Collisions

**Risk**: Low - UUID v7 provides 128 bits of randomness  
**Mitigation**: Validation in `createProjectGraph()` checks existence before creation

### Maximum Graphs Per Database

**PostgreSQL Limit**: No hard limit on schemas (graphs are schemas)  
**Practical Limit**: Tested up to 10,000 graphs without issues  
**Recommendation**: Monitor filesystem inode usage (each graph creates schema)

### Graph Deletion Failures

**Scenario**: `drop_graph()` fails due to active connections  
**Mitigation**:
1. Retry with exponential backoff (3 attempts)
2. If still fails, log ERROR and continue (cleanup can be retried)
3. Admin can manually cleanup orphaned graphs

### Concurrent Graph Creation

**Scenario**: Same projectId creates graph twice (race condition)  
**Mitigation**: Check `graphExists()` before creation (idempotency)  
**AGE Behavior**: `create_graph()` throws error if graph exists

### Project ID Validation

**Requirements**:
- Must be valid UUID v7 format
- Must not be null or empty
- Must correspond to existing project in `projects` table (eventually consistent)

**Validation**: `validateProjectId()` helper throws `IllegalArgumentException` if invalid

## Testing Strategy

### Unit Tests (AgeGraphStorageTest.java)

- `testCreateProjectGraphCreatesIsolatedGraph()` - two projects, both exist
- `testEntitiesIsolatedBetweenProjects()` - same entity name, separate nodes
- `testRelationsIsolatedBetweenProjects()` - same relation, separate graphs
- `testEntityDeduplicationWithinProjectOnly()` - merge in project, separate across
- `testCrossProjectQueryReturnsEmpty()` - query wrong project → zero results
- `testDeleteProjectRemovesAllGraphData()` - graph no longer exists after delete
- `testDeleteProjectCompletesWithin60Seconds()` - 10K entities < 60s

### Integration Tests (ProjectIsolationIT.java)

- `testGlobalQueryModeScopedToProject()` - global mode isolation
- `testHybridQueryModeScopedToProject()` - hybrid mode isolation
- `testLocalQueryModeScopedToProject()` - local mode isolation
- `testMixQueryModeScopedToProject()` - mix mode isolation
- `testNaiveQueryModeScopedToProject()` - naive mode isolation

### Performance Tests

- `testGraphCreationUnder100ms()` - P95 <100ms
- `test2HopTraversalUnder300ms()` - P95 <300ms
- `testConcurrent50ProjectUploads()` - no lock contention

## Success Criteria Mapping

| Criteria | How Achieved |
|----------|--------------|
| **SC-001**: Zero cross-project leakage | Physical graph separation - queries cannot cross boundaries |
| **SC-002**: P95 <550ms (target: <300ms) | O(1) routing, O(log n) lookups scoped to project size |
| **SC-003**: Deletion <60s for 10K entities | `drop_graph(cascade=true)` drops all in single transaction |
| **SC-004**: Migration without data loss | Batch copy with 100% verification before dropping old graph |
| **SC-005**: 100 concurrent uploads | No shared graph lock - each project has independent graph |
| **SC-006**: All query modes work | All executors updated to pass projectId for routing |

## References

- [research.md](./research.md) - Architecture decision rationale
- [plan.md](./plan.md) - Implementation phases
- [spec.md](./spec.md) - User stories and requirements
- Apache AGE Documentation: https://age.apache.org/
- PostgreSQL Schema Management: https://www.postgresql.org/docs/current/ddl-schemas.html
