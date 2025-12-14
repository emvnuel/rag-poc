# Tasks: Code Source RAG

**Input**: Design documents from `/specs/010-code-source-rag/`  
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/CodeRAG.yaml

**Tests**: Included per Constitution principle II (TDD approach specified in plan.md)

**Organization**: Tasks grouped by user story for independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Exact file paths included in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create new Java classes and extend existing infrastructure

- [X] T001 Create `BinaryFileDetector.java` skeleton in `src/main/java/br/edu/ifba/document/BinaryFileDetector.java`
- [X] T002 [P] Create `LanguageDetector.java` skeleton in `src/main/java/br/edu/ifba/document/LanguageDetector.java`
- [X] T003 [P] Create `CodeChunker.java` skeleton in `src/main/java/br/edu/ifba/document/CodeChunker.java`
- [X] T004 [P] Create `CodeDocumentExtractor.java` skeleton in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T005 [P] Create `CodeExtractionPrompts.java` skeleton in `src/main/java/br/edu/ifba/document/CodeExtractionPrompts.java`
- [X] T006 Add code-related configuration properties to `src/main/resources/application.properties`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core components that MUST be complete before ANY user story can be implemented

**CRITICAL**: All user stories depend on BinaryFileDetector and LanguageDetector

### Tests for Foundational Phase

- [X] T007 [P] Create `BinaryFileDetectorTest.java` with tests for extension blacklist, magic bytes, and NUL byte detection in `src/test/java/br/edu/ifba/document/BinaryFileDetectorTest.java`
- [X] T008 [P] Create `LanguageDetectorTest.java` with tests for 15+ language extensions and content validation in `src/test/java/br/edu/ifba/document/LanguageDetectorTest.java`

### Implementation for Foundational Phase

- [X] T009 Implement `BinaryFileDetector.isBinaryExtension()` with extension blacklist (.pyc, .class, .jar, .so, .dll, etc.) in `src/main/java/br/edu/ifba/document/BinaryFileDetector.java`
- [X] T010 Implement `BinaryFileDetector.isBinaryContent()` with magic bytes (ELF, MZ, CAFEBABE, PK, PNG) and NUL byte detection in `src/main/java/br/edu/ifba/document/BinaryFileDetector.java`
- [X] T011 Implement `BinaryFileDetector.isBinary()` combining extension and content checks in `src/main/java/br/edu/ifba/document/BinaryFileDetector.java`
- [X] T012 Implement `LanguageDetector.detectFromExtension()` with language mapping for 25+ extensions in `src/main/java/br/edu/ifba/document/LanguageDetector.java`
- [X] T013 Implement `LanguageDetector.validateContent()` with regex patterns for common languages in `src/main/java/br/edu/ifba/document/LanguageDetector.java`
- [X] T014 Implement `LanguageDetector.detect()` combining extension and content detection with confidence score in `src/main/java/br/edu/ifba/document/LanguageDetector.java`

**Checkpoint**: BinaryFileDetector and LanguageDetector are functional - user story implementation can begin

---

## Phase 3: User Story 1 - Upload and Query Code Files (Priority: P1)

**Goal**: Enable developers to upload source code files and query the codebase

**Independent Test**: Upload a Java file, ask "What does this code do?", verify coherent answer with code references

### Tests for User Story 1

- [X] T015 [P] [US1] Create `CodeDocumentExtractorTest.java` with tests for `supports()`, `extract()`, and `extractMetadata()` in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`
- [X] T016 [P] [US1] Add test `testAcceptsCodeFile()` verifying .java, .py, .js files are accepted in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`
- [X] T017 [P] [US1] Add test `testRejectsBinaryFile()` verifying binary files are rejected with proper error in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`
- [X] T018 [P] [US1] Add test `testExtractsCodeMetadata()` verifying language, lineCount, imports are extracted in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`

### Implementation for User Story 1

