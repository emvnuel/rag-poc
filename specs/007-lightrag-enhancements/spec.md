# Feature Specification: LightRAG Official Implementation Enhancements

**Feature Branch**: `007-lightrag-enhancements`  
**Created**: 2025-11-26  
**Status**: Draft  
**Input**: User description: "Enhancements from the official impl https://github.com/HKUDS/LightRAG/"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reranker Integration for Improved Retrieval (Priority: P1)

As an application developer querying the knowledge base, I want retrieved text chunks to be reranked using a specialized reranker model so that the most relevant chunks appear first and query responses are more accurate.

**Why this priority**: The official LightRAG implementation highlights reranking as a significant performance booster, especially for "mix" mode queries. Without reranking, the system may return less relevant chunks in the final context, degrading response quality.

**Independent Test**: Can be tested by executing queries with reranking enabled/disabled and comparing the relevance of the top-k returned chunks using a ground truth dataset.

**Acceptance Scenarios**:

1. **Given** a query in "mix" mode with reranking enabled, **When** the query executes, **Then** retrieved chunks are reranked by the configured reranker model before being sent to the LLM
2. **Given** a reranker configuration, **When** the application starts, **Then** the reranker service initializes and is available for query processing
3. **Given** a query with reranking disabled, **When** the query executes, **Then** chunks are returned in their original vector similarity order without reranking
4. **Given** multiple provider options (Cohere, Jina, custom), **When** a provider is configured, **Then** the system uses the specified reranker implementation

---

### User Story 2 - Document Deletion with Knowledge Graph Regeneration (Priority: P1)

As a content administrator, I want to delete a document from the system and have the knowledge graph automatically rebuilt from remaining documents so that query quality is maintained without orphaned or stale graph nodes.

**Why this priority**: Document deletion is a critical maintenance operation. Without proper KG regeneration, deleted documents leave behind orphaned entities and relationships that pollute query results. The official implementation specifically highlights this capability for ensuring query performance post-deletion.

**Independent Test**: Can be tested by ingesting documents, deleting one, and verifying that entities/relations solely from the deleted document are removed while shared entities are rebuilt with updated descriptions.

**Acceptance Scenarios**:

1. **Given** a document that contributed entities to the knowledge graph, **When** the document is deleted, **Then** entities exclusively from that document are removed from the graph
2. **Given** an entity mentioned in multiple documents, **When** one contributing document is deleted, **Then** the entity's description is rebuilt from the remaining documents' cached extraction results
3. **Given** a relationship between entities from different documents, **When** the document containing one entity is deleted, **Then** the relationship is properly handled (removed if source entity is gone, updated if target entity remains)
4. **Given** the deletion operation, **When** it completes, **Then** vector embeddings for removed entities are also deleted from the vector store

---

### User Story 3 - Entity Merging Operations (Priority: P2)

As a knowledge base curator, I want to merge multiple entities that represent the same concept (e.g., "AI", "Artificial Intelligence", "Machine Intelligence") into a single canonical entity so that the knowledge graph is cleaner and queries return more consistent results.

**Why this priority**: Real-world knowledge graphs often contain duplicate entities with slightly different names. Entity merging consolidates these duplicates, improving graph quality and query accuracy. This builds upon the semantic deduplication from spec-002 but provides explicit merge control.

**Independent Test**: Can be tested by creating entities with similar names, executing merge operations, and verifying that all relationships are properly redirected to the merged entity.

**Acceptance Scenarios**:

1. **Given** multiple entities representing the same concept, **When** a merge operation is requested, **Then** all source entities are consolidated into a single target entity
2. **Given** entities with relationships, **When** entities are merged, **Then** all relationships from source entities are redirected to the target entity
3. **Given** a merge operation with conflicting descriptions, **When** merge executes, **Then** descriptions are consolidated using the configured merge strategy (concatenate, keep_first, or LLM summarization)
4. **Given** a merge operation, **When** it completes, **Then** duplicate relationships (now pointing to the same entity pair) are merged with combined weights

