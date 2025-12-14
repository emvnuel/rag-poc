# Data Model: Code Source RAG

**Feature**: 010-code-source-rag  
**Date**: 2025-12-13  
**Status**: Draft

## Overview

This feature extends the existing document processing pipeline to support source code files. Rather than creating entirely new entities, we extend the existing `Document` entity with code-specific metadata and enhance the entity extraction to recognize code-specific types.

## Design Principles

1. **Extend, Don't Replace**: Use existing Document/Chunk entities with enriched metadata
2. **Language-Agnostic**: No language-specific tables or schemas
3. **Backward Compatible**: Existing documents continue to work unchanged
4. **Metadata-Driven**: Store code-specific information in JSONB metadata fields

## Entity Modifications

### 1. Document (Existing - Extended)

The existing `Document` entity is extended with code-specific metadata. No schema changes required - metadata is stored in the existing JSONB `metadata` column.

**Existing Fields** (unchanged):
- `id` (UUID v7)
- `project_id` (UUID, FK to projects)
- `type` (VARCHAR) - now includes "CODE"
- `status` (VARCHAR) - NOT_PROCESSED, PROCESSING, PROCESSED
- `file_name` (VARCHAR)
- `content` (TEXT)
- `metadata` (JSONB)
- `created_at`, `updated_at` (TIMESTAMP)

**New Document Type**: `CODE`

**Extended Metadata Schema** (for CODE documents):

```json
{
  "language": "java",
  "languageDetectionMethod": "extension",
  "fileExtension": ".java",
  "lineCount": 245,
  "characterCount": 8432,
  "encoding": "UTF-8",
  "imports": [
    "java.util.List",
    "jakarta.inject.Inject"
  ],
  "topLevelDeclarations": [
    {"type": "class", "name": "UserService", "line": 12},
    {"type": "interface", "name": "UserServicePort", "line": 5}
  ],
  "isTestFile": false,
  "hasMainMethod": false
}
```

### 2. Vector/Chunk (Existing - Extended)

The existing vector storage is extended with code-specific chunk metadata. No schema changes required - metadata stored in existing JSONB fields.

**Existing Fields** (unchanged):
- `id` (UUID v7)
- `document_id` (UUID, FK)
- `project_id` (UUID, FK)
- `content` (TEXT)
- `embedding` (VECTOR)
- `metadata` (JSONB)

**Extended Chunk Metadata Schema** (for CODE chunks):

```json
{
  "fileName": "UserService.java",
  "filePath": "src/main/java/com/example/service/UserService.java",
  "language": "java",
  "startLine": 45,
  "endLine": 72,
  "containingScope": "UserService.createUser",
  "scopeType": "method",
  "chunkType": "function_body",
  "imports": ["com.example.repository.UserRepository"],
  "lineCount": 28,
  "indentLevel": 1
}
```

**Chunk Types**:
| Type | Description |
|------|-------------|
| `file_header` | Imports, package declarations, file-level comments |
| `class_definition` | Class/struct/interface declaration with docstring |
| `function_body` | Complete function/method with signature |
| `function_fragment` | Part of large function split across chunks |
| `block` | Generic code block (fallback) |

### 3. Entity (Existing - Extended Types)

The existing graph entity storage is extended with new entity types. No schema changes required.

**Existing Fields** (unchanged):
- `name` (VARCHAR)
- `type` (VARCHAR)
- `description` (TEXT)
- `project_id` (UUID)
- `source_chunk_ids` (TEXT[])

**New Entity Types** (added to existing):

| Type | Description | Name Examples |
|------|-------------|---------------|
| `FUNCTION` | Functions, methods, procedures | `calculateTotal`, `UserService.createUser` |
| `CLASS` | Classes, structs, types | `UserService`, `OrderRepository` |
| `MODULE` | Modules, packages, namespaces | `com.example.service`, `auth.service` |
| `INTERFACE` | Interfaces, protocols, traits | `Serializable`, `UserServicePort` |
| `VARIABLE` | Constants, global variables | `MAX_RETRIES`, `DEFAULT_TIMEOUT` |
| `API_ENDPOINT` | REST/GraphQL endpoints | `POST /api/users`, `GET /api/orders/{id}` |
| `DEPENDENCY` | External libraries | `spring-boot-starter-web`, `react` |