- [X] T019 [US1] Implement `CodeDocumentExtractor.supports()` checking for supported code extensions in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T020 [US1] Implement `CodeDocumentExtractor.extract()` reading code content and rejecting binary files in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T021 [US1] Implement `CodeDocumentExtractor.extractMetadata()` extracting language, lineCount, charCount, imports, topLevelDeclarations in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T022 [US1] Implement `CodeDocumentExtractor.detectLanguage()` using LanguageDetector in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T023 [US1] Implement import extraction regex patterns for common languages (Java, Python, JS/TS, Go) in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T024 [US1] Implement top-level declaration detection (class, function, interface) regex patterns in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T025 [US1] Add structured logging for code file processing (language detected, line count, binary rejection) in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`

**Checkpoint**: Code files can be uploaded and processed - basic querying works via existing RAG pipeline

---

## Phase 4: User Story 2 - Preserve Code Structure in Responses (Priority: P1)

**Goal**: Ensure code snippets in responses maintain proper indentation and formatting

**Independent Test**: Upload code with proper indentation, query it, verify response preserves exact formatting

### Tests for User Story 2

- [X] T026 [P] [US2] Add test `testPreservesIndentation()` in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`
- [X] T027 [P] [US2] Add test `testPreservesSpecialCharacters()` for regex patterns and escape sequences in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`
- [X] T028 [P] [US2] Add test `testHandlesEncodings()` for UTF-8, UTF-16, ISO-8859-1 in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`

### Implementation for User Story 2

- [X] T029 [US2] Implement encoding detection and UTF-8 conversion in `CodeDocumentExtractor.extract()` in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T030 [US2] Ensure raw content extraction preserves exact whitespace (no trimming, no normalization) in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`
- [X] T031 [US2] Add ENCODING_ERROR response handling when encoding conversion fails in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`

**Checkpoint**: Code formatting is preserved end-to-end from upload to query response

---

## Phase 5: User Story 3 - Understand Code Context and Relationships (Priority: P2)

**Goal**: Extract code entities (functions, classes) and relationships (imports, calls, inheritance)

**Independent Test**: Upload related code files, ask about dependencies, verify correct relationship identification

### Tests for User Story 3

- [ ] T032 [P] [US3] Create `CodeExtractionPromptsTest.java` with tests for entity extraction prompts in `src/test/java/br/edu/ifba/lightrag/core/CodeExtractionPromptsTest.java`
- [ ] T033 [P] [US3] Create `CodeExtractionIT.java` integration test for entity and relationship extraction in `src/test/java/br/edu/ifba/lightrag/CodeExtractionIT.java`
- [ ] T034 [P] [US3] Add test `testExtractsCodeEntities()` verifying FUNCTION, CLASS, MODULE extraction in `src/test/java/br/edu/ifba/lightrag/CodeExtractionIT.java`
- [ ] T035 [P] [US3] Add test `testExtractsRelationships()` verifying IMPORTS, CALLS, EXTENDS extraction in `src/test/java/br/edu/ifba/lightrag/CodeExtractionIT.java`

### Implementation for User Story 3

- [X] T036 [US3] Define new entity types (FUNCTION, CLASS, MODULE, INTERFACE, VARIABLE, API_ENDPOINT, DEPENDENCY) as constants in `src/main/java/br/edu/ifba/lightrag/core/CodeExtractionPrompts.java`
- [X] T037 [US3] Define new relationship types (IMPORTS, CALLS, EXTENDS, IMPLEMENTS, DEFINES, DEPENDS_ON, RETURNS, ACCEPTS) as constants in `src/main/java/br/edu/ifba/lightrag/core/CodeExtractionPrompts.java`
- [X] T038 [US3] Implement code entity extraction system prompt with examples for each entity type in `src/main/java/br/edu/ifba/lightrag/core/CodeExtractionPrompts.java`
- [X] T039 [US3] Implement code relationship extraction system prompt with examples in `src/main/java/br/edu/ifba/lightrag/core/CodeExtractionPrompts.java`
- [X] T040 [US3] Modify `LightRAG.java` to use code-specific prompts when document type is CODE in `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`
- [X] T041 [US3] Add entity description format including source file and line number reference in `src/main/java/br/edu/ifba/lightrag/core/CodeExtractionPrompts.java`

**Checkpoint**: Code entities and relationships are extracted and queryable

---

## Phase 6: User Story 4 - Language-Agnostic Code Handling (Priority: P2)

**Goal**: Support 15+ programming languages without language-specific configuration

**Independent Test**: Upload files in Java, Python, JavaScript, Go, Rust - verify all processed correctly

### Tests for User Story 4

