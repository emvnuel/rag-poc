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

## Active Technologies
- Java 21 + Quarkus 3.28.4, Resilience4j (via quarkus-smallrye-fault-tolerance), PostgreSQL 14+, Apache AGE, pgvector (004-retry-backoff)
- `AgeGraphStorage.java` for graph ops, `PgVectorStorage.java` for vector ops (004-retry-backoff)
- PostgreSQL 14+ with Apache AGE and pgvector extensions (006-lightrag-official-impl)

## Recent Changes
- 006-lightrag-official-impl: Added gleaning extraction, keyword extraction, context merging, token budgets, entity normalization
- 004-retry-backoff: Added Java 21 + Quarkus 3.28.4, Resilience4j (via quarkus-smallrye-fault-tolerance), PostgreSQL 14+, Apache AGE, pgvector
