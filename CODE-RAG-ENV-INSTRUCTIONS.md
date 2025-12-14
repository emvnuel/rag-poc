# Code Source RAG - Environment Variable Setup

## Quick Start (No Changes Needed!)

**Good news**: Code Source RAG works out-of-the-box with **zero configuration changes**! 

The system automatically:
- Detects code files by extension (.java, .py, .js, etc.)
- Chunks at function/class boundaries
- Extracts code-specific entities (functions, classes, methods)
- Detects programming language from file extension

## Optional Configuration

If you want to customize behavior, add these to your `.env` file:

```bash
# =============================================================================
# Code Source RAG Configuration
# =============================================================================

# Enable/disable code file processing
LIGHTRAG_CODE_EXTRACTION_ENABLED=true

# Code Extraction Prompts (Optional - uses comprehensive defaults if not specified)
# System prompt for code entity extraction
# Supports placeholders: {entity_types}, {relationship_types}, {language}
# Leave empty to use default comprehensive prompt
LIGHTRAG_CODE_EXTRACTION_SYSTEM_PROMPT=""

# User prompt for code extraction
# Supports placeholder: {input_text}
# Leave empty to use default
LIGHTRAG_CODE_EXTRACTION_USER_PROMPT=""

# Binary file detection threshold in bytes (default: 8192)
LIGHTRAG_CODE_BINARY_CHECK_SIZE=8192

# Code-specific entity types - comma-separated (default below)
LIGHTRAG_CODE_ENTITY_TYPES=function,class,module,interface,variable,api_endpoint,dependency,type,method,constant,enum,struct,trait
```

## What's Already Configured

These settings in `src/main/resources/application.properties` control Code Source RAG:

```properties
# Lines 323-346: Code Source RAG Configuration
lightrag.code.extraction.enabled=${LIGHTRAG_CODE_EXTRACTION_ENABLED:true}
lightrag.code.binary.check.size=${LIGHTRAG_CODE_BINARY_CHECK_SIZE:8192}
lightrag.entity.types.code=${LIGHTRAG_CODE_ENTITY_TYPES:function,class,module,interface,variable,api_endpoint,dependency,type,method,constant,enum,struct,trait}
```

## How It Works (Automatic)

### 1. Upload a Code File

```bash
curl -X POST http://localhost:8080/documents/files \
  -F "file=@UserService.java" \
  -F "projectId=<your-project-uuid>"
```

### 2. Automatic Processing

The system detects it's a code file and:
- **Chunking**: Uses `CodeChunker` (respects function/class boundaries)
- **Language**: Detects "Java" from `.java` extension
- **Entities**: Extracts classes, methods, variables, imports
- **Relationships**: Extracts calls, imports, inherits, implements

### 3. Query Your Code

```bash
curl "http://localhost:8080/projects/<id>/chat?q=How+does+UserService+work&mode=hybrid"
```

Response includes:
- Code snippets with line numbers (e.g., `UserService.java:10-25`)
- Function signatures and class definitions
- Dependency relationships and call graphs

## Supported Languages (25+)

Automatically detected from file extensions:

| Language | Extensions |
|----------|------------|
| Java | .java |
| Python | .py |
| JavaScript | .js, .mjs, .cjs |
| TypeScript | .ts |
| Go | .go |
| Rust | .rs |
| C | .c |
| C++ | .cpp, .cc, .cxx |
| C# | .cs |
| Ruby | .rb |
| PHP | .php |
| Swift | .swift |
| Kotlin | .kt, .kts |
| Scala | .scala |
| Shell | .sh, .bash |
| SQL | .sql |
| R | .r |
| Lua | .lua |
| Perl | .pl |

## Advanced Customization

### Custom Entity Types

If you need domain-specific extraction:

```bash
# Add to .env
LIGHTRAG_CODE_ENTITY_TYPES=function,class,controller,service,repository,dto,entity,bean
```

### Larger Code Files

For large classes/modules, increase chunk size:

```bash
# Add to .env
LIGHTRAG_CHUNK_SIZE=2400  # Default is 1200 tokens
LIGHTRAG_CHUNK_OVERLAP=150  # Default is 100 tokens
```

### Debug Logging

Enable detailed logging for troubleshooting:

```bash
# Add to .env or application.properties
quarkus.log.category."br.edu.ifba.document.CodeChunker".level=DEBUG
quarkus.log.category."br.edu.ifba.lightrag.core.CodeExtractionPrompts".level=DEBUG
quarkus.log.category."br.edu.ifba.document.CodeDocumentExtractor".level=DEBUG
```

## Integration Points

### Files Modified

1. **LightRAGService.java** - Added CodeChunker injection and document type parameter
2. **DocumentProcessorJob.java** - Passes document type to LightRAG
3. **LightRAG.java** - Core integration:
   - Uses CodeChunker for CODE documents (line 648-680)
   - Uses CodeExtractionPrompts for CODE documents (line 881-927)
4. **application.properties** - Configuration defaults (lines 323-346)

### Key Classes

- **CodeChunker** (`document/CodeChunker.java`) - Respects function/class boundaries
- **CodeExtractionPrompts** (`lightrag/core/CodeExtractionPrompts.java`) - Code-specific entity extraction
- **CodeDocumentExtractor** (`document/CodeDocumentExtractor.java`) - Auto-detects CODE type
- **BinaryFileDetector** (`document/BinaryFileDetector.java`) - Rejects binary files
- **LanguageDetector** (`document/LanguageDetector.java`) - Detects programming language

## Troubleshooting

### Code File Not Detected as CODE

Check the file extension is supported:
```bash
# View supported extensions in CodeDocumentExtractor.java
grep -A 30 "private static final Set<String> CODE_EXTENSIONS" src/main/java/br/edu/ifba/document/CodeDocumentExtractor.java
```

### Binary File Rejected

This is correct behavior! Binary files (.class, .jar, .exe) are automatically rejected:
```
Error: "Binary file detected and cannot be processed as text"
```

### Chunks Split Mid-Function

Check chunk size is large enough for your functions:
```bash
# Increase in .env
LIGHTRAG_CHUNK_SIZE=2400
```

### Missing Code Entities

Enable gleaning for better extraction:
```bash
# Add to .env (default: true, max-passes: 1)
LIGHTRAG_GLEANING_ENABLED=true
LIGHTRAG_GLEANING_MAX_PASSES=2  # More thorough but slower
```

## Testing

### Manual Test (Recommended)

1. Start application: `./mvnw quarkus:dev`
2. Upload Java file: See "Upload a Code File" above
3. Verify type: Check database for `type='CODE'`
4. Query: See "Query Your Code" above

### Check Logs

Look for these log messages:
```
Using code-aware chunking for document <uuid> (fileName: UserService.java)
Document <uuid> code-chunked into 5 pieces
Using code extraction prompts for chunk <uuid> (language: Java)
```

## Summary

- ✅ **Zero configuration required** - Works out-of-the-box
- ✅ **25+ languages supported** - Auto-detected from extension
- ✅ **Smart chunking** - Respects function/class boundaries
- ✅ **Code-aware extraction** - Functions, classes, dependencies
- ✅ **3 optional settings** - Only if you need customization

Just upload your code files and start querying! 🚀

## See Also

- Full implementation: `specs/010-code-source-rag/`
- Detailed documentation: `.env.code-rag-additions`
- Architecture: `AGENTS.md`
