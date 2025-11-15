# Feature Specification: Semantic Entity Deduplication

**Feature Branch**: `002-semantic-entity-dedup`  
**Created**: 2025-11-15  
**Status**: Draft  
**Input**: User description: "Improve the LightRAG entity deduplication strategy to be more semantic"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Basic Semantic Matching (Priority: P1)

When a user indexes documents containing entities with slight name variations (e.g., "OpenAI", "Open AI", "openai") that refer to the same real-world entity, the system should recognize them as duplicates and merge them into a single entity node in the knowledge graph.

**Why this priority**: This is the foundational capability that delivers immediate value by reducing knowledge graph noise and improving query accuracy. Without this, the graph becomes cluttered with duplicate entities.

**Independent Test**: Can be fully tested by indexing two documents with entity name variations (e.g., "Apple Inc." and "Apple") and verifying that only one entity node exists in the graph with merged descriptions.

**Acceptance Scenarios**:

1. **Given** two documents are indexed where Document A mentions "Apple Inc." and Document B mentions "Apple", **When** the system extracts entities, **Then** both should map to the same entity node in the graph
2. **Given** entities "New York City", "NYC", and "New York" are extracted from different chunks, **When** deduplication occurs, **Then** they should merge into a single entity with combined descriptions
3. **Given** entities "machine learning" and "Machine Learning" are extracted, **When** deduplication occurs, **Then** they should be treated as the same entity (case-insensitive matching)

---

### User Story 2 - Type-Aware Semantic Matching (Priority: P2)

When a user indexes documents containing entities with the same name but different types (e.g., "Apple" the company vs "Apple" the fruit, "Jordan" the person vs "Jordan" the country), the system should keep them as separate entities rather than incorrectly merging them.

**Why this priority**: Prevents false positive merges that would corrupt the knowledge graph. This builds on P1 by adding disambiguation logic.

**Independent Test**: Can be fully tested by indexing documents mentioning "Apple Inc." (ORGANIZATION) and "apple" (FOOD), verifying they remain separate entities in the graph.

**Acceptance Scenarios**:

1. **Given** an entity "Apple" of type "ORGANIZATION" and another "apple" of type "FOOD", **When** deduplication occurs, **Then** they should remain as two separate entities
2. **Given** entities "Mercury" of type "PLANET" and "Mercury" of type "CHEMICAL_ELEMENT", **When** deduplication occurs, **Then** they should remain separate
3. **Given** entities "Washington" of type "PERSON" and "Washington" of type "LOCATION", **When** deduplication occurs, **Then** they should not be merged

---

### User Story 3 - Description-Based Semantic Similarity (Priority: P3)

When a user indexes documents where entities have different names but nearly identical semantic meanings based on their descriptions (e.g., "CEO of Microsoft" and "Satya Nadella", "The Big Apple" and "New York City"), the system should identify them as potential duplicates and merge them.

**Why this priority**: This is an advanced feature that provides the highest quality deduplication by using semantic understanding of entity descriptions, not just names. It's lower priority because it requires more computational resources and has higher complexity.

**Independent Test**: Can be tested by indexing documents with semantically similar entity descriptions and verifying that entities with high semantic similarity are merged even if names differ.

**Acceptance Scenarios**:

1. **Given** an entity "CEO of Microsoft" with description "leads Microsoft Corporation" and entity "Satya Nadella" with description "Chief Executive Officer at Microsoft", **When** semantic similarity exceeds threshold, **Then** they should be merged
2. **Given** entities "The Big Apple" and "New York City" with similar location descriptions, **When** semantic analysis occurs, **Then** they should be identified as duplicates
3. **Given** entities with low semantic similarity despite similar names, **When** deduplication occurs, **Then** they should remain separate

---

### Edge Cases

