# Quickstart: LightRAG Official Implementation Enhancements

**Feature**: 007-lightrag-enhancements  
**Date**: 2025-11-26  
**Purpose**: Developer setup guide for implementing the enhancements

## Prerequisites

1. **Existing Environment**
   - Java 21
   - Quarkus 3.28.4
   - PostgreSQL 14+ with Apache AGE and pgvector extensions
   - Spec-006 (LightRAG Official Implementation) completed

2. **New Dependencies**
   
   Add to `pom.xml`:
   ```xml
   <!-- Excel export -->
   <dependency>
       <groupId>org.apache.poi</groupId>
       <artifactId>poi-ooxml</artifactId>
       <version>5.2.5</version>
   </dependency>
   
   <!-- MicroProfile REST Client for Cohere/Jina APIs -->
   <dependency>
       <groupId>io.quarkus</groupId>
       <artifactId>quarkus-rest-client-reactive</artifactId>
   </dependency>
   ```

3. **API Keys** (optional, for reranking)
   - Cohere API key: https://dashboard.cohere.ai/api-keys
   - Jina API key: https://jina.ai/api

## Configuration

Add to `application.properties`:

```properties
# ====================
# Reranker Configuration
# ====================
# Enable/disable reranking (default: false)
lightrag.rerank.enabled=true

# Provider: cohere, jina, none (default: none)
lightrag.rerank.provider=cohere

# Cohere settings
lightrag.rerank.cohere.api-key=${COHERE_API_KEY:}
lightrag.rerank.cohere.model=rerank-english-v3.0

# Jina settings (alternative provider)
lightrag.rerank.jina.api-key=${JINA_API_KEY:}
lightrag.rerank.jina.model=jina-reranker-v2-base-multilingual

# Minimum relevance score (chunks below this are filtered)
lightrag.rerank.min-score=0.1

# Timeout before falling back to original order
lightrag.rerank.fallback-timeout-ms=2000

# ====================
# Description Summarization (Map-Reduce)
# ====================
# Skip summarization if fewer than this many descriptions
lightrag.description.force-summary-count=6

# Skip summarization if total tokens below this
lightrag.description.summary-context-size=10000

# Max tokens per batch in map phase
lightrag.description.summary-max-tokens=500

# Maximum map-reduce iterations
lightrag.description.max-map-iterations=3

# ====================
# Chunk Selection
# ====================
# Method: vector (similarity-based) or weight (occurrence-based)
lightrag.query.chunk-selection-method=vector

# Maximum related chunks to include in context
lightrag.query.max-related-chunks=20

# ====================
# Export
# ====================
# Batch size for streaming export
lightrag.export.batch-size=1000
```

## Environment Variables

Set these environment variables for reranker API access:

```bash
# For Cohere reranker
export COHERE_API_KEY=your-cohere-api-key

# For Jina reranker (alternative)
export JINA_API_KEY=your-jina-api-key
```

## New File Structure

Create these new packages under `src/main/java/br/edu/ifba/lightrag/`:

```
lightrag/
├── rerank/                    # NEW - Reranker implementations
│   ├── Reranker.java          # Interface
│   ├── RerankedChunk.java     # Record
│   ├── RerankerConfig.java    # Configuration
│   ├── CohereReranker.java    # Cohere implementation
│   ├── JinaReranker.java      # Jina implementation
│   └── NoOpReranker.java      # Disabled/fallback
│
├── deletion/                  # NEW - Document deletion
│   ├── DocumentDeletionService.java
│   ├── DocumentDeletionServiceImpl.java
│   └── KnowledgeRebuildResult.java
│
├── merge/                     # NEW - Entity merging
│   ├── EntityMergeService.java
│   ├── EntityMergeServiceImpl.java
│   ├── MergeStrategy.java
│   └── MergeResult.java
│
├── export/                    # NEW - Graph export
│   ├── GraphExporter.java     # Interface
│   ├── ExportConfig.java      # Record
│   ├── CsvGraphExporter.java
│   ├── ExcelGraphExporter.java
│   └── MarkdownGraphExporter.java
│
├── tracking/                  # NEW - Token tracking
│   ├── TokenTracker.java
│   ├── TokenTrackerImpl.java
│   ├── TokenUsage.java
│   └── TokenSummary.java
│
└── core/
    └── DescriptionSummarizer.java  # ENHANCED with map-reduce
```

## Implementation Order

### Phase 1: Token Tracking (Foundation)

1. Create `TokenUsage` and `TokenSummary` records
2. Implement `TokenTrackerImpl` as `@RequestScoped` bean
3. Add `TokenUsageFilter` (JAX-RS ContainerResponseFilter) to expose headers
4. Integrate tracker into LLM adapters

