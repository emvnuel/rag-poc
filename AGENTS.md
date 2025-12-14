# Agent Guidelines for rag-saas

## Build/Test/Run Commands
- **Build**: `./mvnw package` or `./mvnw clean package`
- **Test all**: `./mvnw test`
- **Test single**: `./mvnw test -Dtest=ExampleResourceTest` or `./mvnw test -Dtest=ExampleResourceTest#testHelloEndpoint`
- **Integration tests**: `./mvnw verify -DskipITs=false`

## Project Info
- **Framework**: Quarkus 3.28.4 with Jakarta REST (not JAX-RS/RESTEasy)
- **Java version**: 21
- **Package**: `br.edu.ifba`

## Code Style
- **Imports**: Jakarta (`jakarta.ws.rs.*`), not javax. Standard imports before third-party. NEVER use inline imports (e.g., avoid `import br.edu.ifba.lightrag.core.EntityResolver; import br.edu.ifba.lightrag.core.DeduplicationConfig;` on single lines). Always use separate lines for each import.
- **Annotations**: Use `@Path`, `@GET/@POST`, `@Produces`, `@QuarkusTest` for tests
- **Testing**: JUnit 5 + REST Assured. Pattern: `given().when().get("/path").then().statusCode(200).body(is("expected"))`
- **Naming**: Classes end with `Resource` for REST endpoints, `Test` for tests, `IT` for integration tests
- **Error handling**: Use `IllegalArgumentException` for validation, null checks before operations
- **Javadoc**: Include for public methods with `@param` and `@return` tags where helpful
- **Finals**: Use `final` for utility classes and immutable variables

## Entity Resolution & Deduplication

The system includes semantic entity deduplication to identify and merge duplicate entities in the knowledge graph.

### Configuration
Configure via `application.properties`:
```properties
# Enable/disable entity resolution
lightrag.deduplication.enabled=true

# Similarity threshold (0.0-1.0): higher = more strict
lightrag.deduplication.similarity.threshold=0.4

# Preset shortcuts (optional, overrides threshold):
# - strict: 0.6 (fewer merges, high confidence)
# - moderate: 0.4 (balanced)
# - aggressive: 0.2 (more merges, may over-merge)
lightrag.deduplication.similarity.preset=moderate

# Parallel processing
lightrag.deduplication.parallel.enabled=true
lightrag.deduplication.parallel.threads=4
lightrag.deduplication.batch.size=200

# Audit logging
lightrag.deduplication.log.merges=true
```

### Threshold Guidelines
- **0.6 (strict)**: High confidence matches only (e.g., "MIT" + "Massachusetts Institute of Technology")
- **0.4 (moderate)**: Balanced approach, good default (e.g., "Harvard" + "Harvard University")
- **0.2 (aggressive)**: Liberal merging, may over-merge (e.g., "University" + "The University")

### Common Issues & Troubleshooting

**Problem**: Deduplication rate >60% (too many merges)
- **Cause**: Threshold too low for dataset
- **Fix**: Increase threshold (0.4 → 0.6) or use `preset=strict`

**Problem**: Performance slow with large entity sets
- **Cause**: Sequential processing or small batch size
- **Fix**: Enable parallel processing (`parallel.enabled=true`) and increase batch size (200 → 500)

**Problem**: Related but distinct entities being merged (e.g., "Apple Inc." + "Apple Store")
- **Cause**: Threshold too low, type penalty too weak
- **Fix**: Increase threshold or ensure entities have correct types (ORGANIZATION vs LOCATION)

**Problem**: Obvious duplicates not merging (e.g., "MIT" + "M.I.T.")
- **Cause**: Threshold too high
- **Fix**: Lower threshold (0.6 → 0.4) or use `preset=moderate`

### Testing Commands
```bash
# Run entity resolution unit tests (33 tests)
./mvnw test -Dtest=EntityResolverTest

# Run deduplication integration tests (20 tests)
./mvnw verify -DskipITs=false -Dit.test=EntityDeduplicationIT

# Run performance benchmarks (5 tests)
./mvnw test -Dtest=EntityResolverPerformanceTest

# Run all entity resolution tests
./mvnw test -Dtest="Entity*Test"
```

