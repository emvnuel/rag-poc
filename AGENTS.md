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

### Testing Commands
```bash
# Run gleaning extraction tests (9 tests)
./mvnw test -Dtest=GleaningExtractionTest

# Run query mode enhancement tests (18 tests)
./mvnw test -Dtest=QueryModeEnhancementsTest

# Run all LightRAG core tests
./mvnw test -Dtest="*LightRAG*,Gleaning*,QueryMode*"
```

### Key Components
- **LightRAGExtractionConfig** (`lightrag/core/LightRAGExtractionConfig.java`): Centralized configuration via `@ConfigMapping`
- **KeywordExtractor/LLMKeywordExtractor** (`lightrag/query/`): Query keyword extraction
- **ContextMerger** (`lightrag/query/ContextMerger.java`): Round-robin context merging with token budgets
- **TokenUtil** (`lightrag/utils/TokenUtil.java`): Token estimation and budget allocation
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

## Active Technologies
- Java 21 + Quarkus 3.28.4, Resilience4j (via quarkus-smallrye-fault-tolerance), PostgreSQL 14+, Apache AGE, pgvector (004-retry-backoff)
- `AgeGraphStorage.java` for graph ops, `PgVectorStorage.java` for vector ops (004-retry-backoff)
- PostgreSQL 14+ with Apache AGE and pgvector extensions (006-lightrag-official-impl)
- Apache POI for Excel export (007-lightrag-enhancements)

## Recent Changes
- 007-lightrag-enhancements: Added reranker integration (Cohere/Jina), document deletion with KG rebuild, entity merge operations, KG export (CSV/Excel/Markdown/Text), token usage tracking, chunk selection strategies
- 006-lightrag-official-impl: Added gleaning extraction, keyword extraction, context merging, token budgets, entity normalization
- 004-retry-backoff: Added Java 21 + Quarkus 3.28.4, Resilience4j (via quarkus-smallrye-fault-tolerance), PostgreSQL 14+, Apache AGE, pgvector