**Entity Description Format** (for code entities):

```
[Type] defined in [file] at line [N]. [Docstring if available]. [Inferred purpose].
```

Example:
```
Method that creates a new user in the database. Validates input, hashes password, and persists to UserRepository. Throws ValidationException on invalid input. Defined in UserService.java at line 45.
```

### 4. Relation (Existing - Extended Types)

**Existing Fields** (unchanged):
- `source` (VARCHAR)
- `target` (VARCHAR)
- `type` (VARCHAR)
- `description` (TEXT)
- `project_id` (UUID)
- `source_chunk_ids` (TEXT[])

**New Relationship Types** (added to existing):

| Type | Source | Target | Description |
|------|--------|--------|-------------|
| `IMPORTS` | MODULE/CLASS | MODULE/CLASS | Import/require dependency |
| `CALLS` | FUNCTION | FUNCTION | Function invocation |
| `EXTENDS` | CLASS | CLASS | Class inheritance |
| `IMPLEMENTS` | CLASS | INTERFACE | Interface implementation |
| `DEFINES` | CLASS/MODULE | FUNCTION | Containment (class contains method) |
| `DEPENDS_ON` | MODULE | DEPENDENCY | External dependency |
| `RETURNS` | FUNCTION | CLASS | Return type |
| `ACCEPTS` | FUNCTION | CLASS | Parameter type |

## New Java Classes

### 1. CodeDocumentExtractor

```java
package br.edu.ifba.document;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Extracts text and metadata from source code files.
 * Supports multiple programming languages through extension detection.
 */
@ApplicationScoped
public class CodeDocumentExtractor implements DocumentExtractor {
    
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".java", ".py", ".js", ".jsx", ".ts", ".tsx", ".mjs",
        ".go", ".rs", ".c", ".cpp", ".cc", ".h", ".hpp",
        ".cs", ".rb", ".php", ".swift", ".kt", ".kts", ".scala",
        ".sh", ".bash", ".sql", ".r", ".lua", ".pl", ".pm"
    );
    
    @Override
    public String extract(InputStream inputStream) throws IOException;
    
    @Override
    public Map<String, Object> extractMetadata(InputStream inputStream) throws IOException;
    
    @Override
    public boolean supports(String fileName);
    
    // Detect binary files
    public boolean isBinaryFile(byte[] header);
    
    // Detect language from extension and content
    public String detectLanguage(String fileName, String content);
}
```

### 2. CodeChunker

```java
package br.edu.ifba.document;

import java.util.List;
import java.util.Map;

/**
 * Chunks source code files while respecting logical boundaries.
 * Uses regex-based heuristics for language-agnostic boundary detection.
 */
@ApplicationScoped
public class CodeChunker {
    
    /**
     * Chunk result containing text and metadata.
     */
    public record CodeChunk(
        String content,
        int startLine,
        int endLine,
        String containingScope,
        String scopeType,
        String chunkType
    ) {}
    
    /**
     * Chunks code content into logical units.
     *
     * @param content raw source code
     * @param fileName file name for language detection
     * @param maxTokens maximum tokens per chunk
     * @param overlapTokens overlap between chunks
     * @return list of code chunks with metadata
     */
    public List<CodeChunk> chunk(
        String content,
        String fileName,
        int maxTokens,
        int overlapTokens
    );
    
    // Detect function/class boundaries
    List<Boundary> detectBoundaries(String content, String language);
    
    // Split at logical points (statement ends)
    List<String> splitAtStatementBoundaries(String content, int maxTokens);
}
```

### 3. LanguageDetector

