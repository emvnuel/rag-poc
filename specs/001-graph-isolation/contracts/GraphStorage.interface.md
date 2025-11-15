# GraphStorage Interface Contract

**Feature**: Project-Level Graph Isolation  
**Version**: 2.0.0 (Updated for per-project graphs)  
**Status**: Implementation Ready

## Overview

This document defines the interface contract for `GraphStorage` implementations supporting per-project graph isolation. All implementations must guarantee physical data isolation between projects.

## Interface Changes

### New Lifecycle Methods

#### createProjectGraph

```java
/**
 * Creates a new isolated graph for a project.
 * 
 * @param projectId the project UUID (must be valid UUID v7 format)
 * @return a CompletableFuture that completes when the graph is created
 * @throws IllegalArgumentException if projectId is null or invalid UUID format
 * @throws IllegalStateException if graph already exists for this project
 */
CompletableFuture<Void> createProjectGraph(@NotNull String projectId);
```

**Behavior**:
- Generate graph name from projectId using `graph_<uuid_prefix>` convention
- Execute `ag_catalog.create_graph(graphName)`
- Idempotent: Check existence before creation
- Log INFO: "Creating graph for project: {projectId}, graph name: {graphName}"

**Performance**: Target <100ms P95

#### deleteProjectGraph

```java
/**
 * Deletes a project's graph and all associated data.
 * 
 * @param projectId the project UUID
 * @return a CompletableFuture that completes when the graph is deleted
 * @throws IllegalArgumentException if projectId is null or invalid UUID format
 */
CompletableFuture<Void> deleteProjectGraph(@NotNull String projectId);
```

**Behavior**:
- Generate graph name from projectId
- Execute `ag_catalog.drop_graph(graphName, cascade=true)`
- Graceful: If graph doesn't exist, log WARNING and continue
- Log INFO: "Deleting graph for project: {projectId}, graph name: {graphName}"

**Performance**: Target <60 seconds for 10K entities (Success Criteria SC-003)

#### graphExists

```java
/**
 * Checks if a graph exists for a project.
 * 
 * @param projectId the project UUID
 * @return a CompletableFuture<Boolean> - true if graph exists, false otherwise
 * @throws IllegalArgumentException if projectId is null or invalid UUID format
 */
CompletableFuture<Boolean> graphExists(@NotNull String projectId);
```

**Behavior**:
- Query `ag_catalog.ag_graph` to check existence
- No side effects - read-only operation

**Performance**: Target <10ms

### Updated Entity Methods

All entity methods now require `projectId` as the **first parameter** to route operations to the correct graph.

#### upsertEntity

```java
/**
 * Inserts or updates an entity in the project's graph.
 * Uses MERGE semantics for deduplication within the project.
 * 
 * @param projectId the project UUID
 * @param entity the entity to upsert
 * @return a CompletableFuture that completes when the entity is upserted
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<Void> upsertEntity(@NotNull String projectId, @NotNull Entity entity);
```

**Isolation Guarantee**: Entities with same name in different projects are separate nodes

#### getEntity

```java
/**
 * Retrieves an entity by name from the project's graph.
 * 
 * @param projectId the project UUID
 * @param entityName the entity name
 * @return a CompletableFuture<Entity> - the entity, or null if not found
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<Entity> getEntity(@NotNull String projectId, @NotNull String entityName);
```

**Isolation Guarantee**: Only returns entities from the specified project's graph

#### getEntities

```java
/**
 * Retrieves multiple entities by name from the project's graph.
 * 
 * @param projectId the project UUID
 * @param entityNames the list of entity names
 * @return a CompletableFuture<List<Entity>> - found entities (may be partial)
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<List<Entity>> getEntities(@NotNull String projectId, @NotNull List<String> entityNames);
```

**Isolation Guarantee**: Only searches within project's graph

#### getAllEntities

```java
/**
 * Retrieves all entities from the project's graph.
 * 
 * @param projectId the project UUID
 * @return a CompletableFuture<List<Entity>> - all entities in project
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<List<Entity>> getAllEntities(@NotNull String projectId);
```

**Isolation Guarantee**: Returns only entities from project's graph (no cross-project leakage)

#### upsertEntities (Batch)

```java
/**
 * Batch upserts multiple entities in the project's graph.
 * 
 * @param projectId the project UUID
 * @param entities the list of entities to upsert
 * @return a CompletableFuture that completes when all entities are upserted
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<Void> upsertEntities(@NotNull String projectId, @NotNull List<Entity> entities);
```

**Batching**: Recommended batch size 1000 entities (from research findings)

### Updated Relation Methods

All relation methods now require `projectId` as the **first parameter**.

#### upsertRelation

```java
/**
 * Inserts or updates a relation in the project's graph.
 * 
 * @param projectId the project UUID
 * @param relation the relation to upsert
 * @return a CompletableFuture that completes when the relation is upserted
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<Void> upsertRelation(@NotNull String projectId, @NotNull Relation relation);
```

**Isolation Guarantee**: Relations only connect entities within the same project graph

#### getRelationsForEntity

```java
/**
 * Retrieves all relations for an entity in the project's graph.
 * 
 * @param projectId the project UUID
 * @param entityName the entity name
 * @return a CompletableFuture<List<Relation>> - relations where entity is source or target
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<List<Relation>> getRelationsForEntity(@NotNull String projectId, @NotNull String entityName);
```

**Isolation Guarantee**: Only returns relations within project's graph

#### getAllRelations

```java
/**
 * Retrieves all relations from the project's graph.
 * 
 * @param projectId the project UUID
 * @return a CompletableFuture<List<Relation>> - all relations in project
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<List<Relation>> getAllRelations(@NotNull String projectId);
```

