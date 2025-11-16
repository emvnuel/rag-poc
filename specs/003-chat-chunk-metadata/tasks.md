# Tasks: Chat Response Chunk Metadata Enhancement

**Input**: Design documents from `/specs/003-chat-chunk-metadata/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ChatAPI.yaml, quickstart.md

**Tests**: Integration tests are REQUIRED to verify JSON serialization of existing fields

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

- **Java project structure**: `src/main/java/br/edu/ifba/`, `src/test/java/br/edu/ifba/`
- **Resources**: `src/main/resources/`, `src/test/resources/`
- **Contracts**: `specs/003-chat-chunk-metadata/contracts/`
- Paths follow Quarkus Maven project structure

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify project structure and existing implementation

- [X] T001 Review existing SearchResult record in src/main/java/br/edu/ifba/document/SearchResult.java to confirm id and chunkIndex fields exist
- [X] T002 Review existing ChatResponse record in src/main/java/br/edu/ifba/chat/ChatResponse.java to confirm sources field structure
- [X] T003 Review existing ChatService implementation in src/main/java/br/edu/ifba/chat/ChatService.java to confirm SearchResult objects are passed to ChatResponse

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core verification that MUST be complete before implementing user stories

**⚠️ CRITICAL**: These foundational checks confirm the feature likely already works

- [X] T004 Verify Jackson JSON serialization configuration in pom.xml includes record support (Quarkus 3.28.4 default)
- [X] T005 Confirm no custom JsonIgnore or JsonInclude annotations on SearchResult that would hide id or chunkIndex fields
- [X] T006 Review null handling in SearchResult creation to ensure synthesized answers properly set id=null and chunkIndex=null

**Checkpoint**: Foundation verified - feature implementation can now begin

---

## Phase 3: User Story 1 - Source Traceability for Chat Responses (Priority: P1) 🎯 MVP

**Goal**: Enable users to trace AI responses back to specific document chunks by including documentId (id) and chunkIndex in the chat response sources array

**Independent Test**: Send a chat request with documents in the project, verify the response includes sources array with non-null id and chunkIndex for document-based sources

**Acceptance Scenarios**:
1. Chat request returns sources with id and chunkIndex populated
2. Multiple chunks from same document have same id, different chunkIndex
3. Empty sources array when no matches

### Tests for User Story 1 (REQUIRED)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation (or PASS if feature already works)**

- [X] T007 [P] [US1] Create ChatResponseMetadataIT integration test class in src/test/java/br/edu/ifba/chat/ChatResponseMetadataIT.java
- [X] T008 [P] [US1] Write test method testChatResponseIncludesDocumentIdAndChunkIndex that verifies sources[].id and sources[].chunkIndex are present and non-null for document sources
- [X] T009 [P] [US1] Write test method testChatResponseWithMultipleChunksFromSameDocument that verifies same documentId with different chunkIndex values
- [X] T010 [P] [US1] Write test method testChatResponseWithEmptySources that verifies sources array is empty (not null) when no documents match

### Implementation for User Story 1

**Expected Result**: Tests likely PASS immediately (feature already works). If tests FAIL, implementation needed:

- [ ] T011 [US1] IF TESTS FAIL: Verify SearchResult record fields are public and have accessor methods (records auto-generate these)
- [ ] T012 [US1] IF TESTS FAIL: Check for @JsonIgnore annotations on id or chunkIndex in SearchResult and remove them
- [ ] T013 [US1] IF TESTS FAIL: Verify ChatService correctly passes SearchResult objects (not modified DTOs) to ChatResponse constructor
- [ ] T014 [US1] IF TESTS FAIL: Add explicit @JsonProperty annotations to SearchResult fields if Jackson is not detecting them

**Checkpoint**: User Story 1 complete - chat responses now include document IDs and chunk indices for source traceability

---

## Phase 4: User Story 2 - Citation Verification (Priority: P2)

**Goal**: Enable users to match inline citations (e.g., [UUID:chunk-5]) in response text to source metadata for verification

**Independent Test**: Send a chat request that generates citations, extract citation references from response text, match them to sources array entries by id and chunkIndex

**Acceptance Scenarios**:
1. Citations like [UUID:chunk-N] match entries in sources array
2. Multiple citations from different documents all traceable to sources

### Tests for User Story 2 (REQUIRED)

- [X] T015 [P] [US2] Write test method testCitationMatchingWithSourceMetadata in ChatResponseMetadataIT that verifies citations in response text can be matched to sources array
- [X] T016 [P] [US2] Write test method testMultipleCitationsFromDifferentDocuments that verifies all citations have corresponding source entries

### Implementation for User Story 2

**Expected Result**: No code changes needed - this is a validation of existing citation behavior

- [X] T017 [US2] Document citation format in quickstart.md (already done - verify completeness)
- [X] T018 [US2] Add Java example code to quickstart.md showing citation matching logic (already done - verify completeness)
- [X] T019 [US2] IF NEEDED: Update ChatService citation post-processing logic to ensure invalid citations are removed (verify existing implementation at ChatService.java:95-104)

**Checkpoint**: User Story 2 complete - citation verification is functional and documented

---

## Phase 5: Edge Cases & Validation (Priority: P3)

**Goal**: Ensure graceful handling of edge cases (synthesized answers, null values, empty sources)

**Independent Test**: Send requests that trigger edge cases and verify proper null handling

### Tests for Edge Cases (REQUIRED)

- [X] T020 [P] Write test method testSynthesizedAnswerWithNullDocumentId that verifies sources with source="LightRAG Answer" have id=null and chunkIndex=null
- [X] T021 [P] Write test method testMixedSourcesDocumentsAndSynthesized that verifies response can contain both document sources (id!=null) and synthesized answers (id=null)
- [X] T022 [P] Write test method testNullFieldsSerializedExplicitly that verifies null id and chunkIndex are serialized as "null" in JSON, not omitted

### Implementation for Edge Cases

- [X] T023 IF TESTS FAIL: Verify SearchResult construction in SearchService properly sets null values for synthesized answers
- [X] T024 IF TESTS FAIL: Configure Jackson to serialize null fields (default behavior, but verify no @JsonInclude(NON_NULL) present)
- [X] T025 Add error handling documentation to quickstart.md for edge cases (already done - verify completeness)

**Checkpoint**: All edge cases handled gracefully - feature is production-ready

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, validation, and final verification

- [X] T026 [P] Update API contract in specs/003-chat-chunk-metadata/contracts/ChatAPI.yaml with examples (already done - verify completeness)
- [X] T027 [P] Verify all code examples in quickstart.md are syntactically correct and runnable
- [X] T028 [P] Add Javadoc comments to SearchResult record documenting id and chunkIndex field semantics
- [X] T029 [P] Add Javadoc comments to ChatResponse record documenting sources field structure
- [X] T030 Run full test suite to ensure no regressions: ./mvnw test
- [X] T031 Run integration tests specifically: ./mvnw verify -DskipITs=false
- [X] T032 Test quickstart.md examples manually using curl commands with real project data
- [X] T033 Verify backward compatibility by running existing ChatServiceTest without modifications
- [X] T034 Review AGENTS.md to ensure chat metadata feature context is documented (already done)
- [X] T035 Run build to confirm no compilation errors: ./mvnw clean package

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup (Phase 1) - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User Story 1 (P1): Core source traceability - MUST complete first (MVP)
  - User Story 2 (P2): Citation verification - Can start after US1 tests pass
  - Edge Cases (P3): Validation - Can start after US1 tests pass
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories - MVP REQUIREMENT
- **User Story 2 (P2)**: Can start after User Story 1 tests pass - Validates that US1 metadata enables citation matching
- **Edge Cases (P3)**: Can start after User Story 1 tests pass - Validates robustness of US1 implementation

### Within Each User Story

- Tests MUST be written and RUN before implementation (they may PASS immediately)
- If tests PASS on first run: Feature already works, skip to next user story
- If tests FAIL: Implement fixes in order (T011 → T012 → T013 → T014)
- Integration tests before documentation updates
- Story complete before moving to next priority

### Parallel Opportunities

- **Phase 1 (Setup)**: T001, T002, T003 can all run in parallel (reading different files)
- **Phase 2 (Foundational)**: T004, T005, T006 can all run in parallel (verification tasks)
- **User Story 1 Tests**: T007, T008, T009, T010 can all run in parallel (different test methods in same class)
- **User Story 2 Tests**: T015, T016 can run in parallel (different test methods)
- **Edge Case Tests**: T020, T021, T022 can all run in parallel (different test methods)
- **Polish Phase**: T026, T027, T028, T029 can all run in parallel (different files)

---

## Parallel Example: User Story 1

```bash
# Launch all test creation for User Story 1 together:
Task: "Create ChatResponseMetadataIT integration test class in src/test/java/br/edu/ifba/chat/ChatResponseMetadataIT.java"
Task: "Write test method testChatResponseIncludesDocumentIdAndChunkIndex"
Task: "Write test method testChatResponseWithMultipleChunksFromSameDocument"
Task: "Write test method testChatResponseWithEmptySources"