### Key Components
- **EntityResolver** (`lightrag/core/EntityResolver.java`): Main orchestrator for deduplication
- **EntitySimilarityCalculator** (`lightrag/core/EntitySimilarityCalculator.java`): Computes multi-metric similarity scores
- **EntityClusterer** (`lightrag/core/EntityClusterer.java`): Groups similar entities using connected components
- **DeduplicationConfig** (`lightrag/core/DeduplicationConfig.java`): Configuration model with validation

### Similarity Metrics (see EntitySimilarityCalculator)
1. **String similarity** (50% weight): Levenshtein distance + token overlap
2. **Type matching** (30% weight): PERSON, ORGANIZATION, LOCATION, etc.
3. **Description overlap** (20% weight): TF-IDF weighted token matching

## Retry Logic with Exponential Backoff

The system includes automatic retry logic for transient database failures using SmallRye Fault Tolerance.

### Configuration
Configure via `application.properties`:
```properties
# SmallRye Fault Tolerance - Retry Configuration
smallrye.faulttolerance.global.retry.max-retries=3
smallrye.faulttolerance.global.retry.delay=200
smallrye.faulttolerance.global.retry.max-duration=30s
smallrye.faulttolerance.global.retry.jitter=100
smallrye.faulttolerance.global.retry.enabled=true
```

### Configuration Presets

**High Availability (more retries, longer delays)**
```properties
smallrye.faulttolerance.global.retry.max-retries=5
smallrye.faulttolerance.global.retry.delay=1000
smallrye.faulttolerance.global.retry.max-duration=60s
```

**Low Latency (fewer retries, faster failure)**
```properties
smallrye.faulttolerance.global.retry.max-retries=2
smallrye.faulttolerance.global.retry.delay=200
smallrye.faulttolerance.global.retry.max-duration=5s
```

### Transient vs Permanent Errors

**Transient Errors (will retry)**: SQLSTATE 08xxx (connection), 40xxx (deadlock), 53xxx (resources), 57xxx (operator)

**Permanent Errors (will NOT retry)**: SQLSTATE 23xxx (constraint), 42xxx (syntax/access)

### Testing Commands
```bash
# Run retry unit tests
./mvnw test -Dtest=TransientSQLExceptionPredicateTest
./mvnw test -Dtest=RetryEventLoggerTest

# Run retry integration tests
./mvnw verify -DskipITs=false -Dit.test=AgeGraphStorageRetryIT
./mvnw verify -DskipITs=false -Dit.test=PgVectorStorageRetryIT

# Run all retry-related tests
./mvnw test -Dtest="*Retry*,*Transient*"
```

### Key Components
- **TransientSQLExceptionPredicate** (`lightrag/utils/TransientSQLExceptionPredicate.java`): Classifies SQL exceptions as transient or permanent
- **RetryEventLogger** (`lightrag/utils/RetryEventLogger.java`): Structured logging for retry events with MDC context
- **AgeGraphStorage** (`lightrag/storage/impl/AgeGraphStorage.java`): Graph operations with `@Retry` annotations
- **PgVectorStorage** (`lightrag/storage/impl/PgVectorStorage.java`): Vector operations with `@Retry` annotations

### Common Issues & Troubleshooting

**Problem**: "Retry exhausted" in logs
- **Cause**: Database completely unavailable or network partition longer than retry window
- **Fix**: Check database status, increase `max-retries` or `max-duration`

**Problem**: Operations failing without retries
- **Cause**: Permanent error (constraint violation), or retry disabled
- **Fix**: Check SQLSTATE in logs, verify `retry.enabled=true`

**Problem**: Retries causing too much delay
- **Cause**: Too many retries configured for latency-sensitive operations
- **Fix**: Reduce `max-retries` to 2 and `max-duration` to 5s

## LightRAG Extraction & Query Configuration

The system implements LightRAG features aligned with the official HKUDS/LightRAG Python implementation.

