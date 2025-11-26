# Feature Specification: LightRAG Official Implementation Alignment

**Feature Branch**: `006-lightrag-official-impl`  
**Created**: 2025-11-25  
**Status**: Draft  
**Input**: User description: "Better implementation of the LightRAG using the official implementation as source"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Improved Entity/Relation Extraction Quality (Priority: P1)

As a knowledge worker ingesting documents into the RAG system, I want entity and relationship extraction to follow the official LightRAG methodology so that I get higher quality knowledge graphs with better entity descriptions and more accurate relationships.

**Why this priority**: Entity extraction is the foundation of the knowledge graph. Poor extraction leads to degraded query results. The official implementation uses iterative gleaning (multiple extraction passes) and structured tuple-delimiter parsing that produces more comprehensive and accurate results.

**Independent Test**: Can be tested by ingesting a sample document and comparing the extracted entities/relations count and quality against the current implementation.

**Acceptance Scenarios**:

1. **Given** a document with multiple entities mentioned in different contexts, **When** the document is ingested, **Then** the system extracts entities with accumulated descriptions from all mentions (not just the first occurrence)
2. **Given** a complex document with implicit relationships, **When** entity extraction runs, **Then** the gleaning phase captures additional entities/relations missed in the initial pass
3. **Given** extracted entities and relations, **When** descriptions are merged, **Then** an LLM-based summarization produces coherent descriptions rather than simple concatenation

---

### User Story 2 - Multi-Mode Query Support (Priority: P1)

As an application developer querying the knowledge base, I want to use different query modes (local, global, hybrid, mix, naive, bypass) that match the official LightRAG behavior so that I can choose the appropriate retrieval strategy for different question types.

**Why this priority**: Different queries require different retrieval strategies. Local mode for entity-centric questions, global mode for relationship-centric questions, hybrid for combined approaches, mix for KG+vector, naive for pure vector search, and bypass for direct LLM.

**Independent Test**: Can be tested by executing queries in each mode and verifying the context construction and response generation follows the expected pattern.

**Acceptance Scenarios**:

1. **Given** a local mode query about a specific entity, **When** the query executes, **Then** the system retrieves entity-centric context using low-level keywords
2. **Given** a global mode query about relationships, **When** the query executes, **Then** the system retrieves relationship-centric context using high-level keywords
3. **Given** a hybrid mode query, **When** the query executes, **Then** the system combines local and global results using round-robin merging
4. **Given** a mix mode query, **When** the query executes, **Then** the system includes both KG context and vector-retrieved document chunks

---

### User Story 3 - Keyword Extraction for Query Routing (Priority: P2)

As a system processing user queries, I want automatic extraction of high-level and low-level keywords from queries so that the system can route context retrieval appropriately to entities (low-level) vs relationships (high-level).

**Why this priority**: The official LightRAG uses keyword extraction to determine what context to retrieve. High-level keywords find relationships; low-level keywords find entities. This routing is essential for query quality.

**Independent Test**: Can be tested by submitting queries and verifying the extracted keywords match expected patterns for entity-focused vs relationship-focused questions.

**Acceptance Scenarios**:

1. **Given** a query like "Who is Charlie Gordon?", **When** keywords are extracted, **Then** low-level keywords include entity names ("Charlie Gordon")
2. **Given** a query like "How do characters evolve throughout the story?", **When** keywords are extracted, **Then** high-level keywords capture thematic concepts ("character evolution", "story progression")
3. **Given** extracted keywords, **When** caching is enabled, **Then** keyword extraction results are cached to avoid redundant LLM calls

---

### User Story 4 - Chunk-Based Source Tracking (Priority: P2)

As a system maintaining the knowledge graph, I want to track which chunks contributed to each entity and relationship so that I can properly rebuild descriptions when documents are deleted and maintain citation/provenance.

**Why this priority**: The official implementation maintains source_id tracking per entity/relation. This enables proper document deletion with knowledge graph rebuilding rather than just orphaning graph nodes.

