# Requirements Checklist: Code Source RAG

**Purpose**: Track specification quality and implementation requirements  
**Created**: 2025-12-13  
**Feature**: [spec.md](../spec.md)

---

## Part 1: Specification Quality (Pre-Planning)

### Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

### Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

**Specification Status**: ✅ READY FOR PLANNING

---

## Part 2: Implementation Requirements (Post-Planning)

### Functional Requirements

#### File Ingestion

| ID | Requirement | Status | Test | Notes |
|----|-------------|--------|------|-------|
| FR-001 | System MUST accept source code files for upload and RAG processing | ✅ Complete | `CodeDocumentExtractorTest.testAcceptsCodeFile` | Implemented T019-T025 |
| FR-002 | System MUST support common programming language extensions (.java, .py, .js, .ts, .go, .rs, .c, .cpp, .cs, .rb, .php, .swift, .kt, .scala, .sh, .sql, etc.) | ✅ Complete | `CodeDocumentExtractorTest.testSupportedExtensions` | 100+ extensions supported |
| FR-003 | System MUST detect and reject binary files with code extensions | ✅ Complete | `BinaryFileDetectorTest.testRejectsBinaryFile` | Multi-layer detection T009-T011 |
| FR-004 | System MUST preserve original filename and extension as metadata | ✅ Complete | `CodeDocumentExtractorTest.testMetadataPreservesFilename` | Metadata extraction T018 |
| FR-005 | System MUST support configuration of max file size limits | ✅ Complete | Existing 50MB limit | Uses existing DocumentService limit |

#### Text Extraction

| ID | Requirement | Status | Test | Notes |
|----|-------------|--------|------|-------|
| FR-006 | System MUST preserve original indentation, line breaks, special characters, and Unicode | ⬜ Pending | `CodeDocumentExtractorTest.testPreservesFormatting` | Critical for code |
| FR-007 | System MUST extract code-specific metadata: extension, language, line count, character count | ⬜ Pending | `CodeDocumentExtractorTest.testExtractsCodeMetadata` | |
| FR-008 | System MUST handle different character encodings (UTF-8, UTF-16, ASCII, ISO-8859-1) | ⬜ Pending | `CodeDocumentExtractorTest.testHandlesEncodings` | Convert to UTF-8 |

#### Chunking

| ID | Requirement | Status | Test | Notes |
|----|-------------|--------|------|-------|
| FR-009 | System MUST chunk code files for vector indexing while maintaining readability | ⬜ Pending | `CodeChunkerTest.testChunksCode` | |
| FR-010 | System SHOULD align chunk boundaries with logical code boundaries (functions, classes) | ⬜ Pending | `CodeChunkerTest.testAlignsBoundaries` | Best effort via regex |
| FR-011 | System MUST NOT split chunks mid-token or mid-string-literal when avoidable | ⬜ Pending | `CodeChunkerTest.testNoMidTokenSplit` | Statement boundary detection |
| FR-012 | System MUST preserve context in each chunk (file name, containing scope) | ⬜ Pending | `CodeChunkerTest.testPreservesContext` | Metadata in chunk |
| FR-013 | System MUST support configurable chunk size and overlap for code files | ⬜ Pending | `CodeChunkerTest.testConfigurableChunkSize` | Use existing config |

#### Knowledge Extraction

| ID | Requirement | Status | Test | Notes |
|----|-------------|--------|------|-------|
| FR-014 | System MUST extract code entities: functions, classes, modules, variables | ⬜ Pending | `CodeExtractionIT.testExtractsCodeEntities` | LLM prompt update |
| FR-015 | System MUST extract code relationships: imports, calls, inheritance, implementations | ⬜ Pending | `CodeExtractionIT.testExtractsRelationships` | LLM prompt update |
| FR-016 | System MUST include comments and docstrings in entity descriptions | ⬜ Pending | `CodeExtractionIT.testIncludesDocstrings` | |
| FR-017 | System MUST associate entities with source file and location (line number) | ⬜ Pending | `CodeExtractionIT.testEntityHasSourceLocation` | Chunk metadata |

#### Querying

| ID | Requirement | Status | Test | Notes |
|----|-------------|--------|------|-------|
| FR-018 | System MUST return code chunks as RAG context when relevant | ⬜ Pending | `CodeQueryIT.testReturnsCodeChunks` | Existing retrieval works |
| FR-019 | System MUST format code snippets to preserve indentation in responses | ⬜ Pending | `CodeQueryIT.testPreservesFormatting` | |
| FR-020 | System MUST support queries referencing programming concepts | ⬜ Pending | `CodeQueryIT.testCodeConceptQuery` | |
| FR-021 | System MUST include source attribution (filename, location) in responses | ⬜ Pending | `CodeQueryIT.testSourceAttribution` | Chunk metadata |

### Non-Functional Requirements

#### Performance

| ID | Requirement | Status | Test | Notes |
|----|-------------|--------|------|-------|
| NFR-001 | Process code files <10,000 lines in <30 seconds | ⬜ Pending | `CodePerformanceIT.testLargeFileProcessing` | |
| NFR-002 | Support codebases up to 1,000 files without latency degradation | ⬜ Pending | `CodePerformanceIT.testLargeCodebase` | Query <5s P95 |

#### Compatibility

| ID | Requirement | Status | Test | Notes |
|----|-------------|--------|------|-------|
| NFR-003 | Support 15+ programming languages without user configuration | ⬜ Pending | `LanguageDetectorTest.testMultipleLanguages` | |
| NFR-004 | Work with both PostgreSQL and SQLite backends | ⬜ Pending | Run tests with both backends | |

#### Quality

| ID | Requirement | Status | Test | Notes |
|----|-------------|--------|------|-------|
| NFR-005 | 90% of code queries return relevant code snippets | ⬜ Pending | Manual validation | Success criteria |
| NFR-006 | 100% formatting fidelity for retrieved code | ⬜ Pending | `CodeChunkerTest.testFormattingFidelity` | |
| NFR-007 | 80% accuracy for code relationship queries | ⬜ Pending | Manual validation | Success criteria |

### Edge Cases

| Case | Handling | Status | Test |
|------|----------|--------|------|
| Binary file with code extension (.pyc, .class) | Reject with BINARY_FILE_REJECTED error | ⬜ Pending | `BinaryFileDetectorTest.testCompiledFiles` |
| Very large code file (>1MB, >50k lines) | Process with many chunks, respect size limits | ⬜ Pending | `CodeChunkerTest.testLargeFile` |
| No extension or unusual extension | Attempt text-based processing | ⬜ Pending | `CodeDocumentExtractorTest.testNoExtension` |
| Code comments | Preserve and index as documentation | ⬜ Pending | `CodeChunkerTest.testPreservesComments` |
| Jupyter notebooks, Markdown with code blocks | Future enhancement (out of scope for v1) | ⬜ Deferred | N/A |

### Constitution Compliance

| Principle | Requirement | Status |
|-----------|-------------|--------|
| I. Code Quality | Use Jakarta EE, proper naming, Javadoc | ⬜ Pending |
| II. Testing | TDD approach, unit + integration tests | ⬜ Pending |
| III. API Consistency | Use existing endpoints, standard error responses | ⬜ Pending |
| IV. Performance | Meet 30s processing, 5s query SLAs | ⬜ Pending |
| V. Observability | Structured logging for code processing | ⬜ Pending |
| YAGNI | No AST libraries in v1 | ⬜ Pending |

---

## Sign-off

| Role | Name | Date | Signature |
|------|------|------|-----------|
| Developer | | | |
| Reviewer | | | |