### Configuration
Configure via `application.properties`:
```properties
# Gleaning (iterative extraction for missing entities)
lightrag.extraction.gleaning.enabled=true
lightrag.extraction.gleaning.max-loops=1

# Entity name limits
lightrag.extraction.entity.max-name-length=256

# Description summarization
lightrag.extraction.description.max-length=4096
lightrag.extraction.description.summarize-when-exceeded=true

# Query context token budgets
lightrag.extraction.query.context.max-tokens=4000
lightrag.extraction.query.context.entity-budget-ratio=0.4
lightrag.extraction.query.context.relation-budget-ratio=0.3
lightrag.extraction.query.context.chunk-budget-ratio=0.3

# Keyword extraction for queries
lightrag.extraction.query.keywords.high-level-count=5
lightrag.extraction.query.keywords.low-level-count=5
```

### Configuration Presets

**High Quality (more gleaning, detailed extraction)**
```properties
lightrag.extraction.gleaning.enabled=true
lightrag.extraction.gleaning.max-loops=3
lightrag.extraction.description.max-length=8192
lightrag.extraction.query.context.max-tokens=8000
```

**Fast Processing (minimal gleaning, lower tokens)**
```properties
lightrag.extraction.gleaning.enabled=false
lightrag.extraction.description.max-length=2048
lightrag.extraction.query.context.max-tokens=2000
```

### Key Features

**Gleaning**: Iterative extraction that re-prompts the LLM to find missed entities/relations
- Enabled by default with 1 loop
- Improves extraction completeness at the cost of additional LLM calls

**Entity Normalization**: Automatic cleanup of entity names
- Removes surrounding quotes (`"entity"` → `entity`)
- Truncates names exceeding `max-name-length`
- Normalizes whitespace

**Self-Loop Prevention**: Rejects relationships where source equals target

**Keyword Extraction**: Query analysis for smarter retrieval
- High-level keywords: thematic concepts for global/relation search
- Low-level keywords: specific entities for local/entity search

**Context Merging**: Round-robin merge of entities, relations, and chunks
- Respects per-type token budgets
- Balances context diversity

**Accurate Token Counting (jtokkit)**: Uses cl100k_base encoding for GPT-4 compatible token counting
- Exact token counting instead of character-based approximation
- Falls back to ~4 chars/token if jtokkit unavailable

**Persistent Keyword Cache**: Two-level caching for keyword extraction results
- L1: In-memory cache (5 min TTL) for hot queries
- L2: PostgreSQL extraction_cache table for persistence across restarts

### Testing Commands
```bash
# Run gleaning extraction tests (9 tests)
./mvnw test -Dtest=GleaningExtractionTest

# Run query mode enhancement tests (18 tests)
./mvnw test -Dtest=QueryModeEnhancementsTest

# Run token counting tests with jtokkit
./mvnw test -Dtest="*TokenUtil*"

# Run all LightRAG core tests
./mvnw test -Dtest="*LightRAG*,Gleaning*,QueryMode*"
```

### Key Components
- **LightRAGExtractionConfig** (`lightrag/core/LightRAGExtractionConfig.java`): Centralized configuration via `@ConfigMapping`
- **KeywordExtractor/LLMKeywordExtractor** (`lightrag/query/`): Query keyword extraction (in-memory cache)
- **PersistentKeywordExtractor** (`lightrag/query/PersistentKeywordExtractor.java`): Persistent keyword caching with PostgreSQL
- **ContextMerger** (`lightrag/query/ContextMerger.java`): Round-robin context merging with token budgets
- **TokenUtil** (`lightrag/utils/TokenUtil.java`): Token counting with jtokkit (cl100k_base encoding)
- **LLMDescriptionSummarizer** (`lightrag/core/LLMDescriptionSummarizer.java`): LLM-based description summarization with map-reduce
- **LocalQueryExecutor** (`lightrag/query/LocalQueryExecutor.java`): Entity-focused retrieval using low-level keywords
- **GlobalQueryExecutor** (`lightrag/query/GlobalQueryExecutor.java`): Relation-focused retrieval using high-level keywords
- **HybridQueryExecutor** (`lightrag/query/HybridQueryExecutor.java`): Combined retrieval with round-robin merging

### Common Issues & Troubleshooting

**Problem**: Extraction missing obvious entities
- **Cause**: Gleaning disabled or max-loops too low
- **Fix**: Enable gleaning (`gleaning.enabled=true`) and increase `max-loops` to 2-3

**Problem**: Entity names contain quotes or are truncated
- **Cause**: LLM output format issues
- **Fix**: Names are automatically normalized; increase `max-name-length` if needed

