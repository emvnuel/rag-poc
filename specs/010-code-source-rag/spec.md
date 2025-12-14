# Feature Specification: Code Source RAG

**Feature Branch**: `010-code-source-rag`  
**Created**: 2025-12-13  
**Status**: Draft  
**Input**: User description: "Enable RAG on code sources, all langs in a generic way"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Upload and Query Code Files (Priority: P1)

As a developer, I want to upload source code files to the RAG system so that I can ask questions about the codebase and receive contextually accurate answers that reference specific code locations.

**Why this priority**: This is the core value proposition - without the ability to ingest and query code, the feature has no utility. It enables developers to get instant answers about unfamiliar codebases.

**Independent Test**: Can be fully tested by uploading a source code file (e.g., a Java class) and asking "What does this code do?" - the system should return a coherent answer referencing the code structure.

**Acceptance Scenarios**:

1. **Given** a project exists, **When** a user uploads a Python file (.py), **Then** the system accepts the file and begins processing it for RAG indexing.
2. **Given** a code file has been processed, **When** a user asks "What functions are defined in this file?", **Then** the response references actual function names from the uploaded code.
3. **Given** multiple code files have been uploaded, **When** a user asks about a specific concept, **Then** the system retrieves relevant code snippets from the appropriate files.

---

### User Story 2 - Preserve Code Structure in Responses (Priority: P1)

As a developer, I want the RAG system to preserve code structure and formatting in its responses so that code snippets are readable and can be copied directly into my editor.

**Why this priority**: Code readability is essential for developer productivity. Mangled code formatting renders the feature unusable for practical development tasks.

**Independent Test**: Upload a code file with proper indentation and formatting, ask a question that retrieves code, verify the response maintains proper indentation and syntax.

**Acceptance Scenarios**:

1. **Given** a code file with proper indentation is indexed, **When** code snippets are included in a response, **Then** the original indentation and formatting are preserved.
2. **Given** a code file contains special characters (e.g., regex patterns, string literals), **When** retrieved as context, **Then** special characters are not corrupted or escaped incorrectly.

---

### User Story 3 - Understand Code Context and Relationships (Priority: P2)

As a developer, I want the system to understand code relationships (imports, function calls, class hierarchies) so that I can ask questions about how different parts of the code interact.

**Why this priority**: Understanding code relationships is what makes code-aware RAG more valuable than simple text search. This enables questions like "What calls this function?" or "What does this class depend on?"

**Independent Test**: Upload multiple related code files (e.g., a class and its imports), ask about dependencies between them, verify the response correctly identifies relationships.

**Acceptance Scenarios**:

1. **Given** code files with import statements are indexed, **When** a user asks "What modules does X depend on?", **Then** the system identifies imported modules/packages.
2. **Given** code with function calls across files is indexed, **When** a user asks about function usage, **Then** the system can trace call relationships.
3. **Given** code with class inheritance is indexed, **When** a user asks about class hierarchy, **Then** the system identifies parent/child class relationships.

---

### User Story 4 - Language-Agnostic Code Handling (Priority: P2)

As a user with a polyglot codebase, I want to upload code in any programming language so that I don't need to worry about whether my specific language is supported.

**Why this priority**: Modern projects often use multiple languages. Restricting to specific languages would limit adoption and force users to maintain separate tools.

**Independent Test**: Upload files in different languages (Java, Python, JavaScript, Go, Rust), verify all are accepted and processed, query across all of them.

**Acceptance Scenarios**:

1. **Given** a project exists, **When** a user uploads files with common extensions (.java, .py, .js, .ts, .go, .rs, .cpp, .c, .rb, .php, .swift, .kt), **Then** all files are accepted and processed.
2. **Given** a project contains code in multiple languages, **When** a user queries the project, **Then** relevant results from any language are returned.
3. **Given** an unknown file extension that appears to contain code, **When** uploaded, **Then** the system attempts to process it as plain text code.

---

### User Story 5 - Code-Aware Chunking (Priority: P3)

As a developer, I want the system to chunk code intelligently (by functions, classes, or logical blocks) so that retrieved context is coherent and not split mid-statement.

**Why this priority**: Naive character-based chunking can split code mid-function or mid-statement, making retrieved context confusing. Smart chunking improves answer quality significantly.

**Independent Test**: Upload a file with multiple functions, verify that chunks align with function boundaries when possible.

**Acceptance Scenarios**:

1. **Given** a file with multiple function definitions, **When** the file is chunked for indexing, **Then** chunk boundaries prefer to align with function/method boundaries where possible.
2. **Given** a very long function that exceeds chunk size, **When** chunked, **Then** splits occur at logical points (e.g., after complete statements) rather than mid-token.
3. **Given** a file with class definitions, **When** chunked, **Then** class-level context (class name, docstring) is preserved in chunks containing class methods.

---

### Edge Cases

- What happens when a binary file is uploaded with a code extension (e.g., compiled .pyc)?
  - System should detect non-text content and reject with a clear error message.
  
- How does the system handle very large code files (e.g., generated code, minified JavaScript)?
  - System should process them but may split into many chunks; configurable size limits should apply.
  