**Independent Test**: Can be tested by ingesting a document, verifying source_id tracking, deleting the document, and verifying affected entities/relations are properly rebuilt or removed.

**Acceptance Scenarios**:

1. **Given** an entity extracted from multiple chunks, **When** the entity is stored, **Then** all contributing chunk IDs are tracked in the source_id field
2. **Given** a document deletion request, **When** deletion executes, **Then** entities/relations solely from that document are removed, and shared ones are rebuilt
3. **Given** rebuilt entities/relations, **When** rebuilding completes, **Then** descriptions are re-summarized from remaining cached extraction results

---

### User Story 5 - LLM Response Caching for Extraction (Priority: P3)

As a system administrator, I want LLM extraction results to be cached per chunk so that document deletion can rebuild knowledge without re-calling the LLM for extraction.

**Why this priority**: Caching extraction results enables the rebuild_knowledge_from_chunks functionality. Without cached results, document deletion cannot properly rebuild affected entities/relations.

**Independent Test**: Can be tested by ingesting a document, verifying cache entries exist, and verifying rebuild uses cached results.

**Acceptance Scenarios**:

1. **Given** a chunk being processed, **When** entity extraction completes, **Then** the extraction result is cached with the chunk_id as reference
2. **Given** a cached extraction result, **When** rebuild is triggered, **Then** the cached result is used instead of re-calling the LLM
3. **Given** multiple extraction rounds (initial + gleaning), **When** caching occurs, **Then** all extraction results are tracked in the chunk's llm_cache_list

---

### Edge Cases

- What happens when an entity name is extremely long (>500 characters)?
- How does the system handle when keyword extraction returns empty results for both high-level and low-level?
- What happens when the gleaning phase returns identical results to the initial extraction?
- How does the system handle circular relationships (A->B->C->A) during graph construction?
- What happens when token limits are exceeded during context building?

## Requirements *(mandatory)*

### Functional Requirements

**Entity Extraction Pipeline**
- **FR-001**: System MUST use the official LightRAG tuple-delimiter format for entity extraction prompts: `entity{tuple_delimiter}name{tuple_delimiter}type{tuple_delimiter}description`
- **FR-002**: System MUST implement gleaning (at least one additional extraction pass) to capture entities missed in the initial extraction
- **FR-003**: System MUST normalize entity names by removing quotes, extra whitespace, and applying consistent casing
- **FR-004**: System MUST truncate entity names exceeding the configured maximum length (default 500 characters)

**Relationship Extraction Pipeline**
- **FR-005**: System MUST use the official LightRAG tuple-delimiter format for relationship extraction: `relation{tuple_delimiter}src{tuple_delimiter}tgt{tuple_delimiter}keywords{tuple_delimiter}description`
- **FR-006**: System MUST prevent self-referential relationships (where source equals target)
- **FR-007**: System MUST normalize relationship pairs to consistent ordering (sorted alphabetically) for deduplication

**Description Merging**
- **FR-008**: System MUST accumulate descriptions from multiple chunks for the same entity/relation
- **FR-009**: System MUST use LLM-based summarization when accumulated descriptions exceed the configurable token threshold
- **FR-010**: System MUST implement the map-reduce pattern for summarizing very long description lists

**Query Pipeline**
- **FR-011**: System MUST support six query modes: local, global, hybrid, mix, naive, bypass
- **FR-012**: System MUST extract high-level and low-level keywords from queries using LLM
- **FR-013**: System MUST route context retrieval based on query mode (entities for local, relations for global, both for hybrid)
- **FR-014**: System MUST implement round-robin merging for combining results from different sources

**Chunk Management**
- **FR-015**: System MUST track source chunk IDs for each entity and relationship
- **FR-016**: System MUST support configurable limits on source_id count per entity/relation with FIFO or KEEP-oldest strategies
- **FR-017**: System MUST maintain file_path tracking for citation purposes