**Problem**: Query context too large/small
- **Cause**: Token budget misconfigured
- **Fix**: Adjust `max-tokens` and budget ratios (should sum to 1.0)

**Problem**: Hybrid queries not balanced
- **Cause**: One source type dominating context
- **Fix**: Adjust budget ratios (e.g., `entity-budget-ratio=0.3, relation-budget-ratio=0.4`)

## Reranker Integration

The system supports optional reranking of retrieved chunks using Cohere or Jina APIs.

### Configuration
Configure via `application.properties`:
```properties
# Reranker provider: cohere, jina, or none
lightrag.reranker.provider=cohere

# Cohere configuration
lightrag.reranker.cohere.api-key=${COHERE_API_KEY:}
lightrag.reranker.cohere.model=rerank-english-v3.0

# Jina configuration  
lightrag.reranker.jina.api-key=${JINA_API_KEY:}
lightrag.reranker.jina.model=jina-reranker-v1-base-en

# Minimum relevance score (0.0-1.0)
lightrag.reranker.min-score=0.0
```

### Usage
Add `?rerank=true` to chat queries to enable reranking:
```bash
curl "http://localhost:8080/projects/{id}/chat?q=your+query&rerank=true"
```

### Key Components
- **CohereReranker** (`lightrag/rerank/CohereReranker.java`): Cohere API integration with circuit breaker
- **JinaReranker** (`lightrag/rerank/JinaReranker.java`): Jina API integration with circuit breaker
- **NoOpReranker** (`lightrag/rerank/NoOpReranker.java`): Fallback when reranking disabled/unavailable
- **RerankerFactory** (`lightrag/rerank/RerankerFactory.java`): Selects provider based on config

### Common Issues & Troubleshooting

**Problem**: Reranking returning fewer chunks than expected
- **Cause**: `min-score` threshold filtering out low-relevance chunks
- **Fix**: Lower `min-score` (e.g., 0.0 to include all)

**Problem**: Circuit breaker open, falling back to no-op
- **Cause**: Multiple API failures (timeout, rate limit, invalid key)
- **Fix**: Check API key validity, increase timeout if needed

## Document Deletion with KG Regeneration

The system supports intelligent document deletion that rebuilds affected knowledge graph entities.

### Usage
```bash
# Delete document and rebuild affected entities
DELETE /projects/{projectId}/documents/{documentId}

# Delete without rebuilding (faster but may leave orphan data)
DELETE /projects/{projectId}/documents/{documentId}?skipRebuild=true
```

### Key Components
- **DocumentDeletionService** (`lightrag/deletion/DocumentDeletionService.java`): Service interface
- **DocumentDeletionServiceImpl** (`lightrag/deletion/DocumentDeletionServiceImpl.java`): Implementation with rebuild logic
- **EntityRebuildStrategy** (`lightrag/deletion/EntityRebuildStrategy.java`): Classifies entities for delete vs rebuild

### Rebuild Logic
1. Entities only sourced from deleted document → **FULL_DELETE**
2. Entities with multiple sources → **REBUILD** (uses cached extractions)

## Entity Merge Operations

The system supports merging duplicate entities with relationship redirection.

### Configuration
Merge strategies available:
- `CONCATENATE` - Join descriptions with separator
- `KEEP_FIRST` - Use first description
- `KEEP_LONGEST` - Use longest description
- `LLM_SUMMARIZE` - Use LLM to synthesize descriptions

### Usage
```bash
POST /entities/merge
{
  "projectId": "uuid",
  "sourceEntities": ["Entity A", "Entity B"],
  "targetEntity": "Entity A",
  "strategy": "LLM_SUMMARIZE"
}
```

### Key Components
- **EntityMergeService** (`lightrag/merge/EntityMergeService.java`): Service interface
- **EntityMergeServiceImpl** (`lightrag/merge/EntityMergeServiceImpl.java`): Implementation with self-loop prevention
- **RelationshipRedirector** (`lightrag/merge/RelationshipRedirector.java`): Handles relation redirection and deduplication

### Self-Loop Prevention
When merging A into B, relations like A→B become B→B (self-loops) and are automatically filtered out.