# Expected: All tests can be written in parallel, then run together to verify feature works
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (verify existing code structure)
2. Complete Phase 2: Foundational (verify JSON serialization setup)
3. Complete Phase 3: User Story 1 (write tests, verify feature works)
4. **STOP and VALIDATE**: Run tests - if they PASS, feature is done!
5. If tests FAIL: Implement fixes T011-T014
6. Deploy/demo MVP

**Expected Timeline**: 
- **If tests PASS**: 1-2 hours (mostly writing tests and validation)
- **If tests FAIL**: 3-4 hours (includes debugging and fixes)

### Incremental Delivery

1. Complete Setup + Foundational → Foundation verified (30 min)
2. Add User Story 1 → Write tests → Run tests → Feature validated (1-2 hours) → MVP READY ✅
3. Add User Story 2 → Validate citation matching → Deploy/Demo (1 hour)
4. Add Edge Cases → Validate robustness → Deploy/Demo (1 hour)
5. Polish → Documentation and final validation → Production ready (1 hour)

**Total Estimated Time**: 4-6 hours for complete feature (MVP in 2-3 hours)

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together (30 min)
2. Once Foundational is done:
   - Developer A: User Story 1 tests + implementation (if needed)
   - Developer B: User Story 2 tests + validation
   - Developer C: Edge case tests
3. Team reconvenes for Polish phase

---

## Notes

- **CRITICAL**: This is primarily a **validation feature** - the code likely already works!
- Write integration tests FIRST to verify JSON serialization
- If all tests PASS on first run: Feature is done, skip implementation tasks
- If tests FAIL: Investigate serialization configuration and fix
- [P] tasks = different files/test methods, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests run successfully before moving to next phase
- Commit after each phase or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, modifying files unnecessarily, breaking existing tests

---

## Success Validation Checklist

After completing all phases, verify:

- ✅ `ChatResponseMetadataIT` test class exists with all test methods
- ✅ All integration tests pass: `./mvnw verify -DskipITs=false`
- ✅ curl example from quickstart.md returns sources with id and chunkIndex
- ✅ Existing `ChatServiceTest` still passes (backward compatibility)
- ✅ Full build succeeds: `./mvnw clean package`
- ✅ API contract matches implementation (validate with test responses)
- ✅ Documentation examples are accurate and runnable

**Feature Complete When**: All tests pass, documentation validated, backward compatibility confirmed
