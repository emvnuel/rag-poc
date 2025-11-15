# Feature Specification: Project-Level Graph Isolation

**Feature Branch**: `001-graph-isolation`  
**Created**: 2025-11-15  
**Status**: Draft  
**Input**: User description: "Isolate graphs per project. Currently Graphs are not isolated per project. While vector storage (PgVector) properly isolates data by project_id, the graph storage (Apache AGE) operates on a single shared graph across all projects, leading to data leakage and cross-project contamination."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Data Isolation Guarantee (Priority: P1)

As a project owner, I need complete assurance that my knowledge graph data (entities and relationships) is isolated from other projects, so that sensitive information remains private and query results only reflect my project's documents.

**Why this priority**: Data isolation is a fundamental security and privacy requirement for any multi-tenant system. Without proper isolation, users' proprietary information can leak to other tenants, violating data protection regulations and destroying trust.

**Independent Test**: Can be fully tested by creating two projects with overlapping entity names (e.g., both mention "Apple Inc."), uploading documents to each, then querying both projects to verify that results contain only entities/relationships from the respective project with zero cross-contamination.

**Acceptance Scenarios**:

1. **Given** two projects (Project A and Project B) exist in the system, **When** Project A uploads a document mentioning "John Smith - CEO", **Then** Project B's queries must never return entities or relationships about "John Smith" even if Project B later uploads a document with the same person name.

2. **Given** Project A has built a knowledge graph with 100 entities, **When** Project B creates a new knowledge graph with overlapping entity names, **Then** entity merging/deduplication must occur only within each project boundary, never across projects.

3. **Given** a user belongs to multiple projects, **When** the user switches between projects and runs queries, **Then** each query must return results strictly scoped to the active project's graph with no entity or relationship bleed from other projects.

---

### User Story 2 - Graph Query Scoping (Priority: P2)

As a system administrator, I need all graph queries (local, global, hybrid modes) to automatically scope to the active project, so that users never have to manually filter results and cannot accidentally access other projects' data.

**Why this priority**: Automatic scoping prevents developer errors and security vulnerabilities. Manual filtering is error-prone and creates opportunities for data leakage through forgotten filters or injection attacks.

**Independent Test**: Can be tested by executing all five query modes (LOCAL, GLOBAL, HYBRID, NAIVE, MIX) against multiple projects with similar content and verifying that each mode's entity extraction, relationship traversal, and context assembly only touches the active project's graph subgraph.

**Acceptance Scenarios**:

1. **Given** Project A contains documents about "artificial intelligence", **When** a user queries Project B about "artificial intelligence", **Then** the global query mode must extract entities and relationships only from Project B's graph, ignoring all of Project A's related entities.

2. **Given** a hybrid query that combines vector search and graph traversal, **When** the query executes against Project C, **Then** both the vector results and graph relationship hops must be filtered to Project C's scope before assembling the final context.

---

### User Story 3 - Project Deletion Cleanup (Priority: P3)

As a project owner, I need the ability to delete my project and have all associated graph data (entities, relationships) permanently removed, so that orphaned data doesn't accumulate and my data is truly deleted upon request.

**Why this priority**: Data retention compliance (GDPR, CCPA) requires complete deletion capabilities. While less urgent than isolation itself, cleanup is essential for long-term system health and legal compliance.

**Independent Test**: Can be tested by creating a project, building a knowledge graph with 50+ entities, then deleting the project and verifying that no entities, relationships, or graph metadata remain in storage after deletion completes.

**Acceptance Scenarios**:

1. **Given** Project D has an active knowledge graph with entities and relationships, **When** the project owner deletes Project D, **Then** all entities, relationships, and graph metadata belonging to Project D must be permanently removed from graph storage within 60 seconds.

2. **Given** multiple projects share common entity names (e.g., "Microsoft"), **When** one project is deleted, **Then** only that project's instance of the entities is removed, leaving other projects' entities intact and functional.

---

### Edge Cases

- What happens when a project reaches its entity limit (if any) and tries to add more entities?
- How does the system handle concurrent writes to the same project's graph from multiple document ingestion jobs?
- What occurs if a graph query spans relationships that cross expected boundaries (bug scenario)?
- How does the system recover if graph isolation metadata becomes corrupted or mismatched with vector storage project IDs?
- What happens when migrating existing shared graph data to isolated per-project graphs?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST store all graph entities and relationships with a project identifier that associates them uniquely to a single project.