## Knowledge Graph Export

The system supports exporting knowledge graphs in multiple formats.

### Usage
```bash
# Export as CSV (default)
GET /projects/{projectId}/export

# Export as Excel
GET /projects/{projectId}/export?format=excel

# Export as Markdown
GET /projects/{projectId}/export?format=markdown

# Export as plain text
GET /projects/{projectId}/export?format=text

# Export only entities
GET /projects/{projectId}/export?entities=true&relations=false
```

### Supported Formats
| Format | MIME Type | Extension |
|--------|-----------|-----------|
| CSV | text/csv | .csv |
| Excel | application/vnd.openxmlformats-officedocument.spreadsheetml.sheet | .xlsx |
| Markdown | text/markdown | .md |
| Text | text/plain | .txt |

### Key Components
- **GraphExporter** (`lightrag/export/GraphExporter.java`): Export interface
- **CsvGraphExporter** (`lightrag/export/CsvGraphExporter.java`): RFC 4180 compliant CSV
- **ExcelGraphExporter** (`lightrag/export/ExcelGraphExporter.java`): Streaming Excel with SXSSFWorkbook
- **MarkdownGraphExporter** (`lightrag/export/MarkdownGraphExporter.java`): Markdown tables
- **TextGraphExporter** (`lightrag/export/TextGraphExporter.java`): Human-readable plain text
- **ExportResources** (`lightrag/export/ExportResources.java`): REST endpoint

## Token Usage Tracking

The system tracks token consumption for all LLM operations and exposes it via response headers.

### Response Headers
- `X-Token-Input` - Total input tokens
- `X-Token-Output` - Total output tokens  
- `X-Token-Total` - Sum of input + output
- `X-Token-Operations` - Number of LLM calls

### Key Components
- **TokenTracker** (`lightrag/core/TokenTracker.java`): Interface for tracking
- **TokenTrackerImpl** (`lightrag/core/TokenTrackerImpl.java`): Request-scoped implementation
- **TokenUsageFilter** (`lightrag/core/TokenUsageFilter.java`): Adds headers to responses

## Chunk Selection Strategies

The system supports multiple strategies for selecting chunks during retrieval.

### Strategies
- **VECTOR** (default): Pure vector similarity search
- **WEIGHTED**: Boosts chunks connected to relevant entities/relations

### Key Components
- **ChunkSelector** (`lightrag/query/ChunkSelector.java`): Strategy interface
- **VectorChunkSelector** (`lightrag/query/VectorChunkSelector.java`): Default similarity-based
- **WeightedChunkSelector** (`lightrag/query/WeightedChunkSelector.java`): Entity-aware weighting
- **ChunkSelectorFactory** (`lightrag/query/ChunkSelectorFactory.java`): Factory for selection

## Graph Traversal

The system supports BFS (Breadth-First Search) traversal for exploring the knowledge graph.

### Methods
- **traverse(projectId, startEntity, maxDepth)**: Simple traversal with depth limit
- **traverseBFS(projectId, startEntity, maxDepth, maxNodes)**: Advanced BFS with node limit

### Usage
```java
// Traverse from entity with depth limit
GraphSubgraph subgraph = graphStorage.traverse(projectId, "Apple Inc.", 2).join();

// Traverse with node limit (prevents memory exhaustion on large graphs)
GraphSubgraph limited = graphStorage.traverseBFS(projectId, "Apple Inc.", 5, 100).join();
```

### Key Features
- Level-by-level BFS traversal ensures breadth-first ordering
- `maxNodes` parameter prevents memory exhaustion on large graphs
- Batch neighbor queries per level (more efficient than per-node queries)
- Proper cycle detection to avoid infinite loops
- Single database connection reused throughout traversal

### Key Components
- **GraphStorage.traverseBFS()** (`lightrag/storage/GraphStorage.java`): Interface method
- **AgeGraphStorage.traverseBFS()** (`lightrag/storage/impl/AgeGraphStorage.java`): PostgreSQL/AGE implementation
- **InMemoryGraphStorage.traverseBFS()** (`lightrag/storage/impl/InMemoryGraphStorage.java`): In-memory implementation

## SQLite Storage Backend

The system supports SQLite as an alternative storage backend for local development, edge deployment, and portable knowledge bases.