---

### User Story 4 - Token Usage Tracking (Priority: P2)

As a system administrator, I want to monitor token consumption during document ingestion and query operations so that I can control API costs and optimize performance.

**Why this priority**: LLM API calls are the primary cost driver. Token tracking enables cost visibility, budget enforcement, and optimization decisions. The official implementation provides a TokenTracker utility specifically for this purpose.

**Independent Test**: Can be tested by performing ingestion and query operations while tracking reported token counts, then comparing against expected values.

**Acceptance Scenarios**:

1. **Given** a document ingestion operation, **When** ingestion completes, **Then** the total input and output tokens consumed are tracked and reported
2. **Given** a query operation, **When** the query completes, **Then** token usage for the query (including keyword extraction and response generation) is tracked
3. **Given** a session with multiple operations, **When** the session ends, **Then** cumulative token usage is available for the entire session
4. **Given** token tracking enabled, **When** an operation executes, **Then** per-operation token breakdown is logged for debugging and optimization

---

### User Story 5 - Knowledge Graph Data Export (Priority: P3)

As a data analyst, I want to export the knowledge graph (entities, relations, and chunks) to various formats (CSV, Excel, Markdown) so that I can analyze the knowledge structure externally or share it with stakeholders.

**Why this priority**: Data export enables analysis, backup, and sharing capabilities. While not critical for core RAG functionality, it provides valuable visibility into the knowledge graph contents.

**Independent Test**: Can be tested by ingesting documents, exporting the graph, and verifying the export contains all expected entities, relations, and optionally vector data.

**Acceptance Scenarios**:

1. **Given** a populated knowledge graph, **When** export is requested in CSV format, **Then** entities and relations are exported with all metadata fields
2. **Given** an export request with `include_vector_data=true`, **When** export executes, **Then** embedding vectors are included in the output
3. **Given** an export request for Excel format, **When** export executes, **Then** entities, relations, and relationships are placed in separate sheets
4. **Given** an export request, **When** export completes, **Then** the file is written to the specified path with proper encoding

---

### Edge Cases

- What happens when the reranker service is unavailable during a query? The system should fall back to non-reranked results with a warning.
- How does the system handle merging entities that have circular relationships with each other?
- What happens when a document deletion affects entities shared across many documents (100+)?
- How does token tracking handle partial failures where some LLM calls succeed and others fail?
- What happens when exporting a very large knowledge graph (100k+ entities) that exceeds memory limits?

## Requirements *(mandatory)*

### Functional Requirements

**Reranker Integration**
- **FR-001**: System MUST support reranking of retrieved text chunks using configurable reranker models
- **FR-002**: System MUST support at least one reranker provider (Cohere, Jina, or custom implementation)
- **FR-003**: System MUST allow reranking to be enabled/disabled per query via QueryParam
- **FR-004**: System MUST gracefully degrade to non-reranked results if the reranker service fails
- **FR-005**: System MUST apply reranking before the chunk token budget truncation

**Document Deletion with KG Regeneration**
- **FR-006**: System MUST remove all chunks associated with a deleted document from chunk storage and vector store
- **FR-007**: System MUST identify entities and relations that were exclusively sourced from the deleted document
- **FR-008**: System MUST remove exclusively-sourced entities and their embeddings from the graph and vector stores
- **FR-009**: System MUST rebuild descriptions for entities/relations that were partially sourced from the deleted document
- **FR-010**: System MUST use cached extraction results (from spec-006) for rebuilding descriptions without re-calling the LLM
- **FR-011**: System MUST update all relationship records when source or target entities are affected

**Entity Merging**
- **FR-012**: System MUST support merging multiple source entities into a single target entity
- **FR-013**: System MUST redirect all relationships from source entities to the target entity
- **FR-014**: System MUST prevent self-loop relationships after merge (when two merged entities had a relationship between them)
- **FR-015**: System MUST support configurable merge strategies for descriptions: concatenate, keep_first, keep_longest, llm_summarize
- **FR-016**: System MUST remove source entities and their embeddings after successful merge
- **FR-017**: System MUST deduplicate relationships after merge (when two source entities had relationships to the same third entity)

