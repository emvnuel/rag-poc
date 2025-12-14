# Implementation Plan: Code Source RAG

**Branch**: `010-code-source-rag` | **Date**: 2025-12-13 | **Spec**: [spec.md](./spec.md)  
**Input**: Feature specification from `/specs/010-code-source-rag/spec.md`

## Summary

Enable RAG on source code files across all programming languages using a language-agnostic approach. The system will support uploading, processing, chunking, and querying code files while preserving code structure, extracting code-specific entities (functions, classes, modules), and maintaining relationships (imports, calls, inheritance).

**Technical Approach**: Extend the existing document extraction pipeline with:
1. A `CodeDocumentExtractor` supporting common code file extensions
2. Code-aware chunking that respects logical boundaries (functions, classes) when detectable
3. Enhanced entity extraction prompts for code-specific entities and relationships
4. Metadata enrichment with file path, language, and line numbers

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Quarkus 3.28.4, Jakarta EE, jtokkit (token counting)  
**Storage**: PostgreSQL 14+ with Apache AGE (graph) and pgvector (embeddings), SQLite (alternative)  
**Testing**: JUnit 5 + REST Assured, `@QuarkusTest` for integration tests  
**Target Platform**: Linux server (Docker), macOS (development)  
**Project Type**: Single backend project (Quarkus REST API)  
**Performance Goals**: 
- Process code files <10,000 lines in <30 seconds
- Query latency P95 <5 seconds for codebases up to 1,000 files
**Constraints**: 
- No external AST parsing libraries in initial version (YAGNI)
- Use regex-based heuristics for language-agnostic chunking
- Preserve exact formatting (indentation, whitespace)
**Scale/Scope**: 
- Support 15+ programming languages
- Up to 1,000 code files per project
- Up to 50MB max file size (existing limit)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Compliance | Notes |
|-----------|------------|-------|
| **I. Code Quality Standards** | ✅ PASS | Will use Jakarta EE annotations, *Resource/*Service naming, Javadoc |
| **II. Testing Standards** | ✅ PASS | TDD approach: tests first for CodeDocumentExtractor, chunking logic |
| **III. API Consistency** | ✅ PASS | Extends existing `/projects/{id}/documents` endpoints, no new REST resources needed |
| **IV. Performance Requirements** | ✅ PASS | Code files are text-based, similar to existing TXT processing; 30s SLA matches PDF processing |
| **V. Observability** | ✅ PASS | Will use existing structured logging, add code-specific metrics (language, line count) |
| **Database Queries** | ✅ PASS | No new indexes required; uses existing vector/graph storage |
| **LLM API Calls** | ✅ PASS | Entity extraction uses existing retry/circuit breaker patterns |
| **Resource Limits** | ✅ PASS | 50MB file limit already configured; existing chunk size limits apply |
| **YAGNI Principle** | ✅ PASS | No AST libraries initially; simple regex-based heuristics for chunking boundaries |

**Gate Status**: ✅ PASSED - All principles satisfied, no violations requiring justification.

## Project Structure

### Documentation (this feature)

```text
specs/010-code-source-rag/
├── plan.md              # This file
├── research.md          # Phase 0 output - chunking strategies, embedding models
├── data-model.md        # Phase 1 output - CodeFile, CodeChunk entities
├── quickstart.md        # Phase 1 output - testing code ingestion
├── contracts/           # Phase 1 output - API contracts if needed
│   └── CodeRAG.yaml     # OpenAPI for any new endpoints
├── checklists/
│   └── requirements.md  # FR/NFR checklist
└── tasks.md             # Phase 2 output (NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/main/java/br/edu/ifba/
├── document/
│   ├── CodeDocumentExtractor.java        # NEW: Code file extraction
│   ├── CodeChunker.java                  # NEW: Code-aware chunking logic
│   ├── DocumentExtractor.java            # EXISTING: Interface (no changes)
│   ├── DocumentExtractorFactory.java     # EXISTING: Auto-discovers new extractor
│   └── PlainTextDocumentExtractor.java   # EXISTING: Reference implementation
├── lightrag/
│   ├── core/
│   │   ├── LightRAG.java                 # MODIFY: Use code chunker for code files
│   │   └── CodeExtractionPrompts.java    # NEW: Code-specific entity prompts
│   └── utils/
│       └── TokenUtil.java                # EXISTING: Chunking utilities
└── ...

src/test/java/br/edu/ifba/
├── document/
│   ├── CodeDocumentExtractorTest.java    # NEW: Unit tests
│   └── CodeChunkerTest.java              # NEW: Chunking logic tests
└── lightrag/
    └── CodeExtractionIT.java             # NEW: Integration tests
```

**Structure Decision**: Single project structure - this feature extends existing document processing pipeline. No new REST endpoints required; code files are processed through the existing `/projects/{projectId}/documents` upload endpoint.

## Complexity Tracking

> No violations requiring justification. Feature aligns with existing architecture.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A | N/A | N/A |

## Implementation Phases

### Phase 0: Research (Complete in research.md)

**Unknowns to Resolve**:
1. **Code Chunking Strategy**: Regex-based vs AST-based for language-agnostic support
2. **Language Detection**: Heuristics for detecting programming language from extension + content
3. **Binary File Detection**: How to reliably detect binary files uploaded with code extensions
4. **Chunk Boundary Heuristics**: Patterns to identify function/class boundaries across languages
5. **Embedding Model**: Whether current `nomic-embed-text` is adequate for code, or code-specific model needed

### Phase 1: Design (Complete in data-model.md, contracts/, quickstart.md)

**Key Decisions**:
1. Extend existing entities vs create new code-specific entities
2. Entity types to add for code (FUNCTION, CLASS, MODULE, INTERFACE, etc.)
3. Metadata schema for code chunks (line numbers, containing scope, language)
4. Prompt templates for code entity extraction

### Phase 2: Tasks (Generated separately by /speckit.tasks)

Will break down implementation into TDD-driven tasks based on research and design outputs.
