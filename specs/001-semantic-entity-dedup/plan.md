# Implementation Plan: Semantic Entity Deduplication

**Branch**: `001-semantic-entity-dedup` | **Date**: 2025-11-15 | **Spec**: [spec.md](./spec.md)  
**Input**: Feature specification from `/specs/001-semantic-entity-dedup/spec.md`

## Summary

Implement a semantic entity deduplication system that reduces duplicate entity nodes in the knowledge graph by 40-60%. The system will use a multi-metric similarity approach combining string matching (Jaccard, Levenshtein, containment), type-aware comparison, and semantic embeddings. Implementation follows a phased approach: (1) Enhanced LLM extraction prompts for quick wins (30-50% improvement), (2) Post-extraction entity resolution pipeline for robust deduplication (70-90% improvement), and (3) Description-based semantic similarity for advanced cases. The solution must maintain <2x performance overhead and preserve project isolation.

## Technical Context

**Language/Version**: Java 21 (Quarkus 3.28.4)  
**Primary Dependencies**: Jakarta REST, Apache AGE (graph), pgvector (embeddings), EmbeddingFunction, LLMFunction  
**Storage**: PostgreSQL with Apache AGE extension (graph) + pgvector extension (embeddings)  
**Testing**: JUnit 5 + REST Assured for integration tests, Testcontainers for database tests  
**Target Platform**: Linux server (Quarkus JVM mode)  
**Project Type**: Single Java application (Quarkus backend)  
**Performance Goals**: <2x current entity processing time, P95 <500ms for entity resolution per batch  
**Constraints**: Max 2x performance overhead, must work with Apache AGE v1.5.0 limitations, preserve project isolation  
**Scale/Scope**: Process 100-entity batches efficiently, support configurable similarity thresholds, handle cross-batch deduplication

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Quality Gates

| Gate | Status | Notes |
|------|--------|-------|
| **Code Quality Standards** | ✅ PASS | Will use Jakarta EE, follow naming conventions (*Service, *Repository), implement proper error handling, add Javadoc |
| **Testing Standards** | ✅ PASS | Will write tests first (TDD), create integration tests with @QuarkusTest, unit tests with >80% coverage, mock LLM/embedding APIs |
| **API Consistency** | ✅ PASS | No new REST endpoints (internal service changes only), existing APIs remain unchanged |
| **Performance Requirements** | ⚠️ CONDITIONAL | Must verify <2x processing overhead (spec requirement SC-004), batch entity resolution to avoid memory spikes |
| **Observability** | ✅ PASS | Will add structured logging (JSON) with correlationId, log similarity scores and merge decisions at INFO level |

### Performance Standards Compliance

| Standard | Compliance | Implementation Plan |
|----------|------------|---------------------|
| **Database Queries** | ✅ PASS | Entity resolution operates in-memory; graph updates use batch upsertEntities() |
| **LLM API Calls** | ✅ PASS | Enhanced prompt approach requires no additional LLM calls; embeddings already generated for entities |
| **Resource Limits** | ✅ PASS | Process entities in batches (config.kgExtractionBatchSize), reuse existing embedding infrastructure |
| **Scalability** | ✅ PASS | Project isolation maintained via projectId filtering, no shared state |

### Justifications

**Performance (⚠️ CONDITIONAL)**: The spec allows max 2x processing overhead (SC-004). This is acceptable because:
- Entity resolution is O(n²) within entity type groups (not across all entities)
- Batch processing limits memory usage
- String similarity is fast (Levenshtein, Jaccard are O(n*m) for string lengths)
- Trade-off: 2x slower processing for 40-60% better graph quality

**Simpler Alternative Rejected**: Direct string matching (current implementation) only catches exact name matches. User feedback in dupes.md shows this creates 4 duplicate entities for "Warren Home" variations, severely impacting answer quality.

## Project Structure

### Documentation (this feature)

```text
specs/001-semantic-entity-dedup/
├── plan.md              # This file (/speckit.plan command output)
├── spec.md              # Feature specification (already created)
├── checklists/
│   └── requirements.md  # Specification quality checklist (already created)
├── research.md          # Phase 0 output (to be created below)
├── data-model.md        # Phase 1 output (to be created below)
├── quickstart.md        # Phase 1 output (to be created below)
└── contracts/           # Phase 1 output (to be created below)
    └── EntityResolver.interface.md
```

### Source Code (repository root)

