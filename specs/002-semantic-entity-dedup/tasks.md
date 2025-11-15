# Tasks: Semantic Entity Deduplication

**Input**: Design documents from `/specs/002-semantic-entity-dedup/`  
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅

**Tests**: Tests are included as this project follows TDD principles per the constitution.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Java package**: `src/main/java/br/edu/ifba/lightrag/core/`
- **Test package**: `src/test/java/br/edu/ifba/lightrag/`
- **Resources**: `src/main/resources/`
- All paths are relative to repository root

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and configuration for entity resolution feature

- [x] T001 Create feature branch `002-semantic-entity-dedup` from main
- [x] T002 [P] Add entity resolution configuration schema to src/main/resources/application.properties
- [x] T003 [P] Create test data files from dupes.md examples in test-data/entity-resolution/

**Checkpoint**: ✅ Branch created, configuration schema defined, test data ready

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core data structures and interfaces that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 [P] Create EntitySimilarityScore record in src/main/java/br/edu/ifba/lightrag/core/EntitySimilarityScore.java
- [x] T005 [P] Create EntityCluster record in src/main/java/br/edu/ifba/lightrag/core/EntityCluster.java
- [x] T006 [P] Create EntityResolutionResult record in src/main/java/br/edu/ifba/lightrag/core/EntityResolutionResult.java
- [x] T007 [P] Create DeduplicationConfig interface in src/main/java/br/edu/ifba/lightrag/core/DeduplicationConfig.java
- [x] T008 Create EntitySimilarityCalculator service skeleton in src/main/java/br/edu/ifba/lightrag/core/EntitySimilarityCalculator.java
- [x] T009 Create EntityClusterer service skeleton in src/main/java/br/edu/ifba/lightrag/core/EntityClusterer.java
- [x] T010 Create EntityResolver service skeleton in src/main/java/br/edu/ifba/lightrag/core/EntityResolver.java
- [x] T011 Add configuration validation logic to DeduplicationConfig interface

**Checkpoint**: ✅ Foundation ready - all core interfaces and data structures exist, user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Basic Semantic Matching (Priority: P1) 🎯 MVP

**Goal**: Enable detection and merging of entities with name variations (e.g., "OpenAI", "Open AI", "openai") using multi-metric string similarity

**Independent Test**: Index two documents with entity name variations (e.g., "Apple Inc." and "Apple"), verify only one entity node exists in graph with merged descriptions

**Success Criteria** (from spec.md SC-001, SC-004):
- 40-60% fewer duplicate entity nodes
- <2x processing time overhead

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T012 [P] [US1] Create EntitySimilarityCalculatorTest in src/test/java/br/edu/ifba/lightrag/core/EntitySimilarityCalculatorTest.java with test cases for all similarity metrics (48 tests created, all failing as expected)
- [x] T013 [P] [US1] Create EntityClustererTest in src/test/java/br/edu/ifba/lightrag/core/EntityClustererTest.java with clustering algorithm tests (31 tests created)
- [x] T014 [P] [US1] Create EntityResolverTest in src/test/java/br/edu/ifba/lightrag/core/EntityResolverTest.java with end-to-end resolution tests (24 tests, all passing)
- [x] T015 [P] [US1] Create EntityDeduplicationIT integration test in src/test/java/br/edu/ifba/lightrag/EntityDeduplicationIT.java (13 tests created, all passing)

### Implementation for User Story 1

#### String Similarity Metrics (can parallelize)

- [x] T016 [P] [US1] Implement normalizeName method in EntitySimilarityCalculator for lowercase/trim/punctuation removal
- [x] T017 [P] [US1] Implement tokenize method in EntitySimilarityCalculator for word splitting
- [x] T018 [P] [US1] Implement computeJaccardSimilarity method in EntitySimilarityCalculator using token overlap formula
- [x] T019 [P] [US1] Implement computeContainmentScore method in EntitySimilarityCalculator for substring matching
- [x] T020 [P] [US1] Implement computeLevenshteinSimilarity method in EntitySimilarityCalculator with dynamic programming algorithm (reference: dupes.md lines 966-989)
- [x] T021 [P] [US1] Implement computeAbbreviationScore method in EntitySimilarityCalculator for acronym detection (reference: dupes.md lines 1007-1024)

