# Implementation Plan: LightRAG Official Implementation Enhancements

**Branch**: `007-lightrag-enhancements` | **Date**: 2025-11-26 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/007-lightrag-enhancements/spec.md`

## Summary

Implement enhancements from the official HKUDS/LightRAG implementation to improve the Java RAG system. Key features include:

1. **Reranker Integration** - Add reranking capability for retrieved chunks using external reranker APIs (Cohere, Jina)
2. **Document Deletion with KG Regeneration** - Intelligent entity/relation rebuild when documents are deleted
3. **Entity Merging** - Manual consolidation of duplicate entities with relationship redirection
4. **Token Usage Tracking** - Monitor LLM token consumption for cost management
5. **Knowledge Graph Export** - Export entities/relations to CSV, Excel, Markdown formats

Additionally, implement key improvements from the user's analysis:
- **LLM-Based Description Summarization** - Map-reduce pattern for description merging (replaces simple concatenation)
- **Chunk Selection Methods** - Add WEIGHT method (weighted polling) alongside VECTOR similarity
- **LLM Response Caching** - Enable document deletion + rebuild workflow

## Technical Context

**Language/Version**: Java 21 + Quarkus 3.28.4  
**Primary Dependencies**: 
- SmallRye Fault Tolerance (retry/circuit breaker)
- Apache AGE (graph storage)
- pgvector (vector similarity)
- Jakarta REST (API layer)
- Apache POI (Excel export) - NEW
- MicroProfile REST Client (reranker APIs) - NEW

**Storage**: PostgreSQL 14+ with Apache AGE and pgvector extensions  
**Testing**: JUnit 5 + REST Assured + Testcontainers  
**Target Platform**: Linux server (containerized), compatible with ARM64/x86_64  
**Project Type**: Single backend service (Quarkus)

**Performance Goals** (from Constitution):
- Document ingestion: <30s for 100-page PDF
- Vector search: P95 <200ms
- Graph queries: P95 <500ms
- Chat API: first token <1s
- Reranker fallback: <2s (from spec SC-006)

**Constraints**:
- Max chunk size: 2000 tokens
- Max entities per chunk: 20
- Query timeout: 5s hard limit
- LLM timeout: 30s generation, 10s embeddings
- Export: up to 50,000 entities without memory issues (SC-005)

**Scale/Scope**:
- Support 100 concurrent document uploads
- KG regeneration for entities shared across 100+ documents

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Code Quality | ✅ PASS | Jakarta EE, existing naming conventions apply |
| II. Testing Standards | ✅ PASS | TDD approach, integration tests for reranker/export |
| III. API Consistency | ✅ PASS | New endpoints follow existing patterns |
| IV. Performance Requirements | ✅ PASS | Streaming export, fallback timeouts defined |
| V. Observability | ✅ PASS | Token tracking adds observability for LLM costs |
| Quality Gates | ✅ PASS | >80% branch coverage target |

**Pre-Phase 0 Gate Status**: ✅ PASSED - No constitution violations identified

## Project Structure

### Documentation (this feature)

```text
specs/007-lightrag-enhancements/
├── plan.md              # This file
├── research.md          # Phase 0: Technical research and decisions
├── data-model.md        # Phase 1: Entity/model additions
├── quickstart.md        # Phase 1: Developer quickstart guide
├── contracts/           # Phase 1: API contracts for new endpoints
└── tasks.md             # Phase 2: Implementation tasks (created by /speckit.tasks)
```

### Source Code (repository root)

```text
src/
├── main/java/br/edu/ifba/
│   ├── lightrag/
│   │   ├── core/
│   │   │   ├── LightRAG.java                      # MODIFY: Add delete/merge/export methods
│   │   │   ├── Entity.java                        # MODIFY: Add merge helper methods
│   │   │   ├── Relation.java                      # MODIFY: Add merge helper methods
│   │   │   ├── DescriptionSummarizer.java         # MODIFY: Add map-reduce implementation
│   │   │   ├── TokenUsage.java                    # NEW: Token consumption tracking model
│   │   │   ├── TokenTracker.java                  # NEW: Session-level token aggregator
│   │   │   └── MergeStrategy.java                 # NEW: Enum for merge strategies
│   │   ├── query/
│   │   │   ├── MixQueryExecutor.java              # MODIFY: Add reranking integration
│   │   │   ├── ChunkSelector.java                 # NEW: Interface for chunk selection
│   │   │   ├── VectorChunkSelector.java           # NEW: Vector similarity implementation
│   │   │   ├── WeightedChunkSelector.java         # NEW: Weighted polling implementation
│   │   │   └── RerankerService.java               # NEW: Reranker integration service
│   │   ├── rerank/
│   │   │   ├── Reranker.java                      # NEW: Reranker interface
│   │   │   ├── RerankerConfig.java                # NEW: Configuration for rerankers
│   │   │   ├── CohereReranker.java                # NEW: Cohere API implementation
│   │   │   ├── JinaReranker.java                  # NEW: Jina API implementation
│   │   │   └── RerankedChunk.java                 # NEW: Chunk with relevance score
│   │   ├── deletion/
│   │   │   ├── DocumentDeletionService.java       # NEW: Orchestrates deletion + rebuild
│   │   │   ├── EntityRebuildStrategy.java         # NEW: Rebuild vs delete decision
│   │   │   └── KnowledgeRebuildResult.java        # NEW: Result of rebuild operation
│   │   ├── merge/
│   │   │   ├── EntityMergeService.java            # NEW: Entity merge orchestration
│   │   │   ├── RelationshipRedirector.java        # NEW: Redirects relations after merge
│   │   │   └── MergeResult.java                   # NEW: Result of merge operation
│   │   ├── export/
│   │   │   ├── GraphExporter.java                 # NEW: Interface for export formats
│   │   │   ├── CsvGraphExporter.java              # NEW: CSV export implementation
│   │   │   ├── ExcelGraphExporter.java            # NEW: Excel export implementation
│   │   │   ├── MarkdownGraphExporter.java         # NEW: Markdown export implementation
│   │   │   └── ExportConfig.java                  # NEW: Export configuration options
│   │   └── storage/
│   │       ├── GraphStorage.java                  # MODIFY: Add delete/merge batch operations
│   │       └── impl/
│   │           ├── AgeGraphStorage.java           # MODIFY: Implement new batch methods
│   │           └── PgVectorStorage.java           # MODIFY: Add batch delete for embeddings
│   └── document/
│       └── DocumentResources.java                 # MODIFY: Add delete endpoint
└── test/java/br/edu/ifba/
    └── lightrag/
        ├── rerank/
        │   ├── RerankerServiceTest.java           # NEW: Reranker unit tests
        │   ├── CohereRerankerIT.java              # NEW: Cohere integration tests
        │   └── RerankerFallbackIT.java            # NEW: Fallback behavior tests
        ├── deletion/
        │   ├── DocumentDeletionIT.java            # NEW: Deletion + rebuild tests
        │   └── EntityRebuildStrategyTest.java     # NEW: Rebuild logic tests
        ├── merge/
        │   ├── EntityMergeServiceIT.java          # NEW: Merge integration tests
        │   └── RelationshipRedirectorTest.java    # NEW: Redirection logic tests
        ├── export/
        │   ├── CsvGraphExporterTest.java          # NEW: CSV export tests
        │   ├── ExcelGraphExporterTest.java        # NEW: Excel export tests
        │   └── LargeGraphExportIT.java            # NEW: Performance/memory tests
        └── core/
            └── TokenTrackerTest.java              # NEW: Token tracking tests