- **FR-002**: System MUST prevent any graph query from accessing entities or relationships belonging to projects other than the one specified in the query context.

- **FR-003**: System MUST ensure entity deduplication and merging occurs only within project boundaries, never across different projects.

- **FR-004**: System MUST support complete deletion of all graph data (entities, relationships, metadata) when a project is deleted.

- **FR-005**: System MUST maintain performance characteristics (query latency, ingestion throughput) equivalent to current implementation despite isolation overhead.

- **FR-006**: System MUST provide migration capability to convert existing shared graph data into project-isolated graphs without data loss.

- **FR-007**: System MUST enforce graph isolation at the storage layer, not just at the application query layer, to prevent bypass through direct database access.

- **FR-008**: System MUST validate that every graph operation (insert, update, delete, query) includes a valid project identifier before execution.

- **FR-009**: System MUST log all cross-project data access attempts (if any occur due to bugs) as security events for auditing.

- **FR-010**: System MUST ensure that graph isolation works consistently across all five query modes (LOCAL, GLOBAL, HYBRID, NAIVE, MIX).

### Key Entities

- **Project**: Represents a tenant in the multi-tenant system. Contains isolated collections of documents, vectors, and now knowledge graphs. Has unique identifier (UUID v7), creation date, configuration settings.

- **Entity** (Graph Node): Represents a knowledge graph entity (person, organization, concept, etc.). Attributes include name, type, description, source document references, and now must include project identifier for isolation.

- **Relationship** (Graph Edge): Represents connections between entities. Attributes include relationship type, description, confidence weight, source document references, and must include project identifier matching both connected entities.

- **Graph Metadata**: Configuration and statistics about a project's knowledge graph, including entity count, relationship count, last updated timestamp, schema version.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero cross-project data leakage in isolation testing - 1000+ queries across 10 projects with overlapping content must return 100% project-scoped results.

- **SC-002**: Graph query performance remains within 10% of current benchmarks - P95 latency for 2-hop traversals stays under 550ms (current: 500ms target).

- **SC-003**: Project deletion completes within 60 seconds for projects with up to 10,000 entities and removes 100% of associated graph data.

- **SC-004**: Migration of existing shared graph data to isolated graphs completes without data loss - validation confirms 100% of entities and relationships are preserved and correctly assigned to projects.

- **SC-005**: System handles 100 concurrent document uploads across 50 different projects without isolation failures or performance degradation beyond current baseline.

- **SC-006**: All five query modes (LOCAL, GLOBAL, HYBRID, NAIVE, MIX) operate correctly with project isolation, verified through automated integration tests covering each mode.

## Assumptions

- **A-001**: The current system already has a `project_id` field available in the document and vector storage tables that can be referenced for graph isolation.

- **A-002**: Apache AGE graph storage supports partitioning or subgraph isolation mechanisms (e.g., using node/edge labels with project IDs as properties, or separate graph names per project).

- **A-003**: The number of projects is expected to scale to hundreds or low thousands, not millions (affects choice of isolation strategy).

- **A-004**: Existing production data (if any) in the shared graph can be associated with projects through document ownership relationships for migration purposes.

- **A-005**: Performance overhead of adding project ID filters to every graph query is acceptable within the 10% degradation threshold.

## Dependencies

- **D-001**: Requires understanding of current Apache AGE schema structure and how entities/relationships are stored.

- **D-002**: May require database schema changes to add project ID columns/properties to graph storage tables.

- **D-003**: Depends on existing project management functionality to provide valid project context for all graph operations.

- **D-004**: Migration strategy depends on ability to trace existing entities back to source documents and their associated projects.

## Out of Scope

- Cross-project graph analytics or relationship discovery (may be added as a separate opt-in feature later with explicit permissions).
- Project-level graph access controls beyond owner/member roles (fine-grained entity permissions).
- Graph data export/import between projects (backup/restore is separate concern).
- Performance optimization specific to very large single-project graphs (>1M entities) - focus is on isolation correctness first.
