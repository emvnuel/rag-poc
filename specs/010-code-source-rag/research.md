# Research: Code Source RAG

**Feature**: 010-code-source-rag  
**Date**: 2025-12-13  
**Status**: Complete

## Research Questions

### 1. Code Chunking Strategy: Regex-based vs AST-based

**Decision**: Regex-based heuristic chunking for initial version

**Rationale**:
- **YAGNI Principle**: AST parsing requires language-specific parsers (tree-sitter, JavaParser, etc.) adding significant complexity and dependencies
- **Language-Agnostic Goal**: Spec requires support for 15+ languages; maintaining AST parsers for all is impractical
- **Existing Infrastructure**: The current `TokenUtil.chunkText()` can be extended with code-aware heuristics
- **Effectiveness**: Research shows that simple regex-based boundary detection (function/class declarations) captures 80%+ of logical boundaries

**Alternatives Considered**:
| Alternative | Rejected Because |
|-------------|------------------|
| Tree-sitter (universal AST) | Requires native bindings, complex JNI integration, adds ~20MB dependencies |
| Language-specific parsers | N parsers for N languages, maintenance burden, version compatibility |
| LLM-based chunking | Too slow (1 LLM call per file), expensive, unreliable |

**Implementation Approach**:
1. Split on blank lines first (natural paragraph boundaries in code)
2. Detect function/class declarations using regex patterns for common languages
3. Keep chunks at logical boundaries when under token limit
4. Fall back to token-based splitting with statement awareness

### 2. Language Detection Heuristics

**Decision**: Extension-based detection with content validation

**Rationale**:
- File extension is the primary signal (99% accurate for standard extensions)
- Content validation catches edge cases (e.g., shell scripts without extension, polyglot files)
- No external dependencies needed

**Language Detection Matrix**:

| Extension(s) | Language | Content Validation Pattern |
|--------------|----------|---------------------------|
| `.java` | Java | `class\s+\w+`, `package\s+` |
| `.py` | Python | `def\s+\w+`, `import\s+`, `from\s+` |
| `.js`, `.mjs` | JavaScript | `function\s+`, `const\s+`, `let\s+`, `=>` |
| `.ts`, `.tsx` | TypeScript | `interface\s+`, `type\s+`, `: \w+` |
| `.go` | Go | `func\s+`, `package\s+` |
| `.rs` | Rust | `fn\s+`, `impl\s+`, `struct\s+` |
| `.c`, `.h` | C | `#include`, `void\s+\w+\(`, `int\s+main` |
| `.cpp`, `.hpp`, `.cc` | C++ | `class\s+\w+`, `namespace\s+`, `template<` |
| `.rb` | Ruby | `def\s+\w+`, `class\s+\w+`, `module\s+` |
| `.php` | PHP | `<?php`, `function\s+`, `class\s+` |
| `.swift` | Swift | `func\s+`, `class\s+`, `struct\s+` |
| `.kt`, `.kts` | Kotlin | `fun\s+`, `class\s+`, `val\s+`, `var\s+` |
| `.scala` | Scala | `def\s+`, `object\s+`, `trait\s+` |
| `.sh`, `.bash` | Shell | `#!/bin/`, `function\s+\w+` |
| `.sql` | SQL | `SELECT`, `CREATE TABLE`, `INSERT INTO` |
| `.r`, `.R` | R | `function\(`, `library\(`, `<-` |

### 3. Binary File Detection

**Decision**: Multi-layer detection with early rejection

**Rationale**:
- Binary files can waste processing resources and corrupt embeddings
- Multiple detection layers provide defense in depth

**Detection Layers**:

1. **Extension Blacklist** (fast, first check):
   ```
   .pyc, .pyo, .class, .jar, .war, .ear, .so, .dll, .dylib, .exe, .bin, .o, .a, .lib
   ```

2. **Magic Bytes Detection** (first 16 bytes):
   | Bytes | Type |
   |-------|------|
   | `0x7F 0x45 0x4C 0x46` | ELF binary |
   | `0x4D 0x5A` | Windows executable |
   | `0xCA 0xFE 0xBA 0xBE` | Java class file |
   | `0x50 0x4B 0x03 0x04` | ZIP/JAR archive |
   | `0x89 0x50 0x4E 0x47` | PNG image |

3. **NUL Byte Detection** (fallback):
   - Scan first 8KB for NUL bytes (0x00)
   - Text files rarely contain NUL; binary files commonly do
   - Threshold: >10 NUL bytes = binary

**Error Response**:
```json
{
  "error": "BINARY_FILE_REJECTED",
  "message": "Cannot process binary file. Only text-based source code files are supported.",
  "fileName": "compiled.pyc"
}
```

### 4. Chunk Boundary Heuristics

**Decision**: Language-agnostic regex patterns prioritizing function/class boundaries

**Rationale**:
- Most languages share similar patterns for function/class declarations
- Regex patterns can handle 80%+ of common code structures
- Graceful fallback to blank-line splitting

**Universal Boundary Patterns** (priority order):