- What happens when entity names have typos (e.g., "Microsft" vs "Microsoft")? Should they be merged?
- How does the system handle entities where one has a description and the other doesn't?
- What happens when merging entities with conflicting entity types (e.g., one says "ORGANIZATION", another says "COMPANY")?
- How does the system handle abbreviations vs full names (e.g., "IBM" vs "International Business Machines")?
- What happens when the semantic similarity score is exactly at the threshold boundary?
- How are entity merge decisions logged for auditability?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST detect entity name variations using string similarity algorithms (normalized edit distance, fuzzy matching)
- **FR-002**: System MUST normalize entity names before comparison (lowercase, whitespace trimming, punctuation removal)
- **FR-003**: System MUST compare entity types when determining if two entities are duplicates
- **FR-004**: System MUST generate semantic embeddings for entity names and descriptions
- **FR-005**: System MUST calculate semantic similarity scores between entity embeddings using cosine similarity
- **FR-006**: System MUST merge entities when similarity score exceeds a configurable threshold
- **FR-007**: System MUST combine descriptions from merged entities using the existing description merging strategy
- **FR-008**: System MUST preserve all relationships when merging entities (redirect edges from old entity to merged entity)
- **FR-009**: System MUST make similarity threshold configurable via application properties
- **FR-010**: System MUST handle deduplication within a single batch (same document chunks) and across multiple batches (different documents)
- **FR-011**: System MUST update entity embeddings in vector storage when entities are merged
- **FR-012**: System MUST provide different similarity thresholds for name-based vs description-based matching

### Key Entities *(include if feature involves data)*

- **Entity**: Represents a concept in the knowledge graph with name, type, description, and semantic embedding
- **Entity Similarity Score**: Numeric score (0.0 to 1.0) representing how similar two entities are based on name and description embeddings
- **Deduplication Rule**: Configuration that defines similarity thresholds and matching strategies (name-based, type-aware, description-based)

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Knowledge graph contains 40-60% fewer duplicate entity nodes after indexing a document corpus with known entity variations
- **SC-002**: Query accuracy improves by 25-40% when searching for entities with name variations (measured by precision/recall)
- **SC-003**: Zero false positive merges occur for entities with the same name but different types (100% precision for type-aware matching)
- **SC-004**: Entity deduplication completes within 2x the current processing time (maintains reasonable performance overhead)
- **SC-005**: Users can configure similarity thresholds without code changes (via application.properties)

## Assumptions *(mandatory)*

- The embedding model (currently configured in application.properties) produces semantically meaningful embeddings for entity names and descriptions
- Cosine similarity is an appropriate metric for comparing entity semantic similarity
- The existing LightRAG entity extraction process produces entity types that are consistent enough for type-aware matching
- The PostgreSQL vector extension (pgvector) supports efficient similarity searches needed for deduplication
- Entity merge operations can be performed without breaking existing application functionality
- A similarity threshold of 0.85-0.95 for name-based matching is reasonable (to be tuned based on testing)
- A similarity threshold of 0.90-0.98 for description-based matching is reasonable (higher threshold to avoid false positives)

## Dependencies

- **Embedding Function**: Requires the existing `EmbeddingFunction` interface to generate entity embeddings
- **Vector Storage**: Depends on `PgVectorStorage` for storing and querying entity embeddings
- **Graph Storage**: Depends on `AgeGraphStorage` for entity merge operations and relationship updates
- **Configuration**: Requires new configuration properties in `application.properties` for similarity thresholds

## Constraints

- Must maintain backward compatibility with existing entity storage and retrieval logic
- Cannot significantly degrade document indexing performance (max 2x slowdown acceptable)
- Must work within the limitations of Apache AGE v1.5.0 (entity property updates may be limited)
- Must not break existing entity-based query modes (LOCAL, GLOBAL, HYBRID)
- Must preserve project isolation (entities should only be deduplicated within the same project)

## Out of Scope

- Deduplication of entities across different projects (entities remain isolated per project)
- LLM-based entity resolution (using language models to decide if entities are the same)
- User interface for manually reviewing and approving entity merges
- Batch re-deduplication of existing knowledge graphs (only applies to newly indexed documents)
- Machine learning models for entity linking beyond semantic embeddings