```text
src/main/java/br/edu/ifba/
├── lightrag/
│   ├── core/
│   │   ├── Entity.java                    # [EXISTING] Entity model
│   │   ├── LightRAG.java                  # [MODIFY] Add entity resolution before storage
│   │   ├── EntityResolver.java            # [NEW] Main resolution logic
│   │   ├── EntitySimilarityCalculator.java # [NEW] Similarity metrics
│   │   └── EntityClusterer.java           # [NEW] Clustering algorithm
│   ├── storage/
│   │   ├── impl/
│   │   │   ├── AgeGraphStorage.java       # [EXISTING] Graph operations
│   │   │   └── PgVectorStorage.java       # [EXISTING] Vector operations
│   └── embedding/
│       └── EmbeddingFunction.java          # [EXISTING] Embedding interface

src/test/java/br/edu/ifba/
└── lightrag/
    ├── core/
    │   ├── EntityResolverTest.java         # [NEW] Unit tests for resolution
    │   ├── EntitySimilarityCalculatorTest.java # [NEW] Unit tests for similarity
    │   └── EntityClustererTest.java        # [NEW] Unit tests for clustering
    └── EntityDeduplicationIT.java          # [NEW] Integration test for full flow
```

**Structure Decision**: Single Java project (Quarkus backend). All entity resolution logic lives in `br.edu.ifba.lightrag.core` package alongside existing LightRAG components. No new REST endpoints needed - this is an internal processing enhancement.

## Complexity Tracking

> **No violations to justify** - Implementation aligns with constitution standards

---

## Phase 0: Outline & Research

### Research Tasks

Based on Technical Context unknowns and spec requirements, the following research is needed:

1. **String Similarity Algorithms**
   - **Question**: Which string similarity metrics are most effective for entity name matching?
   - **Scope**: Evaluate Levenshtein distance, Jaccard similarity (token overlap), substring containment, abbreviation matching
   - **Deliverable**: Recommended similarity metrics with performance characteristics

2. **Clustering Algorithms**
   - **Question**: What clustering approach should we use to group similar entities?
   - **Scope**: Compare threshold-based connected components, DBSCAN, hierarchical clustering
   - **Deliverable**: Selected clustering algorithm with rationale

3. **Embedding-Based Similarity**
   - **Question**: How should we leverage existing entity embeddings for semantic similarity?
   - **Scope**: Investigate cosine similarity on existing entity embeddings (already generated), integration with string-based metrics
   - **Deliverable**: Strategy for combining string similarity + semantic similarity

4. **Performance Optimization**
   - **Question**: How can we optimize O(n²) pairwise comparisons for large entity batches?
   - **Scope**: Research blocking strategies (group by type first), early termination heuristics, parallel processing
   - **Deliverable**: Performance optimization recommendations

5. **Configuration Strategy**
   - **Question**: What configuration parameters should be tunable?
   - **Scope**: Identify similarity thresholds, metric weights, batch sizes, enable/disable flags
   - **Deliverable**: Configuration schema for application.properties

6. **Apache AGE v1.5.0 Limitations**
   - **Question**: What are the implications of AGE v1.5.0 entity property update limitations?
   - **Scope**: Review dupes.md findings on AGE MERGE behavior, document workarounds
   - **Deliverable**: Strategy for entity merging within AGE constraints

### Research Output Location

**File**: `specs/001-semantic-entity-dedup/research.md`

All research findings will be consolidated in this file following the format:
- **Decision**: [What was chosen]
- **Rationale**: [Why chosen]
- **Alternatives Considered**: [What else was evaluated]

---

## Phase 1: Design & Contracts

### Artifacts to Generate

1. **Data Model** (`data-model.md`)
   - EntitySimilarityScore: Fields (entity1Name, entity2Name, score, breakdown by metric)
   - EntityCluster: Fields (canonicalEntity, aliases, mergedDescriptions)
   - DeduplicationConfig: Fields (thresholds, weights, enabled flags)

2. **API Contracts** (`contracts/EntityResolver.interface.md`)
   - `List<Entity> resolveDuplicates(List<Entity> entities, String projectId)`
   - `double computeSimilarity(String name1, String name2, String type1, String type2)`
   - `List<Set<Integer>> clusterBySimilarity(double[][] similarityMatrix, double threshold)`
   - `Entity mergeCluster(Set<Integer> clusterIndices, List<Entity> entities)`

3. **Quickstart** (`quickstart.md`)
   - Step-by-step guide for enabling entity resolution
   - Configuration examples for different similarity thresholds
   - Testing instructions with sample documents
   - Troubleshooting common issues (false positives/negatives)

### Agent Context Update

After generating design artifacts, run:
```bash
.specify/scripts/bash/update-agent-context.sh opencode
```