### Configuration
Configure via `application.properties`:
```properties
# Enable SQLite backend (instead of PostgreSQL)
lightrag.storage.backend=sqlite

# Database file path
lightrag.storage.sqlite.path=data/rag.db

# Connection pool settings
lightrag.storage.sqlite.read-pool-size=4
lightrag.storage.sqlite.busy-timeout=30000
lightrag.storage.sqlite.wal-mode=true

# Vector settings (shared with PostgreSQL backend)
lightrag.vector.dimension=768
lightrag.vector.table.name=vectors
```

**Note**: Vector configuration (`lightrag.vector.dimension`, `lightrag.vector.table.name`) is now unified across both PostgreSQL and SQLite backends. This allows switching backends without changing vector configuration.

### Configuration Presets

**Local Development (default)**
```properties
lightrag.storage.backend=sqlite
lightrag.storage.sqlite.path=data/rag.db
lightrag.storage.sqlite.read-pool-size=4
```

**Edge Deployment (256MB memory limit)**
```properties
lightrag.storage.backend=sqlite
lightrag.storage.sqlite.path=data/rag.db
lightrag.storage.sqlite.read-pool-size=2
# Uses smaller cache and disables mmap for low memory
```

**In-Memory (testing)**
```properties
lightrag.storage.backend=sqlite
lightrag.storage.sqlite.path=:memory:
```

### Key Features

**Backend Switching**: Switch between PostgreSQL and SQLite via configuration only
- Set `lightrag.storage.backend=sqlite` or `lightrag.storage.backend=postgresql`
- All storage interfaces work identically with either backend

**Project Isolation**: Multi-project support with complete isolation
- All queries filter by `project_id`
- Foreign key cascade delete when project is removed

**Export/Import**: Portable knowledge base files
```bash
# Export project to standalone SQLite file
GET /sqlite/export/{projectId}

# Import from SQLite file
POST /sqlite/import
Content-Type: multipart/form-data
file=@exported.db
```

**Edge Deployment Optimizations**:
- Smaller batch sizes for low memory usage
- Configurable cache size and mmap settings
- Connection pooling limits

### Testing Commands
```bash
# Run all SQLite unit tests (170+ tests)
./mvnw test -Dtest="SQLite*"

# Run SQLite integration tests
./mvnw test -Dtest="SQLite*IT"

# Run project isolation tests
./mvnw test -Dtest="SQLiteProjectIsolation*"

# Run with SQLite profile
./mvnw quarkus:dev -Dquarkus.profile=sqlite
```

### Key Components
- **SQLiteStorageProvider** (`lightrag/storage/impl/SQLiteStorageProvider.java`): CDI producer for storage beans
- **SQLiteConnectionManager** (`lightrag/storage/impl/SQLiteConnectionManager.java`): Connection pooling with WAL mode
- **SQLiteVectorStorage** (`lightrag/storage/impl/SQLiteVectorStorage.java`): Vector similarity search
- **SQLiteGraphStorage** (`lightrag/storage/impl/SQLiteGraphStorage.java`): Entity/relation storage with BFS traversal
- **SQLiteExtractionCacheStorage** (`lightrag/storage/impl/SQLiteExtractionCacheStorage.java`): LLM extraction caching
- **SQLiteKVStorage** (`lightrag/storage/impl/SQLiteKVStorage.java`): Key-value storage
- **SQLiteDocStatusStorage** (`lightrag/storage/impl/SQLiteDocStatusStorage.java`): Document processing status
- **SQLiteExportService** (`lightrag/storage/impl/SQLiteExportService.java`): Project export/import
- **SQLiteSchemaMigrator** (`lightrag/storage/impl/SQLiteSchemaMigrator.java`): Schema version management

### Common Issues & Troubleshooting

**Problem**: "Database is locked" errors
- **Cause**: Multiple writers or long-running transactions
- **Fix**: Increase `busy-timeout` (default 30s), ensure WAL mode enabled

**Problem**: Slow vector queries with large datasets
- **Cause**: Linear scan (no ANN index in SQLite)
- **Fix**: For >100K vectors, consider PostgreSQL with pgvector HNSW index

