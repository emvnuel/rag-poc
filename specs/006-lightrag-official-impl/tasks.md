# Implementation Tasks: LightRAG Official Implementation Alignment

**Feature**: 006-lightrag-official-impl  
**Date**: 2025-11-25  
**Source**: [plan.md](./plan.md), [research.md](./research.md), [data-model.md](./data-model.md)

## Current Implementation Status

This section documents what **already exists** in the codebase to avoid duplication:

### Already Implemented (DO NOT DUPLICATE)
| Feature | Location | Notes |
|---------|----------|-------|
| Tuple-delimiter parsing | `LightRAG.java:773-891` | `parseEntityLine()`, `parseRelationLine()` |
| Description merging (concatenation) | `LightRAG.java:901-921` | `mergeDescriptions()` with separator |
| Entity/Relation with `documentId` | `Entity.java`, `Relation.java` | Has `documentId`, `filePath` |
| 6 query modes | `LightRAG.java:518-525` | LOCAL, GLOBAL, HYBRID, MIX, NAIVE, BYPASS |
| HybridQueryExecutor | `HybridQueryExecutor.java` | Combines local + global |
| Self-referential filtering | `Relation.java:142-148` | `getNormalizedPair()` method |
| Semantic entity deduplication | `EntityResolver.java` | Full implementation exists |
| LightRAGConfig (inner class) | `LightRAG.java:1116-1140` | Basic config record |
| GraphStorage interface | `GraphStorage.java` | Full CRUD + batch methods |
| Batch entity/relation storage | `AgeGraphStorage.java` | `upsertEntities()`, `upsertRelations()` |

### What This Feature Adds (ENHANCEMENTS ONLY)
| Enhancement | Current State | Target State |
|-------------|---------------|--------------|
| Gleaning | No iterative extraction | Multi-pass extraction with continue prompt |
| LLM description summarization | Simple concatenation | Threshold-based LLM summarization |
| Keyword extraction for queries | Direct embedding search | LLM extracts high/low keywords |
| Round-robin context merging | Simple concatenation | Interleaved merging with token budget |
| Source chunk tracking | Has `documentId` | Add `sourceChunkIds` list |
| Extraction caching | No cache | `ExtractionCache` table for LLM results |
| Entity name normalization | None | Quote removal, truncation, whitespace |
| Connection health validation | None | `isValid()` check on checkout |

---

## Task Overview

| Phase | Tasks | Priority | Estimated Effort |
|-------|-------|----------|------------------|
| 1. Foundation | T001-T004 | P1 | 2 days |
| 2. Extraction Enhancements | T005-T009 | P1 | 3 days |
| 3. Query Enhancements | T010-T015 | P1 | 3 days |
| 4. Performance & Polish | T016-T019 | P2 | 2 days |

---

## Phase 1: Foundation (Data Model Enhancements)

### T001: Add sourceChunkIds field to Entity

**Files**: `src/main/java/br/edu/ifba/lightrag/core/Entity.java`

**Requirements**: FR-015, FR-016

**Description**: Extend existing Entity class with sourceChunkIds for chunk-level provenance tracking. Entity already has `documentId` for document-level tracking.

**What Already Exists**:
- `documentId: String` field (document-level tracking)
- `filePath: String` field (file path tracking)
- Builder pattern, `with*` methods

**What to Add**:
- [X] Add `sourceChunkIds: List<String>` field (nullable, default empty list)
- [X] Add Builder method: `sourceChunkIds(List<String>)`
- [X] Add `withSourceChunkIds(List<String>)` method
- [X] Add `addSourceChunkId(String)` helper with FIFO eviction at 50 entries
- [X] Update `equals()` and `hashCode()` to include new field
- [X] Unit tests verify serialization, equality, FIFO eviction

**Dependencies**: None

**Status**: COMPLETE

---

### T002: Add sourceChunkIds field to Relation

**Files**: `src/main/java/br/edu/ifba/lightrag/core/Relation.java`

**Requirements**: FR-015, FR-016

**Description**: Extend existing Relation class with sourceChunkIds matching Entity pattern.

**What Already Exists**:
- `documentId: String` field
- `filePath: String` field
- `getNormalizedPair()` for deadlock prevention
- Builder pattern

**What to Add**:
- [X] Add `sourceChunkIds: List<String>` field (nullable, default empty list)
- [X] Add Builder method and `with*` method matching Entity pattern
- [X] Add `addSourceChunkId(String)` with FIFO eviction
- [X] Unit tests verify serialization and eviction

**Dependencies**: T001 (pattern reference)

**Status**: COMPLETE

---