This will update `.specify/memory/opencode-context.md` with:
- New classes: `EntityResolver`, `EntitySimilarityCalculator`, `EntityClusterer`
- Modified classes: `LightRAG` (add resolution step before storage)
- New configuration properties in `application.properties`

---

## Phase 2: Task Breakdown (NOT created by /speckit.plan)

**Note**: Task breakdown is generated by `/speckit.tasks` command in a separate step.

The tasks will be organized by priority levels:
- **P1 Tasks**: Basic semantic matching (FR-001, FR-002, FR-003, FR-006, FR-007)
- **P2 Tasks**: Type-aware matching (FR-003, FR-008)
- **P3 Tasks**: Description-based semantic similarity (FR-004, FR-005, FR-011)

**Estimated Task Breakdown**:
1. Enhanced extraction prompt (Phase 1 from dupes.md) - 1-2 hours
2. String similarity calculator - 4-6 hours
3. Entity clustering algorithm - 4-6 hours
4. EntityResolver service - 6-8 hours
5. Integration with LightRAG - 2-4 hours
6. Configuration system - 2-3 hours
7. Unit tests - 6-8 hours
8. Integration tests - 4-6 hours
9. Performance benchmarking - 3-4 hours
10. Documentation - 2-3 hours

**Total Estimated Time**: 34-50 hours (1-1.5 weeks full-time)

---

## Implementation Strategy

### Phased Rollout (from dupes.md analysis)

#### Phase 1: Enhanced Prompt (Quick Win) ✅ READY
- **Goal**: 30-50% improvement with minimal code changes
- **Effort**: 1-2 hours
- **Risk**: Low
- **Approach**: Update `LIGHTRAG_ENTITY_EXTRACTION_SYSTEM_PROMPT` in application.properties to include entity canonicalization instructions

#### Phase 2: Entity Resolution Pipeline (Robust Solution) ✅ READY
- **Goal**: 70-90% improvement with comprehensive deduplication
- **Effort**: 1.5-2 weeks
- **Risk**: Medium (performance, false positives)
- **Approach**: Implement `EntityResolver` with multi-metric similarity and clustering

#### Phase 3: Semantic Embeddings (Advanced - Optional) ⏸️ FUTURE
- **Goal**: State-of-the-art accuracy for description-based similarity
- **Effort**: 3-4 days
- **Risk**: Low (leverages existing embeddings)
- **Approach**: Add cosine similarity on entity embeddings to similarity calculation
- **Decision Point**: Only implement if Phase 2 doesn't achieve >85% deduplication rate

### Quality Metrics

| Metric | Baseline | Phase 1 Target | Phase 2 Target | Phase 3 Target |
|--------|----------|----------------|----------------|----------------|
| **Entity Duplication Rate** | 40% | 25% | 10% | 5% |
| **Processing Latency** | 100ms | 105ms (+5%) | 120ms (+20%) | 150ms (+50%) |
| **False Merge Rate** | 0% | <2% | <1% | <0.5% |

### Risk Mitigation

1. **Risk: False Positive Merges**
   - Mitigation: Start with conservative threshold (0.80+), add type-based constraints, implement feature flag for rollback

2. **Risk: Performance Degradation**
   - Mitigation: Process entities in batches, implement parallel processing, add caching for similarity computations

3. **Risk: Domain-Specific Challenges**
   - Mitigation: Make thresholds configurable per project, collect domain-specific test datasets

---

## Success Criteria Mapping

| Success Criterion | Implementation Verification |
|-------------------|----------------------------|
| **SC-001**: 40-60% fewer duplicates | Integration test comparing entity count before/after resolution |
| **SC-002**: 25-40% query accuracy improvement | End-to-end test measuring precision/recall on entity queries |
| **SC-003**: Zero false positive merges | Unit tests for type-aware matching, manual validation of test corpus |
| **SC-004**: <2x processing time | Performance benchmark comparing processing time with/without resolution |
| **SC-005**: Configurable thresholds | Configuration test verifying threshold changes affect merge behavior |

---

## Next Steps

1. ✅ **Complete**: Specification created (`spec.md`)
2. ✅ **Complete**: Implementation plan created (`plan.md`)
3. ⏭️ **Next**: Execute Phase 0 research (generate `research.md`)
4. ⏭️ **Next**: Execute Phase 1 design (generate `data-model.md`, `contracts/`, `quickstart.md`)
5. ⏭️ **Next**: Run `/speckit.tasks` to generate task breakdown
6. ⏭️ **Next**: Begin implementation following TDD principles
