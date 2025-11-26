# Quickstart: LightRAG Official Implementation Alignment

**Feature**: 006-lightrag-official-impl  
**Date**: 2025-11-25  
**Purpose**: Developer guide for implementing the official LightRAG alignment

## Prerequisites

- Java 21+
- Docker & Docker Compose
- PostgreSQL with AGE and pgvector extensions (via docker-compose)
- OpenAI API key or compatible LLM endpoint

## Setup

### 1. Start Infrastructure

```bash
# Start PostgreSQL with AGE and pgvector
docker-compose up -d

# Verify extensions
docker exec -it rag-postgres psql -U postgres -d ragdb -c "
SELECT * FROM pg_extension WHERE extname IN ('age', 'vector');
"
```

### 2. Run Migrations

```bash
# Apply new extraction_cache table migration
psql -U postgres -d ragdb -f docker-init/11-add-extraction-cache.sql
```

### 3. Configure Application

Add to `application.properties`:

```properties
# === LightRAG Official Alignment Configuration ===

# Gleaning (iterative extraction)
lightrag.extraction.gleaning.enabled=true
lightrag.extraction.gleaning.max-passes=1

# Description merging
lightrag.entity.description.max-tokens=500
lightrag.entity.description.separator=" | "

# Source tracking
lightrag.entity.max-source-ids=50
lightrag.entity.source-id-strategy=FIFO

# Keyword extraction
lightrag.query.keyword-extraction.enabled=true
lightrag.query.keyword-extraction.cache-ttl=3600

# Token budget management
lightrag.query.context.max-tokens=4000
lightrag.query.context.entity-budget-ratio=0.4
lightrag.query.context.relation-budget-ratio=0.3
lightrag.query.context.chunk-budget-ratio=0.3

# Batch operations
lightrag.graph.batch-size=500
```

### 4. Run Tests

```bash
# Run all LightRAG tests
./mvnw test -Dtest="br.edu.ifba.lightrag.**"

# Run specific new tests
./mvnw test -Dtest=GleaningExtractionIT
./mvnw test -Dtest=KeywordExtractorTest
./mvnw test -Dtest=DescriptionSummarizerTest
./mvnw test -Dtest=BatchOperationsIT
```

## Implementation Order

Follow this order for minimal breaking changes:

### Phase 1: Foundation (No behavior change)

1. **Add source tracking fields to Entity/Relation**
   - `Entity.java`: Add `sourceChunkIds`, `sourceFilePaths`
   - `Relation.java`: Add `sourceChunkIds`, `sourceFilePaths`
   - Builder and with methods
   - Tests: Verify serialization, equality

2. **Add Chunk enhancements**
   - `Chunk.java`: Add `fullDocId`, `chunkOrderIndex`, `llmCacheIds`

3. **Create ExtractionCache table and storage**
   - Migration script: `11-add-extraction-cache.sql`
   - `ExtractionCache.java`: Record class
   - `ExtractionCacheStorage.java`: Interface
   - `PgExtractionCacheStorage.java`: PostgreSQL implementation

### Phase 2: Extraction Improvements

4. **Add gleaning to extraction pipeline**
   - Modify `LightRAG.extractKnowledgeGraphFromChunk()`
   - Add gleaning prompt template
   - Store initial + gleaning results in cache
   - Feature flag: `lightrag.extraction.gleaning.enabled`

5. **Add description summarization**
   - Create `DescriptionSummarizer.java` interface
   - Create `LLMDescriptionSummarizer.java` implementation
   - Integrate into `LightRAG.storeKnowledgeGraph()`
   - Feature flag based on token threshold

### Phase 3: Query Improvements

6. **Add keyword extraction**
   - Create `KeywordExtractor.java` interface
   - Create `LLMKeywordExtractor.java` implementation
   - Add keyword extraction prompt template
   - Integrate caching

7. **Update query executors**
   - Modify `LocalQueryExecutor`: Use low-level keywords
   - Modify `GlobalQueryExecutor`: Use high-level keywords
   - Modify `HybridQueryExecutor`: Use both with round-robin
   - Add token budget management

### Phase 4: Performance Improvements

8. **Add batch operations to GraphStorage**
   - Add `getEntitiesBatch()` to interface
   - Add `getNodeDegreesBatch()` to interface
   - Implement in `AgeGraphStorage.java`

