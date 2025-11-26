# Implementation Plan: LightRAG Official Implementation Alignment

**Branch**: `006-lightrag-official-impl` | **Date**: 2025-11-25 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/006-lightrag-official-impl/spec.md`

## Summary

Align the Java LightRAG implementation with the official Python implementation from HKUDS/LightRAG. Key improvements include:
1. **Improved extraction quality** via iterative gleaning and LLM-based description summarization
2. **Enhanced query pipeline** with proper keyword extraction and round-robin context merging
3. **Source tracking** with chunk-level provenance for entities/relations
4. **Caching infrastructure** for extraction results enabling knowledge graph rebuild on document deletion

## Technical Context

**Language/Version**: Java 21 + Quarkus 3.28.4  
**Primary Dependencies**: 
- SmallRye Fault Tolerance (retry/circuit breaker)
- Apache AGE (graph storage)
- pgvector (vector similarity)
- Jakarta REST (API layer)

**Storage**: PostgreSQL 14+ with Apache AGE and pgvector extensions  
**Testing**: JUnit 5 + REST Assured + Testcontainers  
**Target Platform**: Linux server (containerized), compatible with ARM64/x86_64  
**Project Type**: Single backend service (Quarkus)

**Performance Goals** (from Constitution):
- Document ingestion: <30s for 100-page PDF
- Vector search: P95 <200ms
- Graph queries: P95 <500ms
- Chat API: first token <1s

**Constraints**:
- Max chunk size: 2000 tokens
- Max entities per chunk: 20
- Query timeout: 5s hard limit
- LLM timeout: 30s generation, 10s embeddings

**Scale/Scope**:
- Support 100 concurrent document uploads
- Documents up to 100,000 tokens (from spec SC-004)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Code Quality | ✅ PASS | Jakarta EE, existing naming conventions apply |
| II. Testing Standards | ✅ PASS | TDD approach, integration tests with Testcontainers |
| III. API Consistency | ✅ PASS | Existing Chat API patterns extend naturally |
| IV. Performance Requirements | ✅ PASS | Token limits, batch processing already implemented |
| V. Observability | ✅ PASS | Structured logging with MDC, metrics tracking |
| Quality Gates | ✅ PASS | >80% branch coverage target, existing CI pipeline |

**Pre-Phase 0 Gate Status**: ✅ PASSED - No constitution violations identified

## Project Structure

### Documentation (this feature)

```text
specs/006-lightrag-official-impl/
├── plan.md              # This file
├── research.md          # Phase 0: Technical research and decisions
├── data-model.md        # Phase 1: Entity/cache models
├── quickstart.md        # Phase 1: Developer quickstart guide
├── contracts/           # Phase 1: API contracts (if any new endpoints)
└── tasks.md             # Phase 2: Implementation tasks (created by /speckit.tasks)
```

### Source Code (repository root)

```text
src/
├── main/java/br/edu/ifba/
│   ├── lightrag/
│   │   ├── core/
│   │   │   ├── LightRAG.java                    # MODIFY: Add gleaning, description merging
│   │   │   ├── LightRAGConfig.java              # MODIFY: Add gleaning/caching config
│   │   │   ├── Entity.java                      # MODIFY: Add sourceIds, filePath fields
│   │   │   ├── Relation.java                    # MODIFY: Add sourceIds, filePath fields
│   │   │   ├── Chunk.java                       # MODIFY: Add llmCacheList field
│   │   │   ├── ExtractionResult.java            # NEW: Cache entry for LLM results
│   │   │   ├── KeywordExtractor.java            # NEW: High/low-level keyword extraction
│   │   │   └── DescriptionSummarizer.java       # NEW: LLM-based description merging
│   │   ├── query/
│   │   │   ├── LocalQueryExecutor.java          # MODIFY: Use keyword extraction
│   │   │   ├── GlobalQueryExecutor.java         # MODIFY: Use keyword extraction
│   │   │   ├── HybridQueryExecutor.java         # MODIFY: Round-robin merging
│   │   │   ├── MixQueryExecutor.java            # MODIFY: KG + vector context
│   │   │   └── QueryContext.java                # NEW: Token-managed context builder
│   │   ├── storage/
│   │   │   ├── GraphStorage.java                # MODIFY: Add batch methods
│   │   │   ├── impl/
│   │   │   │   ├── AgeGraphStorage.java         # MODIFY: Batch ops, native SQL stats
│   │   │   │   └── AgeConfig.java               # MODIFY: Connection validation
│   │   │   └── LLMCacheStorage.java             # NEW: Extraction result cache
│   │   └── utils/
│   │       ├── TokenUtil.java                   # MODIFY: Add budget calculation
│   │       └── TransientSQLExceptionPredicate.java # MODIFY: Extended patterns
│   └── document/
│       └── Document.java                        # MODIFY: Add chunk tracking
└── test/java/br/edu/ifba/
    └── lightrag/
        ├── core/
        │   ├── GleaningExtractionIT.java        # NEW: Iterative extraction tests
        │   ├── DescriptionSummarizerTest.java   # NEW: LLM summarization tests
        │   └── KeywordExtractorTest.java        # NEW: Keyword extraction tests
        ├── query/
        │   └── RoundRobinMergingTest.java       # NEW: Context merging tests
        └── storage/
            ├── BatchOperationsIT.java           # NEW: Batch graph ops tests
            └── LLMCacheStorageIT.java           # NEW: Cache persistence tests
```

**Structure Decision**: Single backend project (existing Quarkus structure). All changes are modifications to existing modules or new classes within the `lightrag` package. No new Maven modules required.

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
|----------|------------|-------------------------------------|
| KeywordExtractor class | FR-012 requires LLM-based keyword extraction for query routing | Regex/heuristic extraction produces poor quality keywords for semantic queries |
| DescriptionSummarizer class | FR-009/FR-010 require LLM summarization when descriptions exceed threshold | Simple concatenation (current impl) produces incoherent long descriptions |
| LLMCacheStorage | FR-021/FR-022 require cached extraction for rebuild without re-calling LLM | Without cache, document deletion cannot rebuild shared entities |
| ExtractionResult record | Structured cache entry for extraction results | Raw string storage loses metadata needed for rebuild |

## Phase Dependencies

```
Phase 0: Research
    └── research.md (technical decisions)
    
Phase 1: Design
    ├── data-model.md (Entity, Relation, Chunk, ExtractionResult changes)
    ├── contracts/ (any new REST endpoints - likely none for this feature)
    └── quickstart.md (developer setup)
    
Phase 2: Tasks (via /speckit.tasks)
    └── tasks.md (implementation breakdown)
```

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Gleaning increases LLM costs | High | Medium | Make gleaning passes configurable (default 1) |
| Description summarization latency | Medium | Medium | Async processing, threshold-based trigger |
| Cache storage growth | Medium | Low | TTL-based cleanup, configurable retention |
| Breaking existing document ingestion | Low | High | Feature flags for new behavior |

## Next Steps

1. **Phase 0**: Generate `research.md` - research gleaning implementation, description summarization patterns, keyword extraction prompts
2. **Phase 1**: Generate `data-model.md`, `contracts/`, `quickstart.md`
3. **Phase 2**: Run `/speckit.tasks` to generate implementation tasks