```java
package br.edu.ifba.document;

import java.util.Optional;

/**
 * Detects programming language from file extension and content.
 */
@ApplicationScoped
public class LanguageDetector {
    
    public record DetectionResult(
        String language,
        String method,  // "extension", "content", "heuristic"
        double confidence
    ) {}
    
    /**
     * Detect language from file name and optionally content.
     */
    public DetectionResult detect(String fileName, String content);
    
    /**
     * Get language from extension only.
     */
    public Optional<String> detectFromExtension(String fileName);
    
    /**
     * Validate content matches expected language.
     */
    public boolean validateContent(String content, String expectedLanguage);
}
```

### 4. BinaryFileDetector

```java
package br.edu.ifba.document;

/**
 * Detects binary files that should not be processed as code.
 */
@ApplicationScoped  
public class BinaryFileDetector {
    
    /**
     * Check if file is binary based on extension.
     */
    public boolean isBinaryExtension(String fileName);
    
    /**
     * Check if content appears to be binary.
     * Examines magic bytes and NUL byte frequency.
     */
    public boolean isBinaryContent(byte[] header);
    
    /**
     * Combined check for binary file detection.
     */
    public boolean isBinary(String fileName, byte[] header);
}
```

## Configuration

Add to `application.properties`:

```properties
# Code Document Extraction
lightrag.code.extraction.enabled=${LIGHTRAG_CODE_EXTRACTION_ENABLED:true}

# Binary file detection
lightrag.code.binary.check.size=${LIGHTRAG_CODE_BINARY_CHECK_SIZE:8192}

# Code-specific chunking
lightrag.code.chunk.prefer-boundaries=${LIGHTRAG_CODE_CHUNK_PREFER_BOUNDARIES:true}
lightrag.code.chunk.min-chunk-lines=${LIGHTRAG_CODE_CHUNK_MIN_LINES:5}

# Extended entity types (append to existing)
lightrag.entity.types.code=${LIGHTRAG_CODE_ENTITY_TYPES:function,class,module,interface,variable,api_endpoint,dependency}

# Code extraction prompts
lightrag.code.extraction.system.prompt=${LIGHTRAG_CODE_EXTRACTION_SYSTEM_PROMPT:Extract entities and relationships from the following source code.}
```

## Validation Rules

### Document Validation
1. **Binary Rejection**: Files detected as binary MUST be rejected with `BINARY_FILE_REJECTED` error
2. **Size Limit**: Files >50MB MUST be rejected (existing limit)
3. **Encoding**: Non-UTF-8 files SHOULD be converted; if conversion fails, reject with `ENCODING_ERROR`

### Chunk Validation
1. **Minimum Size**: Chunks <5 lines SHOULD be merged with adjacent chunks
2. **Maximum Size**: Chunks MUST NOT exceed configured `maxTokens`
3. **Scope Preservation**: Class/function declarations MUST NOT be split from their bodies when possible

### Entity Validation
1. **Name Normalization**: Entity names MUST be normalized (trimmed, no quotes)
2. **Type Validation**: Entity types MUST be from allowed list
3. **Self-Loop Prevention**: Relations where source == target MUST be rejected

## State Transitions

Code documents follow the existing document state machine:

```
┌─────────────────┐
│  NOT_PROCESSED  │ ←── Initial state after upload
└────────┬────────┘
         │
         │ Scheduler picks up
         ▼
┌─────────────────┐
│   PROCESSING    │ ←── Extracting, chunking, embedding
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌───────┐ ┌────────┐
│PROCESSED│ │ FAILED │
└─────────┘ └────────┘
```

## Indexes

No new indexes required. Existing indexes on:
- `vectors.project_id` - Project isolation
- `vectors.document_id` - Document lookup
- `graph_entities.project_id` - Entity queries
- HNSW index on `vectors.embedding` - Similarity search

## Migration

No database migration required. This feature:
1. Uses existing tables with JSONB metadata
2. Adds new document type (`CODE`) handled by application code
3. Adds new entity/relation types stored as strings