**Problem**: Application fails to start with SQLite
- **Cause**: Missing schema or wrong backend configuration
- **Fix**: Verify `lightrag.storage.backend=sqlite` is set, check database file permissions

**Problem**: Project data not isolated
- **Cause**: Should not happen - all queries filter by project_id
- **Fix**: Report as bug if cross-project data leakage occurs

### Database Schema

The SQLite schema mirrors PostgreSQL with these tables:
- `projects` - Project metadata
- `documents` - Document metadata with FK to projects
- `vectors` - Vector embeddings with FK to projects/documents
- `graph_entities` - Knowledge graph entities with FK to projects
- `graph_relations` - Knowledge graph relations with FK to projects
- `extraction_cache` - LLM extraction cache with FK to projects
- `kv_store` - General key-value storage
- `document_status` - Document processing status
- `schema_version` - Migration tracking

All project-related tables have `ON DELETE CASCADE` for automatic cleanup.

## Code Source RAG

The system supports uploading and querying source code files across 100+ programming languages.

### Supported Languages

**Primary Languages** (15+ with content validation):
- Java, Python, JavaScript, TypeScript, Go, Rust, C, C++, C#, Ruby, PHP, Swift, Kotlin, Scala, Shell, SQL, R

**Extended Support** (100+ file extensions):
- JVM: Java, Kotlin, Scala, Groovy, Clojure
- JavaScript/TypeScript: JS, TS, JSX, TSX, Vue, Svelte  
- Systems: Rust, Go, C, C++, Zig
- Scripting: Python, Ruby, PHP, Perl, Lua, Shell
- Functional: Haskell, OCaml, Erlang, Elixir, Lisp, Scheme
- Mobile: Swift, Dart, Objective-C
- Data Science: R, Julia
- Other: Fortran, COBOL, Pascal, Nim, Crystal, PowerShell, Solidity

### Configuration

Configure via `application.properties` and `.env`:
```properties
# Code Document Extraction
lightrag.code.extraction.enabled=${LIGHTRAG_CODE_EXTRACTION_ENABLED:true}

# Binary file detection (check first N bytes)
lightrag.code.binary.check.size=${LIGHTRAG_CODE_BINARY_CHECK_SIZE:8192}

# Code entity and relationship types
lightrag.entity.types.code=${LIGHTRAG_CODE_ENTITY_TYPES:function,class,module,...}
lightrag.relationship.types.code=${LIGHTRAG_CODE_RELATIONSHIP_TYPES:calls,imports,inherits,...}

# Code Extraction Prompts (Optional - uses comprehensive defaults if not specified)
# System prompt with placeholders: {entity_types}, {relationship_types}, {language}
lightrag.code.extraction.system.prompt=${LIGHTRAG_CODE_EXTRACTION_SYSTEM_PROMPT:...}
# User prompt with placeholder: {input_text}
lightrag.code.extraction.user.prompt=${LIGHTRAG_CODE_EXTRACTION_USER_PROMPT:...}
```

**Note**: Prompts are optional. The system includes comprehensive default prompts optimized for code analysis. Override via environment variables only if you need domain-specific customization.

### Key Features

**Language Detection**: Automatic detection via file extension + content validation
- Extension-based detection (99% accuracy)
- Content pattern validation for verification
- Supports 100+ file extensions

**Binary File Rejection**: Multi-layer detection prevents binary files from being processed
- Extension blacklist (.pyc, .class, .jar, .so, .dll, etc.)
- Magic bytes detection (ELF, MZ, CAFEBABE, PK, PNG)
- NUL byte frequency analysis

**Code-Aware Chunking**: Respects logical code boundaries
- Aligns chunks with function/class declarations when possible
- Groups imports together at file start
- Preserves decorators/annotations with declarations
- Falls back to statement boundaries for oversized functions
- Maintains exact indentation and formatting

**Metadata Extraction**: Rich code-specific metadata
- Language, line count, character count
- Import statements
- Top-level declarations (classes, functions, interfaces)
- File encoding (UTF-8, UTF-16, ISO-8859-1)

**Code Entity Types**: Extended entity types for code
- FUNCTION, CLASS, MODULE, INTERFACE, VARIABLE
- API_ENDPOINT, DEPENDENCY