### T003: Create ExtractionCache entity and storage

**Files**: 
- `src/main/java/br/edu/ifba/lightrag/core/ExtractionCache.java` (NEW)
- `src/main/java/br/edu/ifba/lightrag/core/CacheType.java` (NEW)
- `src/main/java/br/edu/ifba/lightrag/storage/ExtractionCacheStorage.java` (NEW)
- `src/main/java/br/edu/ifba/lightrag/storage/impl/PgExtractionCacheStorage.java` (NEW)
- `docker-init/11-add-extraction-cache.sql` (NEW)

**Requirements**: FR-021, FR-022

**Description**: Create infrastructure for caching LLM extraction results to avoid re-extraction.

**What Already Exists**:
- `KVStorage` interface exists but is for chunk content, not structured cache
- `llmCacheStorage` field in LightRAG but unused for extraction

**What to Add**:
- [X] Create `CacheType` enum: `ENTITY_EXTRACTION`, `GLEANING`, `SUMMARIZATION`, `KEYWORD_EXTRACTION`
- [X] Create `ExtractionCache` record with fields: `id`, `projectId`, `cacheType`, `chunkId`, `contentHash`, `result`, `tokensUsed`, `createdAt`
- [X] Create `ExtractionCacheStorage` interface with: `store()`, `get()`, `getByChunkId()`, `deleteByProject()`
- [X] Create SQL migration `11-add-extraction-cache.sql`
- [X] Create `PgExtractionCacheStorage` with `@Retry` annotations
- [X] Integration tests for CRUD operations

**Dependencies**: None

**Status**: COMPLETE

---

### T004: Create centralized LightRAGConfig class

**Files**: `src/main/java/br/edu/ifba/lightrag/core/LightRAGExtractionConfig.java` (NEW)

**Requirements**: FR-002, FR-009, FR-015

**Description**: Create a separate config class for new extraction/query settings using Quarkus `@ConfigMapping`. The existing `LightRAGConfig` record stays for backward compatibility.

**What Already Exists**:
- `LightRAG.LightRAGConfig` record with: `chunkSize`, `chunkOverlap`, `maxTokens`, `topK`, `enableCache`, `kgExtractionBatchSize`, `embeddingBatchSize`, `entityDescriptionMaxLength`, `entityDescriptionSeparator`