**Token Usage Tracking**
- **FR-018**: System MUST track input and output token counts for all LLM calls during ingestion
- **FR-019**: System MUST track input and output token counts for all LLM calls during query processing
- **FR-020**: System MUST provide session-level cumulative token tracking
- **FR-021**: System MUST expose token usage metrics via a programmatic API
- **FR-022**: System MUST support reset of token counters between sessions or operations

**Knowledge Graph Export**
- **FR-023**: System MUST export entities with fields: name, type, description, source_ids, file_paths
- **FR-024**: System MUST export relations with fields: source, target, keywords, description, weight, source_ids
- **FR-025**: System MUST support export formats: CSV, Excel (XLSX), Markdown, plain text
- **FR-026**: System MUST optionally include embedding vectors in exports when requested
- **FR-027**: System MUST handle large exports without loading entire graph into memory (streaming export)

### Key Entities

- **Reranker**: A service that scores and reorders retrieved chunks based on query relevance. Key attributes: provider_type, model_name, api_credentials, top_k
- **MergeOperation**: Represents a pending or completed entity merge. Key attributes: source_entity_ids, target_entity_id, merge_strategy, status
- **TokenUsage**: Tracks token consumption for an operation. Key attributes: operation_type, input_tokens, output_tokens, model_name, timestamp
- **ExportJob**: Represents a graph export request. Key attributes: format, include_vectors, output_path, status, entity_count, relation_count

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Query response relevance improves by at least 15% when reranking is enabled (measured by evaluation metrics or user feedback)
- **SC-002**: Document deletion with KG regeneration completes without orphaned entities for 100% of test cases
- **SC-003**: Entity merge operations complete successfully with all relationships properly redirected for 100% of test cases
- **SC-004**: Token tracking accuracy is within 5% of actual API-reported token counts
- **SC-005**: Knowledge graph export supports graphs with up to 50,000 entities without memory issues
- **SC-006**: Reranker fallback activates within 2 seconds when the reranker service is unavailable

## Out of Scope

The following items are explicitly NOT part of this feature:

- **Multi-model reranking**: Using multiple reranker models in ensemble
- **Real-time token budgeting**: Stopping operations mid-execution when token budget is exceeded
- **Incremental export**: Exporting only changes since last export (differential export)
- **Graph import**: Importing entities/relations from external files (inverse of export)
- **Automatic entity merge suggestions**: ML-based detection of duplicate entities (spec-002 handles semantic dedup during ingestion)
- **Cost estimation**: Predicting token costs before operations execute

## Dependencies

This feature relies on the following existing components:

- **LightRAG Official Implementation (spec-006)**: Extraction caching, keyword extraction, query modes, source tracking
- **Semantic Entity Deduplication (spec-002)**: EntitySimilarityCalculator for potential use in merge candidate detection
- **Graph Storage (spec-001)**: Project-isolated graph operations, AgeGraphStorage
- **Vector Storage**: PgVectorStorage for entity/chunk embeddings
- **LLM Function Interface**: For description summarization during merge and entity rebuild

## Assumptions

The following assumptions are made for this feature:

- **Reranker API availability**: At least one reranker provider (Cohere, Jina) offers stable API access with reasonable rate limits
- **Cached extraction availability**: Document deletion assumes extraction results are cached (per spec-006 FR-021, FR-022)
- **Entity naming consistency**: Entity merge assumes entities are identified by their canonical name after normalization
- **Export file system access**: Export operations have write access to the configured output directory
- **Token tracking accuracy**: Token counts from LLM providers are accurate and consistently reported
- **Backward compatibility**: Existing documents ingested before this feature remain functional; new capabilities apply to new operations
