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