- [X] T042 [P] [US4] Add test `testMultipleLanguages()` for 15+ languages in `src/test/java/br/edu/ifba/document/LanguageDetectorTest.java`
- [X] T043 [P] [US4] Add test `testUnknownExtension()` verifying fallback to text processing in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`
- [X] T044 [P] [US4] Add test `testNoExtension()` verifying file without extension is processed in `src/test/java/br/edu/ifba/document/CodeDocumentExtractorTest.java`

### Implementation for User Story 4

- [X] T045 [US4] Add complete language mapping for all 25+ extensions (.java, .py, .js, .jsx, .ts, .tsx, .mjs, .go, .rs, .c, .cpp, .cc, .h, .hpp, .cs, .rb, .php, .swift, .kt, .kts, .scala, .sh, .bash, .sql, .r, .lua, .pl, .pm) in `src/main/java/br/edu/ifba/document/LanguageDetector.java`
- [X] T046 [US4] Add content validation patterns for 15 most common languages in `src/main/java/br/edu/ifba/document/LanguageDetector.java`
- [X] T047 [US4] Implement fallback handling for unknown extensions (process as generic text code) in `src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java`

**Checkpoint**: All major programming languages are supported

---

## Phase 7: User Story 5 - Code-Aware Chunking (Priority: P3)

**Goal**: Chunk code by logical boundaries (functions, classes) rather than arbitrary character limits

**Independent Test**: Upload file with multiple functions, verify chunks align with function boundaries

### Tests for User Story 5

- [X] T048 [P] [US5] Create `CodeChunkerTest.java` with tests for boundary detection in `src/test/java/br/edu/ifba/document/CodeChunkerTest.java`
- [X] T049 [P] [US5] Add test `testChunkAtFunctionBoundary()` verifying chunks align with function definitions in `src/test/java/br/edu/ifba/document/CodeChunkerTest.java`
- [X] T050 [P] [US5] Add test `testChunkAtClassBoundary()` verifying class declarations stay with their bodies in `src/test/java/br/edu/ifba/document/CodeChunkerTest.java`
- [X] T051 [P] [US5] Add test `testNoMidTokenSplit()` verifying chunks don't split mid-statement in `src/test/java/br/edu/ifba/document/CodeChunkerTest.java`
- [X] T052 [P] [US5] Add test `testLargeFunction()` verifying oversized functions split at statement boundaries in `src/test/java/br/edu/ifba/document/CodeChunkerTest.java`
- [X] T053 [P] [US5] Add test `testChunkMetadata()` verifying startLine, endLine, containingScope in chunk metadata in `src/test/java/br/edu/ifba/document/CodeChunkerTest.java`

### Implementation for User Story 5

- [X] T054 [US5] Implement `CodeChunker.CodeChunk` record with content, startLine, endLine, containingScope, scopeType, chunkType in `src/main/java/br/edu/ifba/document/CodeChunker.java`
- [X] T055 [US5] Implement `CodeChunker.detectBoundaries()` with regex patterns for class/function declarations in `src/main/java/br/edu/ifba/document/CodeChunker.java`
- [X] T056 [US5] Implement universal boundary patterns: CLASS (class/struct/interface), FUNCTION (func/def/fn), IMPORTS (import/require/use) in `src/main/java/br/edu/ifba/document/CodeChunker.java`
- [X] T057 [US5] Implement `CodeChunker.splitAtStatementBoundaries()` for oversized sections (split at ; or } or newline after )) in `src/main/java/br/edu/ifba/document/CodeChunker.java`
- [X] T058 [US5] Implement `CodeChunker.chunk()` main algorithm: detect boundaries, group by token limit, fall back to statement splits in `src/main/java/br/edu/ifba/document/CodeChunker.java`
- [X] T059 [US5] Add decorator/annotation preservation (keep @Decorator with following declaration) in `src/main/java/br/edu/ifba/document/CodeChunker.java`
- [X] T060 [US5] Add import block grouping (keep imports together at file start) in `src/main/java/br/edu/ifba/document/CodeChunker.java`
- [X] T061 [US5] Modify `LightRAG.java` to use `CodeChunker` for CODE type documents instead of default chunker in `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`
- [X] T062 [US5] Add chunk metadata (startLine, endLine, containingScope) to vector storage metadata JSONB in `src/main/java/br/edu/ifba/lightrag/core/LightRAG.java`

**Checkpoint**: Code is chunked by logical boundaries with proper metadata

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Final improvements, validation, and documentation

- [X] T063 [P] Create `CodeQueryIT.java` integration test for end-to-end code querying in `src/test/java/br/edu/ifba/lightrag/CodeQueryIT.java`
- [X] T064 [P] Add test `testSourceAttribution()` verifying filename and line number in responses in `src/test/java/br/edu/ifba/lightrag/CodeQueryIT.java`
- [X] T065 [P] Add test `testCrossLanguageQuery()` verifying queries work across Java, Python, TypeScript files in `src/test/java/br/edu/ifba/lightrag/CodeQueryIT.java`
- [X] T066 Update AGENTS.md with code source RAG configuration options and testing commands
- [ ] T067 Run quickstart.md validation with sample Java, Python, TypeScript files
- [ ] T068 Add performance test `CodePerformanceIT.java` for large file (10k lines) processing in `src/test/java/br/edu/ifba/lightrag/CodePerformanceIT.java`
- [ ] T069 Verify all tests pass with both PostgreSQL and SQLite backends
- [X] T070 Update `checklists/requirements.md` marking all FRs as implemented

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies - can start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 skeleton classes
- **Phase 3-7 (User Stories)**: All depend on Phase 2 (BinaryFileDetector + LanguageDetector)
- **Phase 8 (Polish)**: Depends on all user stories being complete

### User Story Dependencies

| Story | Depends On | Can Start After |
|-------|------------|-----------------|
| US1 (Upload/Query) | Foundational | Phase 2 complete |
| US2 (Preserve Structure) | US1 | T025 (basic extraction works) |
| US3 (Relationships) | US1 | T025 (basic extraction works) |
| US4 (Multi-Language) | Foundational | Phase 2 complete |
| US5 (Chunking) | US1, US4 | T025 + T047 complete |

### Within Each User Story

1. Tests written FIRST, verify they FAIL
2. Implementation to make tests PASS
3. Verify story independently testable before moving on

### Parallel Opportunities

**Phase 1 (all [P]):**
```
T002, T003, T004, T005 can run in parallel (different skeleton files)
```

**Phase 2:**
```
T007, T008 can run in parallel (different test files)
```

**User Story 1:**
```
T015, T016, T017, T018 can run in parallel (same test file but independent tests)
```

**User Story 5:**
```
T048-T053 can run in parallel (all test methods in same file)
```

---

## Parallel Example: Phase 2 Foundational

```bash
# Launch all tests in parallel:
Task: "Create BinaryFileDetectorTest.java in src/test/java/br/edu/ifba/document/"
Task: "Create LanguageDetectorTest.java in src/test/java/br/edu/ifba/document/"
```

## Parallel Example: User Story 5 Chunking

```bash
# Launch all chunking tests in parallel:
Task: "Add test testChunkAtFunctionBoundary() in CodeChunkerTest.java"
Task: "Add test testChunkAtClassBoundary() in CodeChunkerTest.java"
Task: "Add test testNoMidTokenSplit() in CodeChunkerTest.java"
Task: "Add test testLargeFunction() in CodeChunkerTest.java"
Task: "Add test testChunkMetadata() in CodeChunkerTest.java"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 2 Only)