9. **Add connection health validation**
   - Modify `AgeConfig.getConnection()`
   - Add `isValid()` check

## Testing Strategy

### Unit Tests (Fast, No Dependencies)

```java
// KeywordExtractorTest.java
@Test
void testHighLevelKeywordExtraction() {
    var result = extractor.parseKeywords("""
        HIGH_LEVEL: character evolution, plot dynamics
        LOW_LEVEL: Charlie Gordon, Dr. Strauss
        """);
    
    assertThat(result.highLevelKeywords())
        .containsExactly("character evolution", "plot dynamics");
}

// DescriptionSummarizerTest.java
@Test
void testNeedsSummarization() {
    var shortDescs = List.of("Short description.");
    var longDescs = List.of("Long description...".repeat(100));
    
    assertFalse(summarizer.needsSummarization(shortDescs));
    assertTrue(summarizer.needsSummarization(longDescs));
}
```

### Integration Tests (Database Required)

```java
// GleaningExtractionIT.java
@QuarkusTest
class GleaningExtractionIT {
    
    @Test
    void testGleaningCapturesAdditionalEntities() {
        // Document with implicit entities
        String content = """
            Charlie visited the laboratory where Dr. Strauss 
            conducted the experiment. The facility was funded 
            by the Welberg Foundation.
            """;
        
        // Without gleaning: may miss "Welberg Foundation"
        // With gleaning: should capture it
        
        var result = lightRAG.insert(content).join();
        var entities = graphStorage.getAllEntities(projectId).join();
        
        assertThat(entities).anyMatch(e -> 
            e.getEntityName().contains("Welberg"));
    }
}

// BatchOperationsIT.java
@QuarkusTest
class BatchOperationsIT {
    
    @Test
    void testGetEntitiesBatch() {
        // Setup: Create 100 entities
        var names = IntStream.range(0, 100)
            .mapToObj(i -> "Entity" + i)
            .toList();
        
        var result = graphStorage.getEntitiesBatch(
            projectId, names, 50).join();
        
        assertThat(result).hasSize(100);
    }
}
```

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `lightrag.extraction.gleaning.enabled` | `true` | Enable iterative extraction |
| `lightrag.extraction.gleaning.max-passes` | `1` | Max gleaning iterations |
| `lightrag.entity.description.max-tokens` | `500` | Threshold for summarization |
| `lightrag.entity.max-source-ids` | `50` | Max source chunk IDs per entity |
| `lightrag.query.keyword-extraction.enabled` | `true` | Enable LLM keyword extraction |
| `lightrag.query.keyword-extraction.cache-ttl` | `3600` | Keyword cache TTL (seconds) |
| `lightrag.query.context.max-tokens` | `4000` | Total token budget for context |
| `lightrag.graph.batch-size` | `500` | Batch size for graph queries |

## Troubleshooting

### Common Issues

**Issue**: Gleaning adds no new entities
- **Cause**: Initial extraction already comprehensive, or gleaning prompt too similar
- **Fix**: This is expected for simple documents; gleaning helps with complex docs

**Issue**: Description summarization slow
- **Cause**: LLM call for each entity with long descriptions
- **Fix**: Increase threshold (`lightrag.entity.description.max-tokens`)

**Issue**: Keyword extraction returning empty
- **Cause**: LLM response format mismatch
- **Fix**: Check LLM response format, update parsing logic

**Issue**: Batch queries timing out
- **Cause**: Batch size too large for entity count
- **Fix**: Reduce `lightrag.graph.batch-size` to 200

### Debugging

```bash
# Enable debug logging for LightRAG
quarkus.log.category."br.edu.ifba.lightrag".level=DEBUG

# View extraction cache contents
psql -U postgres -d ragdb -c "
SELECT cache_type, COUNT(*) 
FROM rag.extraction_cache 
GROUP BY cache_type;
"

# Check entity source tracking
psql -U postgres -d ragdb -c "
SELECT name, source_chunk_ids::text 
FROM graph_xxx.\"Entity\" 
LIMIT 10;
"
```

## Next Steps

After implementation:
1. Run full test suite: `./mvnw verify`
2. Benchmark extraction quality vs baseline
3. Measure query response quality improvement
4. Update AGENTS.md with new configuration options