**Token Management**
- **FR-018**: System MUST implement token-based truncation for entity and relation context
- **FR-019**: System MUST dynamically calculate available token budget for chunks based on total token limit minus system prompt and KG context
- **FR-020**: System MUST apply token truncation to chunk content before sending to LLM

**Caching**
- **FR-021**: System MUST cache entity extraction results indexed by chunk_id
- **FR-022**: System MUST support rebuilding entities/relations from cached extraction results
- **FR-023**: System MUST cache keyword extraction results indexed by query hash

### Key Entities

- **Entity**: Represents a named concept extracted from documents. Key attributes: entity_name, entity_type, description, source_id (chunk references), file_path (source document paths)
- **Relation**: Represents a connection between two entities. Key attributes: src_id, tgt_id, keywords, description, weight, source_id, file_path
- **Chunk**: Represents a text segment from a document. Key attributes: content, full_doc_id, chunk_order_index, file_path, tokens, llm_cache_list
- **LLM Cache Entry**: Stores extraction results for rebuild purposes. Key attributes: cache_type, chunk_id, return (extraction result), create_time

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Entity extraction captures 90% or more of named entities that the official Python implementation extracts from the same document
- **SC-002**: Query response relevance improves by at least 20% (measured by user satisfaction or automated evaluation) compared to current implementation
- **SC-003**: Document deletion with knowledge graph rebuild completes successfully for 100% of test cases without orphaned graph nodes
- **SC-004**: System handles documents up to 100,000 tokens without out-of-memory errors during extraction
- **SC-005**: Query response time remains under 5 seconds for 95% of queries with properly configured token limits
- **SC-006**: Cached extraction results enable rebuild without any additional LLM calls for extraction

## Out of Scope

The following items are explicitly NOT part of this feature:

- **Graph-level operations**: Community detection, Leiden clustering, hierarchical summarization (these are advanced LightRAG features for a future iteration)
- **Multi-modal support**: Image, audio, or video entity extraction
- **Streaming ingestion**: Real-time document streaming (current batch model is retained)
- **Cross-project entity resolution**: Entities are scoped per project; no global entity resolution across projects
- **Custom entity types**: User-defined entity types beyond the standard set (PERSON, ORGANIZATION, LOCATION, EVENT, CONCEPT, etc.)
- **External knowledge base integration**: Linking extracted entities to Wikidata, DBpedia, or other external knowledge bases

## Dependencies

This feature relies on the following existing components:

- **EmbeddingFunction interface** (`lightrag/embedding/EmbeddingFunction.java`): For generating vector embeddings of entities and chunks
- **LLMFunction interface** (`lightrag/llm/LLMFunction.java`): For entity extraction, keyword extraction, and description summarization
- **AgeGraphStorage** (`lightrag/storage/impl/AgeGraphStorage.java`): For storing and querying entities and relationships in Apache AGE
- **PgVectorStorage** (`lightrag/storage/impl/PgVectorStorage.java`): For vector similarity search on chunks and entity embeddings
- **Document/Chunk data model** (`document/Document.java`): For source document and chunk representation
- **Project isolation** (spec 001): Entity and graph isolation per project must be maintained

## Assumptions

The following assumptions are made for this feature:

- **LLM capability**: The configured LLM can reliably follow extraction prompts and produce structured output in the expected tuple-delimiter format
- **Token counting**: Accurate token counting is available (or can be approximated) for context management and truncation
- **Database support**: PostgreSQL with Apache AGE and pgvector extensions supports all required operations
- **Embedding quality**: The embedding model produces meaningful vectors that capture semantic similarity for entity deduplication and retrieval
- **Configuration flexibility**: Users can configure thresholds (gleaning passes, token limits, similarity thresholds) via application properties
- **Backward compatibility**: Existing documents and knowledge graphs will continue to work; new features apply to newly ingested documents