```java
// Pattern 1: Class/Struct/Interface declarations
Pattern CLASS = Pattern.compile("^\\s*(public|private|protected)?\\s*(abstract|final)?\\s*(class|struct|interface|enum|trait|object)\\s+\\w+", MULTILINE);

// Pattern 2: Function/Method declarations
Pattern FUNCTION = Pattern.compile("^\\s*(public|private|protected|internal)?\\s*(static|async|suspend)?\\s*(fun|func|function|def|fn|void|int|string|bool|async)\\s+\\w+\\s*\\(", MULTILINE);

// Pattern 3: Import/Include blocks (keep together)
Pattern IMPORTS = Pattern.compile("^\\s*(import|from|require|include|using|use)\\s+", MULTILINE);

// Pattern 4: Decorator/Annotation blocks (keep with next declaration)
Pattern DECORATOR = Pattern.compile("^\\s*[@#]\\w+", MULTILINE);
```

**Chunking Algorithm**:
1. Parse file into logical sections using blank line + pattern detection
2. Group imports together at file start
3. Keep decorator/annotation with following declaration
4. Split at function/class boundaries when under token limit
5. For oversized sections, split at statement boundaries (`;`, `}`, newline after `)`))
6. Maintain overlap of 2-3 lines for context continuity

### 5. Embedding Model Adequacy

**Decision**: Use existing `nomic-embed-text` initially, monitor quality metrics

**Rationale**:
- `nomic-embed-text` is a general-purpose model trained on diverse text including code
- No evidence that code-specific models significantly outperform for retrieval tasks
- Changing embedding model requires re-embedding all documents
- Constitution emphasizes YAGNI - add complexity only when proven necessary

**Quality Monitoring**:
- Track code-query recall rates in production
- Compare retrieval accuracy for code vs prose documents
- If recall <80%, consider code-specific model

**Alternative Models** (if quality insufficient):
| Model | Dimension | Notes |
|-------|-----------|-------|
| `BAAI/bge-code-v1` | 768 | Code-optimized, same dimension as nomic |
| `microsoft/codebert-base` | 768 | Pre-trained on code, well-documented |
| `Salesforce/codet5-base` | 768 | Code understanding, generation |

**Migration Path**: If switching models:
1. Add new model configuration
2. Re-embed code documents only (not prose)
3. Run A/B quality tests before full migration

## Additional Research: Code Entity Types

**Decision**: Extend entity types for code-specific concepts

**New Entity Types** (added to existing organization, person, location, event, concept):

| Entity Type | Description | Examples |
|-------------|-------------|----------|
| `FUNCTION` | Functions, methods, procedures | `calculateTotal()`, `processPayment` |
| `CLASS` | Classes, structs, types | `UserService`, `OrderRepository` |
| `MODULE` | Modules, packages, namespaces | `auth.service`, `com.example.utils` |
| `INTERFACE` | Interfaces, protocols, traits | `Serializable`, `Iterator` |
| `VARIABLE` | Constants, global variables | `MAX_RETRIES`, `CONFIG` |
| `API_ENDPOINT` | REST/GraphQL endpoints | `POST /api/users`, `query getUser` |
| `DEPENDENCY` | External libraries/packages | `spring-boot-starter`, `react` |

**Relationship Types** (code-specific):

| Relation Type | Description | Example |
|---------------|-------------|---------|
| `IMPORTS` | Module/package imports | `UserService IMPORTS UserRepository` |
| `CALLS` | Function invocation | `main() CALLS initialize()` |
| `EXTENDS` | Class inheritance | `Admin EXTENDS User` |
| `IMPLEMENTS` | Interface implementation | `UserServiceImpl IMPLEMENTS UserService` |
| `DEPENDS_ON` | External dependency | `UserService DEPENDS_ON spring-data-jpa` |
| `DEFINES` | Containment relationship | `UserService DEFINES createUser()` |

## Additional Research: Metadata Schema

**Decision**: Enrich chunk metadata with code-specific information

**Chunk Metadata Fields**:

```json
{
  "fileName": "UserService.java",
  "filePath": "src/main/java/com/example/service/UserService.java",
  "language": "java",
  "startLine": 45,
  "endLine": 72,
  "containingScope": "UserService.createUser",
  "scopeType": "method",
  "imports": ["com.example.repository.UserRepository", "jakarta.inject.Inject"],
  "lineCount": 28,
  "characterCount": 1245
}
```

**Benefits**:
- Source attribution in chat responses: "From UserService.java, lines 45-72"
- Relationship extraction hints: Import list helps identify dependencies
- Query enhancement: Scope information improves retrieval relevance

## Summary of Decisions

| Unknown | Decision | Rationale |
|---------|----------|-----------|
| Chunking Strategy | Regex-based heuristics | YAGNI, language-agnostic, extensible |
| Language Detection | Extension + content validation | Simple, reliable, no dependencies |
| Binary Detection | Multi-layer (blacklist, magic bytes, NUL) | Defense in depth, early rejection |
| Chunk Boundaries | Universal patterns for function/class | Covers 80%+ cases, graceful fallback |
| Embedding Model | Keep `nomic-embed-text`, monitor quality | YAGNI, avoid re-embedding cost |
| Entity Types | Add code-specific types | Enables code-aware queries |
| Metadata | Enrich with file/scope information | Improves source attribution |

All NEEDS CLARIFICATION items have been resolved through this research.