```

**Structure Decision**: Single backend project (existing Quarkus structure). New packages created for logical separation: `rerank/`, `deletion/`, `merge/`, `export/`. All changes build on existing `lightrag` module.

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
|----------|------------|-------------------------------------|
| RerankerService abstraction | FR-002 requires multiple providers (Cohere, Jina) | Single provider would require code changes to switch |
| EntityMergeService | FR-012-017 require complex merge logic with relationship handling | Direct DB operations would duplicate logic, harder to test |
| DocumentDeletionService | FR-006-011 require coordinated cleanup across graph/vector stores | Transaction-only approach cannot handle partial rebuilds |
| GraphExporter interface | FR-025 requires multiple formats (CSV, Excel, MD) | Single format would not meet user story requirements |

## Phase Dependencies

```
Phase 0: Research
    └── research.md (technical decisions for reranker APIs, export libraries)
    
Phase 1: Design
    ├── data-model.md (TokenUsage, MergeStrategy, ExportConfig)
    ├── contracts/ (DELETE /documents/{id}, POST /entities/merge, GET /export)
    └── quickstart.md (developer setup for reranker keys)
    
Phase 2: Tasks (via /speckit.tasks)
    └── tasks.md (implementation breakdown)
```

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Reranker API rate limits | Medium | Medium | Configurable fallback, circuit breaker |
| Large graph export OOM | Medium | Medium | Streaming export, batch fetching |
| Merge creates orphaned relations | Low | High | Transaction with rollback, comprehensive tests |
| Token tracking accuracy | Low | Low | Compare against API-reported usage in tests |
| Description summarization cost | Medium | Medium | Configurable thresholds, caching |

## Next Steps

1. **Phase 0**: Generate `research.md` - research reranker API contracts, export libraries, map-reduce summarization patterns
2. **Phase 1**: Generate `data-model.md`, `contracts/`, `quickstart.md`
3. **Phase 2**: Run `/speckit.tasks` to generate implementation tasks