#### Similarity Calculation Integration

- [x] T022 [US1] Implement computeNameSimilarity method in EntitySimilarityCalculator combining all metrics with weights (depends on T016-T021)
- [x] T023 [US1] Implement computeSimilarity method in EntitySimilarityCalculator for Entity objects, including type checking (depends on T022)
- [x] T024 [US1] Add configuration injection to EntitySimilarityCalculator for weights and thresholds

#### Clustering Algorithm

- [x] T025 [US1] Implement buildSimilarityMatrix method in EntityClusterer for pairwise comparisons (depends on T023)
- [x] T026 [US1] Implement clusterBySimilarity method in EntityClusterer using threshold-based connected components algorithm
- [x] T027 [US1] Implement mergeCluster method in EntityClusterer for selecting canonical entity and combining descriptions
- [x] T028 [US1] Add type-based blocking optimization to EntityResolver (groupEntitiesByType) for O(n²) → O(n×k) performance improvement

#### Entity Resolution Orchestration

- [x] T029 [US1] Implement resolveDuplicates method in EntityResolver to orchestrate similarity calculation and clustering (depends on T024, T027)
- [x] T030 [US1] Implement resolveDuplicatesWithStats method in EntityResolver to return EntityResolutionResult with metrics
- [x] T031 [US1] Add structured logging to EntityResolver for merge decisions (INFO level) and similarity scores (DEBUG level)
- [x] T032 [US1] Add error handling and fallback logic to EntityResolver (return unmodified entities on failure)

#### LightRAG Integration

- [x] T033 [US1] Inject EntityResolver and DeduplicationConfig into LightRAG class in src/main/java/br/edu/ifba/lightrag/core/LightRAG.java
- [x] T034 [US1] Modify storeKnowledgeGraph method in LightRAG.java line 925 to call entityResolver.resolveDuplicates before existing deduplication
- [x] T035 [US1] Add feature flag check for config.enabled() in LightRAG integration
- [x] T036 [US1] Add resolution statistics logging in LightRAG integration

**Checkpoint**: User Story 1 complete - basic semantic matching works independently, can detect "Apple Inc." vs "Apple" variations

---

## Phase 4: User Story 2 - Type-Aware Semantic Matching (Priority: P2)

**Goal**: Prevent false positive merges by ensuring entities with same name but different types remain separate (e.g., "Apple" company vs "apple" fruit)

**Independent Test**: Index documents mentioning "Apple Inc." (ORGANIZATION) and "apple" (FOOD), verify they remain separate entities in graph

**Success Criteria** (from spec.md SC-003):
- Zero false positive merges for entities with different types (100% precision)

### Tests for User Story 2

- [x] T037 [P] [US2] Add type-aware test cases to EntitySimilarityCalculatorTest for same name, different type scenarios
- [x] T038 [P] [US2] Add type constraint test cases to EntityResolverTest verifying no cross-type merges
- [x] T039 [P] [US2] Add type-aware integration test to EntityDeduplicationIT with "Apple" (ORGANIZATION) vs "apple" (FOOD)

### Implementation for User Story 2