1. Complete Phase 1: Setup (skeleton classes)
2. Complete Phase 2: Foundational (BinaryFileDetector, LanguageDetector)
3. Complete Phase 3: User Story 1 (Upload and Query)
4. Complete Phase 4: User Story 2 (Preserve Structure)
5. **STOP and VALIDATE**: Upload a code file, query it, verify formatting preserved
6. Deploy/demo if ready - users can upload and query code!

### Incremental Delivery

| Increment | User Stories | Deliverable |
|-----------|--------------|-------------|
| MVP | US1 + US2 | Basic code upload and query with formatting |
| v1.1 | + US3 | Code relationships (imports, calls, inheritance) |
| v1.2 | + US4 | Full 15+ language support |
| v1.3 | + US5 | Smart code-aware chunking |

### Task Counts

| Phase | Tasks | Parallel [P] |
|-------|-------|--------------|
| Setup | 6 | 4 |
| Foundational | 8 | 2 |
| US1 (Upload/Query) | 11 | 4 |
| US2 (Preserve Structure) | 6 | 3 |
| US3 (Relationships) | 10 | 4 |
| US4 (Multi-Language) | 6 | 3 |
| US5 (Chunking) | 15 | 6 |
| Polish | 8 | 5 |
| **Total** | **70** | **31** |

---

## Notes

- [P] tasks = different files, no dependencies between them
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests FAIL before implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- US1+US2 together form the practical MVP (code must preserve formatting to be useful)