**Isolation Guarantee**: Returns only relations from project's graph

#### upsertRelations (Batch)

```java
/**
 * Batch upserts multiple relations in the project's graph.
 * 
 * @param projectId the project UUID
 * @param relations the list of relations to upsert
 * @return a CompletableFuture that completes when all relations are upserted
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<Void> upsertRelations(@NotNull String projectId, @NotNull List<Relation> relations);
```

**Batching**: Recommended batch size 1000 relations

### Updated Statistics Method

#### getStats

```java
/**
 * Retrieves statistics for the project's graph.
 * 
 * @param projectId the project UUID
 * @return a CompletableFuture<Map<String, Object>> containing:
 *         - "entityCount": Number of entities in project
 *         - "relationCount": Number of relations in project
 *         - "graphName": The graph name for this project
 * @throws IllegalArgumentException if projectId is null or invalid
 * @throws IllegalStateException if graph doesn't exist for project
 */
CompletableFuture<Map<String, Object>> getStats(@NotNull String projectId);
```

**Isolation Guarantee**: Statistics scoped to project's graph only

## Backward Compatibility

**Breaking Changes**: All existing methods now require `projectId` parameter

**Migration Path**:
1. Update all callers to pass `projectId`
2. Create project graphs for existing data via migration script
3. Deprecate/remove old shared graph

**Note**: Entity and Relation model classes remain unchanged (no `projectId` field added)

## Error Handling

### IllegalArgumentException

**When**: projectId is null, empty, or invalid UUID format

**Example**:
```java
throw new IllegalArgumentException("projectId must be a valid UUID v7 format");
```

### IllegalStateException

**When**: Graph doesn't exist for project, but operation requires it

**Example**:
```java
throw new IllegalStateException("Graph not found for project: " + projectId);
```

**Recovery**: Call `createProjectGraph(projectId)` first

### Concurrency

**Thread Safety**: All methods must be thread-safe

**Concurrent Operations**:
- Multiple projects → No contention (separate graphs)
- Same project → Implementation must handle (e.g., transaction isolation)

## Performance Guarantees

Based on research findings (research.md):

| Operation | Target P95 | Notes |
|-----------|-----------|-------|
| createProjectGraph() | <100ms | Graph creation overhead |
| deleteProjectGraph() | <60s for 10K entities | SC-003 requirement |
| graphExists() | <10ms | Simple metadata query |
| upsertEntity() | <50ms | Single entity MERGE |
| getEntity() | <20ms | Single entity lookup |
| getAllEntities() | <300ms | Depends on project size |
| 2-hop traversal | <300ms | SC-002 target (requirement: <550ms) |

**Scalability**: Performance independent of total number of projects in system

## Testing Requirements

### Unit Tests (per Constitution §II)

Required test coverage for implementations:

1. **Lifecycle Tests**:
   - `testCreateProjectGraphCreatesIsolatedGraph()` - verify independent graphs
   - `testCreateProjectGraphIdempotent()` - handle duplicate calls
   - `testDeleteProjectGraphRemovesData()` - verify cascade deletion
   - `testGraphExistsReturnsTrueAfterCreate()` - existence check

2. **Isolation Tests**:
   - `testEntitiesIsolatedBetweenProjects()` - same name, separate nodes
   - `testRelationsIsolatedBetweenProjects()` - no cross-project edges
   - `testCrossProjectQueryReturnsEmpty()` - wrong project → zero results
   - `testEntityDeduplicationWithinProjectOnly()` - merge in same project

3. **Performance Tests**:
   - `testGraphCreationUnder100ms()` - P95 latency
   - `testDeleteProjectCompletesWithin60Seconds()` - 10K entities
   - `test2HopTraversalUnder300ms()` - query performance

4. **Error Handling Tests**:
   - `testInvalidProjectIdThrowsException()` - validation
   - `testOperationOnNonExistentGraphThrowsException()` - state check
   - `testDeleteNonExistentProjectGraceful()` - idempotency

### Integration Tests

See [quickstart.md](../quickstart.md) for end-to-end testing scenarios

## Implementation Checklist

For implementers of this interface:

- [ ] Implement graph name generation: `graph_<uuid_prefix>` (32 chars)
- [ ] Validate projectId format: `validateProjectId()` helper
- [ ] Add logging with structured fields (projectId, graphName, operation)
- [ ] Route all Cypher queries via `ag_catalog.cypher('graph_name', $$...$$)`
- [ ] Handle idempotency for create/delete operations
- [ ] Add Javadoc to all public methods with @param and @return
- [ ] Ensure thread safety (CompletableFuture proper usage)
- [ ] Write unit tests achieving >80% branch coverage (Constitution §II)
- [ ] Verify performance targets with load testing
- [ ] Add DEBUG logs for all graph operations showing routing

## Success Criteria Validation

This interface enables achievement of:

- **SC-001**: Zero cross-project data leakage → Physical graph separation
- **SC-002**: P95 <550ms (target: <300ms) → O(log n) scoped to project size
- **SC-003**: Deletion <60s for 10K entities → `drop_graph(cascade=true)`
- **SC-004**: Migration without data loss → Batch copy with verification
- **SC-005**: 100 concurrent uploads → No shared graph locks
- **SC-006**: All query modes work → Consistent projectId propagation

## References

- [data-model.md](../data-model.md) - Graph naming and lifecycle
- [plan.md](../plan.md) - Implementation phases
- [research.md](../research.md) - Architecture decision rationale
- Apache AGE Cypher Functions: https://age.apache.org/docs/cypher/