- What happens with code files that have no extension or unusual extensions?
  - System should attempt text-based processing; if content appears to be text, process it.
  
- How are code comments handled?
  - Comments should be preserved and indexed as they often contain valuable documentation.
  
- What happens with mixed-content files (e.g., Jupyter notebooks, Markdown with code blocks)?
  - System should extract and index code blocks separately while maintaining document context.

## Requirements *(mandatory)*

### Functional Requirements

#### File Ingestion
- **FR-001**: System MUST accept source code files for upload and RAG processing.
- **FR-002**: System MUST support common programming language file extensions including but not limited to: .java, .py, .js, .ts, .jsx, .tsx, .go, .rs, .c, .cpp, .h, .hpp, .cs, .rb, .php, .swift, .kt, .scala, .sh, .bash, .sql, .r, .lua, .pl, .pm.
- **FR-003**: System MUST detect and reject binary files uploaded with code extensions, providing a clear error message.
- **FR-004**: System MUST preserve the original filename and extension as metadata for each uploaded code file.
- **FR-005**: System MUST support configuration of maximum file size limits for code uploads.

#### Text Extraction
- **FR-006**: System MUST extract the full text content from code files while preserving:
  - Original indentation (spaces and tabs)
  - Line breaks
  - Special characters and escape sequences
  - Unicode characters and encoding
- **FR-007**: System MUST extract code-specific metadata including: file extension, detected language (when determinable), line count, and character count.
- **FR-008**: System MUST handle files with different character encodings (UTF-8, UTF-16, ASCII, ISO-8859-1) and convert to a standard internal format.

#### Chunking
- **FR-009**: System MUST chunk code files for vector indexing while maintaining code readability.
- **FR-010**: System SHOULD attempt to align chunk boundaries with logical code boundaries (functions, classes, blocks) when the language structure can be detected.
- **FR-011**: System MUST NOT split chunks mid-token or mid-string-literal when avoidable.
- **FR-012**: System MUST preserve sufficient context in each chunk to identify the file, and where applicable, the containing class/function name.
- **FR-013**: System MUST support configurable chunk size and overlap parameters for code files.

#### Knowledge Extraction
- **FR-014**: System MUST extract entities from code including: function/method names, class/type names, module/package names, and significant variable names.
- **FR-015**: System MUST extract relationships from code including: imports/dependencies, function calls, class inheritance, and interface implementations.
- **FR-016**: System MUST include code comments and docstrings as part of entity descriptions when present.
- **FR-017**: System MUST associate extracted entities with their source file and approximate location (line number range or chunk reference).

#### Querying
- **FR-018**: System MUST return code file chunks as part of RAG context when relevant to user queries.
- **FR-019**: System MUST format code snippets in responses to preserve original indentation and structure.
- **FR-020**: System MUST support queries that reference programming concepts (e.g., "functions that handle authentication", "classes that implement interface X").
- **FR-021**: System MUST include source file attribution (filename, approximate location) when returning code snippets.

### Key Entities

- **CodeFile**: Represents an uploaded source code file. Attributes include: filename, extension, detected language, encoding, line count, content hash, and upload timestamp. Related to Project (belongs to) and CodeChunk (contains many).

- **CodeChunk**: A segment of code extracted for vector indexing. Attributes include: content text, start/end line numbers, containing scope (class/function name if applicable), and chunk index within file. Related to CodeFile (belongs to) and Vector (has one embedding).

- **CodeEntity**: A meaningful element extracted from code (function, class, module). Attributes include: name, entity type (function/class/module/variable), description (from docstring/comments), source file reference, and line number. Related to CodeFile (extracted from) and CodeRelation (participates in).

- **CodeRelation**: A relationship between code entities. Attributes include: relationship type (imports, calls, extends, implements), source entity, target entity, and evidence (the code snippet showing the relationship). Related to CodeEntity (connects two entities).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can upload a source code file and receive a queryable index within 30 seconds for files under 10,000 lines.
- **SC-002**: 90% of code-related queries return at least one relevant code snippet from the indexed files.
- **SC-003**: Retrieved code snippets maintain original formatting (indentation, line breaks) with 100% fidelity.
- **SC-004**: System successfully processes files in at least 15 different programming languages without requiring language-specific configuration by the user.
- **SC-005**: Users can identify the source file and approximate location of any returned code snippet.
- **SC-006**: Queries about code relationships (e.g., "what calls function X") return accurate results in 80% of cases where such relationships exist.
- **SC-007**: System handles codebases of up to 1,000 files per project without significant query latency increase (queries complete within 5 seconds).

## Assumptions

- Users primarily work with text-based source code files, not compiled binaries or bytecode.
- The existing document processing pipeline (chunking, embedding, vector storage) can be extended for code files.
- The LLM used for entity extraction can identify code-specific entities (functions, classes, imports) from code snippets.
- Standard programming language patterns (function definitions, class declarations, import statements) are consistent enough within each language to enable relationship extraction.
- Users expect code formatting preservation but do not require syntax highlighting in text responses.
- Configurable chunk sizes (inherited from existing system) are appropriate for code with reasonable defaults (e.g., 1200 characters with 100 character overlap).