**Code Relationship Types**: Code-specific relationships
- IMPORTS, CALLS, EXTENDS, IMPLEMENTS
- DEFINES, DEPENDS_ON, RETURNS, ACCEPTS

### Testing Commands

```bash
# Run all code-related unit tests
./mvnw test -Dtest="*Code*,*Language*,*Binary*"

# Run specific code extractor tests
./mvnw test -Dtest=CodeDocumentExtractorTest
./mvnw test -Dtest=CodeChunkerTest
./mvnw test -Dtest=LanguageDetectorTest
./mvnw test -Dtest=BinaryFileDetectorTest

# Run code integration tests
./mvnw test -Dtest=CodeQueryIT

# Run all code RAG tests (unit + integration)
./mvnw test -Dtest="*Code*"
```

### Key Components

- **CodeDocumentExtractor** (`document/CodeDocumentExtractor.java`): Main extractor for code files
- **LanguageDetector** (`document/LanguageDetector.java`): Language detection from extension + content
- **BinaryFileDetector** (`document/BinaryFileDetector.java`): Binary file detection and rejection
- **CodeChunker** (`document/CodeChunker.java`): Code-aware chunking with boundary detection
- **CodeExtractionPrompts** (`document/CodeExtractionPrompts.java`): LLM prompts for code entity extraction

### Usage

Upload code files through the standard document upload endpoint:

```bash
# Upload a code file
curl -X POST "http://localhost:8080/api/v1/projects/${PROJECT_ID}/documents" \
  -F "file=@UserService.java"

# Query the code
curl -X POST "http://localhost:8080/api/v1/chat" \
  -H "Content-Type: application/json" \
  -d "{
    \"projectId\": \"${PROJECT_ID}\",
    \"message\": \"What methods are available in UserService?\",
    \"history\": []
  }"
```

### Common Issues & Troubleshooting

**Problem**: Binary file uploaded with code extension
- **Cause**: User uploaded compiled file (.class, .pyc) instead of source
- **Fix**: System automatically rejects with "Binary file detected" error

**Problem**: Code formatting not preserved in responses
- **Cause**: Encoding issue or chunking split mid-statement
- **Fix**: System preserves exact formatting; verify file is UTF-8 encoded

**Problem**: Language not detected
- **Cause**: Unusual file extension or ambiguous content
- **Fix**: Add extension to `LanguageDetector.EXTENSION_MAP` if needed

**Problem**: Code entities not extracted
- **Cause**: LLM extraction failure or unsupported language pattern
- **Fix**: Check logs for extraction errors; entity extraction works best with well-structured code

## Active Technologies
- Java 21 + Quarkus 3.28.4, Resilience4j (via quarkus-smallrye-fault-tolerance), PostgreSQL 14+, Apache AGE, pgvector (004-retry-backoff)
- `AgeGraphStorage.java` for graph ops, `PgVectorStorage.java` for vector ops (004-retry-backoff)
- PostgreSQL 14+ with Apache AGE and pgvector extensions (006-lightrag-official-impl)
- Apache POI for Excel export (007-lightrag-enhancements)
- JTokkit 1.1.0 for accurate GPT-compatible token counting (cl100k_base encoding)
- SQLite with sqlite-jdbc for portable/edge deployments (009-sqlite-storage-port)
- Java 21 + Quarkus 3.28.4, Jakarta EE, jtokkit (token counting) (010-code-source-rag)
- PostgreSQL 14+ with Apache AGE (graph) and pgvector (embeddings), SQLite (alternative) (010-code-source-rag)

## Recent Changes
- 007-lightrag-enhancements: Added reranker integration (Cohere/Jina), document deletion with KG rebuild, entity merge operations, KG export (CSV/Excel/Markdown/Text), token usage tracking, chunk selection strategies
- 006-lightrag-official-impl: Added gleaning extraction, keyword extraction, context merging, token budgets, entity normalization
- 004-retry-backoff: Added Java 21 + Quarkus 3.28.4, Resilience4j (via quarkus-smallrye-fault-tolerance), PostgreSQL 14+, Apache AGE, pgvector
- Added jtokkit for accurate token counting (replaces character-based approximation)
- Added PersistentKeywordExtractor with two-level caching (L1 in-memory + L2 PostgreSQL)