### Phase 2: Description Summarization Enhancement

1. Add map-reduce logic to existing `DescriptionSummarizer`
2. Add configuration for thresholds
3. Test with large entity description sets

### Phase 3: Reranker Integration

1. Create `Reranker` interface and implementations
2. Add MicroProfile REST clients for Cohere/Jina APIs
3. Implement circuit breaker fallback
4. Integrate into query executors

### Phase 4: Document Deletion

1. Extend `GraphStorage` with new methods
2. Extend `VectorStorage` with batch delete methods
3. Implement `DocumentDeletionService`
4. Add DELETE endpoint to `DocumentResources`

### Phase 5: Entity Merging

1. Add relationship methods to `GraphStorage`
2. Implement `EntityMergeService`
3. Add POST endpoint for merge operations

### Phase 6: Graph Export

1. Implement `GraphExporter` interface
2. Create CSV, Excel, Markdown exporters
3. Add GET endpoint for export

## Quick Verification

After implementing each phase, verify with tests:

```bash
# Phase 1: Token tracking
./mvnw test -Dtest=TokenTrackerTest

# Phase 2: Description summarization
./mvnw test -Dtest=DescriptionSummarizerMapReduceTest

# Phase 3: Reranker
./mvnw test -Dtest=RerankerTest,CohereRerankerTest

# Phase 4: Document deletion
./mvnw verify -DskipITs=false -Dit.test=DocumentDeletionIT

# Phase 5: Entity merging
./mvnw verify -DskipITs=false -Dit.test=EntityMergeIT

# Phase 6: Export
./mvnw test -Dtest=GraphExporterTest

# All tests
./mvnw verify -DskipITs=false
```

## API Usage Examples

### Delete Document with KG Rebuild

```bash
# Delete a document and rebuild affected entities
curl -X DELETE \
  "http://localhost:8080/api/v1/projects/${PROJECT_ID}/documents/${DOCUMENT_ID}" \
  -H "Accept: application/json"

# Response includes rebuild details:
# {
#   "documentId": "...",
#   "entitiesDeleted": ["Entity A", "Entity B"],
#   "entitiesRebuilt": ["Shared Entity"],
#   "relationsDeleted": 5,
#   "relationsRebuilt": 2,
#   "chunksDeleted": 42,
#   "errors": []
# }
```

### Merge Entities

```bash
# Merge duplicate entities
curl -X POST \
  "http://localhost:8080/api/v1/projects/${PROJECT_ID}/entities/merge" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceEntities": ["AI", "Artificial Intelligence", "Machine Intelligence"],
    "targetEntity": "Artificial Intelligence",
    "strategy": "LLM_SUMMARIZE",
    "targetEntityData": {
      "type": "CONCEPT"
    }
  }'

# Response:
# {
#   "targetEntity": { "name": "Artificial Intelligence", ... },
#   "relationsRedirected": 15,
#   "sourceEntitiesDeleted": 3,
#   "relationsDeduped": 2
# }
```

### Export Knowledge Graph

```bash
# Export to CSV
curl "http://localhost:8080/api/v1/projects/${PROJECT_ID}/export?format=csv" \
  -o knowledge-graph.csv

# Export to Excel with vectors
curl "http://localhost:8080/api/v1/projects/${PROJECT_ID}/export?format=xlsx&includeVectors=true" \
  -o knowledge-graph.xlsx
```

### Check Token Usage (All Endpoints)

```bash
# Token usage is returned in headers for all LLM-involved operations
curl -X POST "http://localhost:8080/api/chat" \
  -H "Content-Type: application/json" \
  -d '{"projectId": "...", "query": "What is AI?"}' \
  -v

# Response headers include:
# X-Token-Input: 1250
# X-Token-Output: 350
```

## Troubleshooting

### Reranker Not Working

```properties
# Check if enabled
lightrag.rerank.enabled=true

# Verify API key is set
echo $COHERE_API_KEY

# Check logs for circuit breaker status
quarkus.log.category."br.edu.ifba.lightrag.rerank".level=DEBUG
```

### Document Deletion Slow

```properties
# Increase batch size for large graphs
lightrag.export.batch-size=2000

# Check if too many entities need rebuilding (consider skipRebuild=true)
```

### Export Out of Memory

```properties
# Reduce batch size for memory-constrained environments
lightrag.export.batch-size=500

# For Excel, POI uses disk buffering automatically with SXSSFWorkbook
```

## Next Steps

1. Run the test suite to verify existing functionality
2. Implement Phase 1 (Token Tracking) first - it's foundational
3. Each subsequent phase builds on the previous
4. Integration tests validate cross-component behavior