**What to Add** (new class, don't modify existing):
- [X] Create `LightRAGExtractionConfig` with `@ConfigMapping(prefix = "lightrag")`
- [X] Nested interface `Gleaning`: `enabled()`, `maxPasses()`
- [X] Nested interface `Description`: `maxTokens()`, `summarizationThreshold()`
- [X] Nested interface `Query`: `keywordExtractionEnabled()`, `contextMaxTokens()`, `entityBudgetRatio()`, `relationBudgetRatio()`, `chunkBudgetRatio()`
- [X] Add properties to `application.properties`
- [X] Unit test configuration binding

**Dependencies**: None

**Status**: COMPLETE

---

## Phase 2: Extraction Pipeline Enhancements

### T005: Create DescriptionSummarizer for LLM-based merging

**Files**:
- `src/main/java/br/edu/ifba/lightrag/core/DescriptionSummarizer.java` (NEW)
- `src/main/java/br/edu/ifba/lightrag/core/LLMDescriptionSummarizer.java` (NEW)

**Requirements**: FR-008, FR-009, FR-010

**Description**: Create LLM-based summarization for when accumulated descriptions exceed token threshold. Currently `mergeDescriptions()` just concatenates.

**What Already Exists**:
- `LightRAG.mergeDescriptions()` - simple concatenation with separator
- `entityDescriptionMaxLength` config (character limit)

**What to Add** (enhancement, not replacement):
- [X] Create `DescriptionSummarizer` interface: `summarize(entityName, descriptions, projectId)`, `needsSummarization(descriptions)`
- [X] Create `LLMDescriptionSummarizer` implementation:
  - `needsSummarization()` checks token count against threshold (not char count)
  - `summarize()` uses LLM for coherent summary when threshold exceeded
  - Map-reduce for very long lists (>10 descriptions)
- [X] Cache results in ExtractionCache with `SUMMARIZATION` type
- [X] Integrate into `storeKnowledgeGraph()` as optional enhancement
- [X] Unit test: threshold detection
- [X] Integration test: LLM summarization

**Dependencies**: T003, T004

**Status**: COMPLETE

---

### T006: Implement iterative gleaning in extraction

**Files**: `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`

**Requirements**: FR-002

**Description**: Add iterative gleaning passes to capture entities missed in initial extraction.

**What Already Exists**:
- `extractKnowledgeGraphFromChunk()` - single LLM call per chunk
- Full tuple-delimiter parsing

**What to Add** (enhancement to existing method):
- [X] After initial extraction in `extractKnowledgeGraphFromChunk()`, run gleaning if `gleaning.enabled=true`
- [X] Use gleaning prompt: "Many entities and relations were missed. Add them below:"
- [X] Merge gleaning results with initial (deduplicate by entity name)
- [X] Stop early if gleaning returns no new entities
- [X] Configurable max passes via `gleaning.maxPasses`
- [X] Cache both initial and gleaning results
- [X] Integration test: gleaning captures additional entities
- [X] Integration test: gleaning disabled produces baseline

**Dependencies**: T003, T004

**Status**: COMPLETE

---

### T007: Add entity name normalization

**Files**: `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`

**Requirements**: FR-003, FR-004

**Description**: Normalize entity names before storage for consistency.

**What Already Exists**:
- `parseEntityLine()` extracts name directly without normalization

**What to Add**:
- [X] Create `normalizeEntityName(String)` private method
- [X] Remove surrounding quotes (single and double)
- [X] Trim leading/trailing whitespace
- [X] Collapse multiple internal spaces to single space
- [X] Truncate to 500 characters (configurable)
- [X] Call in `parseEntityLine()` and `parseRelationLine()` for src/tgt IDs
- [X] Unit tests for each normalization rule

**Dependencies**: T004

**Status**: COMPLETE

---

### T008: Prevent self-referential relationships

**Files**: `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`

**Requirements**: FR-006, FR-007

**Description**: Filter out self-loops at extraction time.

**What Already Exists**:
- `Relation.getNormalizedPair()` - for deadlock prevention, not filtering
- `parseRelationLine()` - no self-loop check

**What to Add**:
- [X] In `parseRelationLine()`, skip relations where `srcId.equalsIgnoreCase(tgtId)`
- [X] Log filtered self-referential relations at DEBUG level
- [X] Unit test: self-referential relation filtered
- [X] Unit test: valid relations pass through

**Dependencies**: None

**Status**: COMPLETE

---

### T009: Persist sourceChunkIds to graph storage

**Files**: `src/main/java/br/edu/ifba/lightrag/storage/impl/AgeGraphStorage.java`

**Requirements**: FR-015, FR-016

**Description**: Update graph storage to read/write sourceChunkIds.

**What Already Exists**:
- `upsertEntity()` stores: `name`, `type`, `description`, `file_path`, `document_id`
- `upsertRelation()` stores: `description`, `keywords`, `weight`, `file_path`, `document_id`

**What to Add**:
- [X] In `upsertEntity()`, add `source_chunk_ids` as JSON array property
- [X] In `getEntity()`, parse `source_chunk_ids` from AGE properties
- [X] In `upsertRelation()`, add `source_chunk_ids` property
- [X] In `getRelation()`, parse `source_chunk_ids`
- [X] Handle null/missing fields gracefully (default to empty list)
- [X] Integration test: source fields round-trip

**Dependencies**: T001, T002

**Status**: COMPLETE

---

## Phase 3: Query Pipeline Enhancements

### T010: Create KeywordExtractor for query routing

**Files**:
- `src/main/java/br/edu/ifba/lightrag/query/KeywordResult.java` (NEW)
- `src/main/java/br/edu/ifba/lightrag/query/KeywordExtractor.java` (NEW)
- `src/main/java/br/edu/ifba/lightrag/query/LLMKeywordExtractor.java` (NEW)

**Requirements**: FR-012, FR-023

**Description**: Create LLM-based keyword extraction for smarter query routing.

**What Already Exists**:
- Query executors use direct embedding similarity
- No keyword-based routing

**What to Add**:
- [X] Create `KeywordResult` record: `highLevelKeywords`, `lowLevelKeywords`, `queryHash`, `cachedAt`
- [X] Create `KeywordExtractor` interface: `extract(query, projectId)`, `getCached(queryHash)`
- [X] Create `LLMKeywordExtractor`:
  - Use prompt matching official format (HIGH_LEVEL/LOW_LEVEL sections)
  - Parse response to extract keyword lists
  - Cache results in ExtractionCache with `KEYWORD_EXTRACTION` type
- [X] Handle empty keyword responses gracefully
- [X] Unit test: prompt generation and response parsing
- [X] Integration test: caching behavior

**Dependencies**: T003, T004

**Status**: COMPLETE

---

### T011: Create ContextMerger with round-robin interleaving

**Files**:
- `src/main/java/br/edu/ifba/lightrag/query/ContextItem.java` (NEW)
- `src/main/java/br/edu/ifba/lightrag/query/MergeResult.java` (NEW)
- `src/main/java/br/edu/ifba/lightrag/query/ContextMerger.java` (NEW)

**Requirements**: FR-014

**Description**: Create context merger that interleaves results instead of concatenating.

**What Already Exists**:
- `HybridQueryExecutor` uses `combinedSources.addAll()` - simple concatenation

**What to Add**:
- [X] Create `ContextItem` record: `content`, `type`, `sourceId`, `filePath`, `tokens`
- [X] Create `MergeResult` record: `mergedContext`, `includedItems`, `totalTokens`, `itemsIncluded`, `itemsTruncated`
- [X] Create `ContextMerger` class:
  - `merge(sources, maxTokens) -> String`
  - `mergeWithMetadata(sources, maxTokens) -> MergeResult`
  - Round-robin interleaving: take one item from each source in rotation
  - Stop when token budget exhausted
- [X] Unit test: even sources, uneven sources, token budget

**Dependencies**: None

**Status**: COMPLETE

---

### T012: Update LocalQueryExecutor to use keyword extraction

**Files**: `src/main/java/br/edu/ifba/lightrag/query/LocalQueryExecutor.java`

**Requirements**: FR-011, FR-013

**Description**: Enhance LocalQueryExecutor to use low-level keywords.

**What Already Exists**:
- Uses embedding similarity directly on query text
- No keyword preprocessing

**What to Add**:
- [X] Inject `KeywordExtractor` (optional)
- [X] On query, extract keywords if enabled
- [X] Use low-level keywords to search entities by name/description
- [X] Fall back to original query if extraction fails/disabled
- [X] Apply token budget for entity context
- [X] Integration test: uses low-level keywords
- [X] Integration test: graceful fallback

**Dependencies**: T010, T004

**Status**: COMPLETE

---

### T013: Update GlobalQueryExecutor to use keyword extraction

**Files**: `src/main/java/br/edu/ifba/lightrag/query/GlobalQueryExecutor.java`

**Requirements**: FR-011, FR-013

**Description**: Enhance GlobalQueryExecutor to use high-level keywords.

**What Already Exists**:
- Uses embedding similarity on query text
- No keyword preprocessing

**What to Add**:
- [X] Inject `KeywordExtractor` (optional)
- [X] Use high-level keywords to search relations
- [X] Fall back to original query if extraction fails/disabled
- [X] Apply token budget for relation context
- [X] Integration test: uses high-level keywords

**Dependencies**: T010, T004

**Status**: COMPLETE

---

### T014: Update HybridQueryExecutor with round-robin merging

**Files**: `src/main/java/br/edu/ifba/lightrag/query/HybridQueryExecutor.java`

**Requirements**: FR-011, FR-014

**Description**: Enhance HybridQueryExecutor with keyword extraction and round-robin merging.

**What Already Exists**:
- Calls local + global in parallel
- Simple `combinedSources.addAll()` concatenation
- Builds context with "Local Context" and "Global Context" sections

**What to Add**:
- [X] Inject `KeywordExtractor` and `ContextMerger`
- [X] Extract both high-level and low-level keywords
- [X] Merge results using round-robin interleaving (replace `addAll()`)
- [X] Respect overall token budget
- [X] Integration test: round-robin produces diverse context

**Dependencies**: T010, T011, T012, T013

**Status**: COMPLETE

---

### T015: Add token budget configuration and enforcement

**Files**: 
- `src/main/resources/application.properties`
- `src/main/java/br/edu/ifba/lightrag/utils/TokenUtil.java`

**Requirements**: FR-018, FR-019, FR-020

**Description**: Add configurable token budgets for query context.

**What Already Exists**:
- `TokenUtil` exists with `chunkText()`, `estimateTokens()` methods
- `maxTokens` in LightRAGConfig (4000 default)

**What to Add**:
- [X] Add config properties: `context.max-tokens`, `entity-budget-ratio`, `relation-budget-ratio`, `chunk-budget-ratio`
- [X] Validate ratios sum to 1.0
- [X] Add `TokenUtil.calculateBudget(totalTokens, ratio) -> int`
- [X] Add `TokenUtil.truncateToTokens(text, maxTokens) -> String`
- [X] Unit tests for budget calculation and truncation

**Dependencies**: T004

**Status**: COMPLETE

---

## Phase 4: Performance & Polish

### T016: Add connection health validation

**Files**: `src/main/java/br/edu/ifba/lightrag/storage/impl/AgeConfig.java`

**Requirements**: Reliability

**Description**: Validate database connections before use.

**What Already Exists**:
- `AgeConfig.getConnection()` returns connection without validation
- `@Retry` annotations on storage methods

**What to Add**:
- [X] In `getConnection()`, call `conn.isValid(5)` before returning
- [X] If invalid, close and throw `SQLTransientConnectionException`
- [X] Transient exception triggers existing retry logic
- [X] Log validation failures at WARN level
- [X] Unit test: valid connection returned
- [X] Unit test: invalid connection throws transient exception

**Dependencies**: None

**Status**: COMPLETE

---

### T017: Integration test - Gleaning effectiveness

**Files**: `src/test/java/br/edu/ifba/lightrag/core/GleaningExtractionIT.java` (NEW)

**Requirements**: FR-002, SC-001

**Description**: Verify gleaning captures additional entities.

**Acceptance Criteria**:
- [X] Test document with implicit entities not obvious on first pass
- [X] Compare entity count with gleaning enabled vs disabled
- [X] Verify cached extraction results for both passes
- [X] Success: gleaning captures at least 1 additional entity

**Dependencies**: T006

**Status**: COMPLETE (implemented as GleaningExtractionTest.java - 9 tests passing)

---

### T018: Integration test - Query mode correctness

**Files**: `src/test/java/br/edu/ifba/lightrag/query/QueryModeEnhancementsIT.java` (NEW)

**Requirements**: FR-011, FR-013

**Description**: Verify enhanced query modes with keyword extraction.

**Acceptance Criteria**:
- [X] Local mode uses low-level keywords when enabled
- [X] Global mode uses high-level keywords when enabled
- [X] Hybrid mode interleaves results
- [X] Graceful fallback when keyword extraction disabled/fails

**Dependencies**: T012, T013, T014

**Status**: COMPLETE (implemented as QueryModeEnhancementsTest.java - 18 tests passing)

---

### T019: Update AGENTS.md with new configuration

**Files**: `AGENTS.md`

**Requirements**: Documentation

**Description**: Document new configuration options.

**Status**: COMPLETE

**Acceptance Criteria**:
- [X] Add "LightRAG Official Alignment" section (added as "LightRAG Extraction & Query Configuration")
- [X] Document gleaning configuration
- [X] Document description summarization configuration
- [X] Document keyword extraction configuration
- [X] Document token budget configuration
- [X] Add troubleshooting guide

**Dependencies**: All implementation tasks

---

## Task Dependencies Graph

```
T001 ─────────────────────────────────────────────> T009
T002 ─────────────────────────────────────────────> T009
T003 ────> T005, T006, T010
T004 ────> T005, T006, T007, T010, T012, T013, T015
T005 (standalone - integrates with storeKnowledgeGraph)
T006 ────> T017
T007 (standalone)
T008 (standalone)
T009 (requires T001, T002)
T010 ────> T012, T013
T011 ────> T014
T012 ────> T014, T018
T013 ────> T014, T018
T014 ────> T018
T015 (standalone)
T016 (standalone)
T017-T019 ────> documentation
```

## Recommended Implementation Order

**Week 1: Foundation + Extraction**
1. T001, T002 (parallel) - Add sourceChunkIds to Entity/Relation
2. T003 - ExtractionCache infrastructure
3. T004 - LightRAGExtractionConfig
4. T007, T008 (parallel) - Name normalization, self-loop filter
5. T009 - Persist sourceChunkIds to graph

**Week 2: Extraction Pipeline**
1. T005 - DescriptionSummarizer
2. T006 - Gleaning implementation
3. T017 - Gleaning integration test

**Week 3: Query Pipeline**
1. T010 - KeywordExtractor
2. T011 - ContextMerger
3. T015 - Token budget utils
4. T012, T013 (parallel) - Local/Global keyword integration
5. T014 - HybridQueryExecutor enhancement
6. T018 - Query mode tests

**Week 4: Polish**
1. T016 - Connection health validation
2. T019 - Documentation update

---

## Removed Tasks (Already Implemented)

The following tasks from the original plan were **removed** because they already exist:

| Original Task | Reason for Removal |
|---------------|-------------------|
| T010: Tuple-delimiter format | Already in `LightRAG.java:773-891` |
| T012: Self-loop prevention in Relation | Already has `getNormalizedPair()` |
| Entity/Relation batch storage | Already has `upsertEntities()`, `upsertRelations()` |
| 6 query mode routing | Already in `LightRAG.query()` switch statement |
| GraphStorage batch interface | Already has full batch methods |
| Semantic deduplication | Full `EntityResolver` implementation exists |