- [x] T040 [US2] Add type comparison logic to computeNameSimilarity in EntitySimilarityCalculator (return 0.0 if types don't match) ✅ COMPLETE - Implemented at EntitySimilarityCalculator:111-113
- [x] T041 [US2] Update clusterBySimilarity in EntityClusterer to enforce type constraints during clustering ✅ COMPLETE - Type constraints enforced through similarity matrix (EntityClusterer:94)
- [~] T042 [US2] Add type mismatch logging to EntityResolver at WARN level ⚠️ NOT IMPLEMENTED - Type safety works via grouping by type, no explicit logging needed
- [~] T043 [US2] Verify EntitySimilarityScore.hasSameType() method is used in clustering decisions ⚠️ NOT USED - Type checking done via computeNameSimilarity instead

**Checkpoint**: User Story 2 complete - type-aware matching prevents false positives, "Mercury" (PLANET) vs "Mercury" (ELEMENT) remain separate

---

## Phase 5: User Story 3 - Description-Based Semantic Similarity (Priority: P3)

**Goal**: Merge entities with different names but semantically similar descriptions using cosine similarity on embeddings (e.g., "CEO of Microsoft" and "Satya Nadella")

**Independent Test**: Index documents with semantically similar entity descriptions, verify entities with high semantic similarity are merged even if names differ

**Success Criteria** (from spec.md):
- Merge entities when semantic similarity exceeds threshold (configurable)
- Handle entities where one has description and other doesn't

**⚠️ NOTE**: This is Phase 3 from dupes.md - OPTIONAL, only implement if Phase 2 doesn't achieve >85% deduplication rate

### Tests for User Story 3

- [ ] T044 [P] [US3] Add semantic similarity test cases to EntitySimilarityCalculatorTest using entity embeddings
- [ ] T045 [P] [US3] Add description-based integration test to EntityDeduplicationIT with "CEO of Microsoft" vs "Satya Nadella"

### Implementation for User Story 3

- [ ] T046 [US3] Add computeSemanticSimilarity method to EntitySimilarityCalculator using PgVectorStorage for embedding retrieval
- [ ] T047 [US3] Implement cosine similarity calculation in EntitySimilarityCalculator for vector embeddings
- [ ] T048 [US3] Update computeSimilarity in EntitySimilarityCalculator to combine string similarity with semantic similarity when config.semanticEnabled()=true
- [ ] T049 [US3] Add semantic similarity weight configuration to DeduplicationConfig (default 0.40)
- [ ] T050 [US3] Update EntityResolver to handle missing descriptions gracefully (skip semantic similarity if description is null)
- [ ] T051 [US3] Add semantic similarity logging to EntityResolver at DEBUG level

**Checkpoint**: User Story 3 complete (if needed) - description-based semantic matching works, "The Big Apple" merges with "New York City" based on context

---

## Phase 6: Performance Optimization

**Purpose**: Ensure <2x processing overhead constraint (SC-004)

- [ ] T052 [P] Implement parallel batch processing in EntityResolver using CompletableFuture for similarity matrix computation
- [ ] T053 [P] Add early termination heuristics to EntitySimilarityCalculator (length ratio check, first token check)
- [ ] T054 Add batch size configuration to DeduplicationConfig (default 200)
- [ ] T055 Add parallel processing configuration to DeduplicationConfig (enabled=true, threads=4)
- [ ] T056 Implement parallel similarity computation in EntityClusterer.buildSimilarityMatrix
- [ ] T057 Add performance metrics logging to EntityResolver (processing time, avg time per entity)

**Checkpoint**: Performance optimized - processing overhead <2x baseline per SC-004

---

## Phase 7: Configuration & Observability

**Purpose**: Make feature configurable and observable per spec.md SC-005

- [ ] T058 [P] Document all configuration properties in src/main/resources/application.properties with comments
- [ ] T059 [P] Create configuration presets in quickstart.md (Conservative, Aggressive, Academic, News)
- [ ] T060 Add startup configuration validation to DeduplicationConfig.validate() method
- [ ] T061 Add structured logging with JSON format and correlationId to all services
- [ ] T062 Add high deduplication rate warning (>60%) to EntityResolver
- [ ] T063 Add merge decision audit logging to EntityResolver with cluster details

**Checkpoint**: Configuration and observability complete - users can tune thresholds without code changes

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Finalize feature for production readiness

- [ ] T064 [P] Add Javadoc to all public methods in EntityResolver, EntitySimilarityCalculator, EntityClusterer
- [ ] T065 [P] Create test execution script from quickstart.md (test-entity-resolution.sh)
- [ ] T066 [P] Validate all test cases from dupes.md Appendix A work correctly
- [ ] T067 Run full test suite and ensure >80% code coverage per constitution
- [ ] T068 Run integration test with actual "Flores para Algernon" document
- [ ] T069 Verify project isolation works correctly (entities deduplicated within project only)
- [ ] T070 Performance benchmark with 50, 100, 200, 500 entity batches
- [ ] T071 Validate quickstart.md configuration examples work
- [ ] T072 Code cleanup and refactoring for code quality standards
- [ ] T073 Update AGENTS.md with entity resolution context
- [ ] T074 Create FEATURE-COMPLETE.md milestone document in specs/002-semantic-entity-dedup/

**Checkpoint**: Feature complete and production-ready

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational completion - MVP delivery
- **User Story 2 (Phase 4)**: Depends on User Story 1 completion - builds on string similarity
- **User Story 3 (Phase 5)**: OPTIONAL - only if Phase 4 <85% deduplication rate
- **Performance (Phase 6)**: Depends on User Story 1 (minimum) or User Story 2 (recommended)
- **Configuration (Phase 7)**: Can start after User Story 1 implementation tasks complete
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Foundation only - no dependencies on other stories ✅ MVP
- **User Story 2 (P2)**: Depends on User Story 1 (extends EntitySimilarityCalculator) ⚠️
- **User Story 3 (P3)**: Depends on User Story 1 and 2 (extends similarity calculation) ⚠️ OPTIONAL

### Within Each User Story

- Tests (T012-T015) MUST be written and FAIL before implementation starts
- Similarity metrics (T016-T021) can run in parallel - different methods
- Clustering (T025-T028) depends on similarity calculation (T023-T024)
- Resolution orchestration (T029-T032) depends on clustering (T027)
- LightRAG integration (T033-T036) depends on resolution orchestration (T029-T030)

### Parallel Opportunities

**Phase 1 (Setup)**:
- T002 and T003 can run in parallel

**Phase 2 (Foundational)**:
- T004, T005, T006, T007 can all run in parallel (different record files)
- T008, T009, T010 should run sequentially after records exist

**Phase 3 (User Story 1)**:
- Tests T012, T013, T014, T015 can run in parallel (different test files)
- Similarity metrics T016-T021 can run in parallel (different methods in same file, but separate concerns)
- T052, T053 (performance) can run in parallel with T058, T059 (configuration docs)

**Phase 8 (Polish)**:
- T064, T065, T066 can run in parallel (different files)

---

## Parallel Example: User Story 1 Core Implementation

```bash
# Launch all test files together:
Task T012: "Create EntitySimilarityCalculatorTest.java"
Task T013: "Create EntityClustererTest.java"  
Task T014: "Create EntityResolverTest.java"
Task T015: "Create EntityDeduplicationIT.java"

# Then launch all similarity metric implementations (different methods):
Task T016: "Implement normalizeName method"
Task T017: "Implement tokenize method"
Task T018: "Implement computeJaccardSimilarity method"
Task T019: "Implement computeLevenshteinSimilarity method"
Task T020: "Implement computeContainmentScore method"
Task T021: "Implement computeAbbreviationScore method"

# After similarity metrics complete, launch clustering tasks:
Task T025: "Implement buildSimilarityMatrix method"
Task T026: "Implement clusterBySimilarity method"
Task T027: "Implement mergeCluster method"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only) - RECOMMENDED

1. Complete Phase 1: Setup (3 tasks, ~30 min)
2. Complete Phase 2: Foundational (8 tasks, ~2 hours)
3. Complete Phase 3: User Story 1 (25 tasks, ~1.5 weeks)
4. **STOP and VALIDATE**: Test User Story 1 independently with dupes.md test cases
5. Measure deduplication rate and performance
6. **Decision Point**: If >85% deduplication achieved, skip User Story 3

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready (~2.5 hours)
2. Add User Story 1 → Test independently → **Deploy/Demo MVP** (~1.5 weeks)
   - Expected: 40-60% deduplication, handles "Warren Home" variations
3. Add User Story 2 → Test independently → Deploy/Demo (~3 days)
   - Expected: Zero false positives, "Apple" (company) vs "apple" (fruit) stay separate
4. **Evaluate**: If deduplication rate >85%, STOP. Otherwise proceed to User Story 3.
5. Add User Story 3 (if needed) → Test independently → Deploy/Demo (~1 week)
   - Expected: Description-based semantic merging for advanced cases
6. Add Performance + Configuration (Phases 6-7) → (~1 week)
7. Polish (Phase 8) → (~3 days)

**Total Estimated Time**: 3-4 weeks (full implementation with US1, US2, US3)  
**MVP Time**: 1.5-2 weeks (Setup + Foundation + US1 only)

### Parallel Team Strategy

With 2 developers:

1. **Both**: Complete Setup + Foundational together (~2.5 hours)
2. **After Foundational**:
   - Developer A: User Story 1 tests + string similarity metrics (T012-T021)
   - Developer B: User Story 1 clustering + resolution (T025-T032)
3. **After US1 Complete**:
   - Developer A: User Story 2 (type-aware matching)
   - Developer B: Performance optimization (Phase 6)
4. Both: Configuration, observability, polish

---

## Test Data Requirements

### Required Test Cases (from dupes.md Appendix A)

1. **Exact Duplicates**: "Warren Home" × 2 → 1 entity
2. **Substring Variations**: "Warren State Home and Training School", "Warren Home", "Warren State Home" → 1 entity  
3. **Abbreviations**: "Massachusetts Institute of Technology", "MIT" → 1 entity
4. **Different Entities**: "Warren Home", "Warwick Home" → 2 entities (no merge)
5. **Type Mismatch**: "Apple Inc." (ORGANIZATION), "apple" (FOOD) → 2 entities (no merge)
6. **Person Names**: "James Gordon", "Jim Gordon", "Commissioner Gordon" → 1 entity

### Test Data Files (T003)

Create in `test-data/entity-resolution/`:
- `test-duplicates.txt` - MIT/Massachusetts Institute of Technology examples
- `test-types.txt` - Apple company vs apple fruit examples
- `test-warren-home.txt` - Warren Home variations from dupes.md
- `test-person-names.txt` - Character name variations

---

## Success Metrics Validation

Per spec.md Success Criteria:

- **SC-001**: 40-60% fewer duplicates → Validate with EntityDeduplicationIT (T015)
- **SC-002**: 25-40% query accuracy improvement → Measure with end-to-end chat queries (T068)
- **SC-003**: Zero false positives for different types → User Story 2 tests (T037-T039)
- **SC-004**: <2x processing overhead → Performance benchmarks (T070)
- **SC-005**: Configurable thresholds → Configuration validation (T071)

---

## Notes

- [P] tasks = different files or methods, no dependencies, can run in parallel
- [US1]/[US2]/[US3] labels map task to specific user story for traceability
- Each user story should be independently completable and testable
- Tests MUST be written first (TDD approach per constitution)
- Verify tests fail before implementing (RED → GREEN → REFACTOR)
- Commit after each logical group of tasks
- Stop at any checkpoint to validate story independently
- Phase 3 (User Story 3) is OPTIONAL - only implement if needed

---

## Quick Reference: Task Count Summary

- **Phase 1 (Setup)**: 3 tasks (~30 min)
- **Phase 2 (Foundational)**: 8 tasks (~2 hours)
- **Phase 3 (User Story 1 - MVP)**: 25 tasks (~1.5 weeks)
- **Phase 4 (User Story 2)**: 7 tasks (~3 days)
- **Phase 5 (User Story 3)**: 8 tasks (~1 week) - OPTIONAL
- **Phase 6 (Performance)**: 6 tasks (~3 days)
- **Phase 7 (Configuration)**: 6 tasks (~2 days)
- **Phase 8 (Polish)**: 11 tasks (~3 days)

**Total**: 74 tasks  
**MVP Scope**: 36 tasks (Phases 1-3)  
**Parallel Opportunities**: 25 tasks marked [P] (33%)
