# Quickstart Guide: Semantic Entity Deduplication

**Branch**: `001-semantic-entity-dedup` | **Date**: 2025-11-15 | **Phase**: 1 - Design  
**Audience**: Developers and users configuring the entity resolution feature

## Summary

This guide provides step-by-step instructions for enabling, configuring, and testing the semantic entity deduplication feature in LightRAG. The feature reduces duplicate entity nodes in the knowledge graph by 40-60% using multi-metric similarity matching.

---

## Quick Start (5 minutes)

### 1. Enable Entity Resolution

Add to `src/main/resources/application.properties`:

```properties
# Enable entity resolution
lightrag.entity.resolution.enabled=true
```

That's it! The system now deduplicates entities using default settings.

### 2. Index a Document

```bash
curl -X POST "http://localhost:8080/api/documents" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@my-document.pdf" \
  -F "projectId=your-project-id"
```

### 3. Verify Results

Check logs for entity resolution statistics:

```
INFO  Entity resolution: 200 → 150 entities (-50 duplicates, 25.0% reduction) | 45 clusters | 120ms
```

Query the knowledge graph to see merged entities:

```bash
curl -X POST "http://localhost:8080/api/chat" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What institutions are mentioned?",
    "projectId": "your-project-id",
    "mode": "GLOBAL"
  }'
```

---

## Configuration Guide

### Basic Configuration

**Default Settings** (works for most use cases):

```properties
# Feature toggle
lightrag.entity.resolution.enabled=true

# Similarity threshold (0.75 = balanced)
lightrag.entity.resolution.similarity.threshold=0.75

# Maximum aliases to show per entity
lightrag.entity.resolution.max.aliases=5
```

### Advanced Configuration

**Fine-Tuning Similarity Metrics**:

```properties
# Similarity metric weights (must sum to 1.0)
lightrag.entity.resolution.weight.jaccard=0.35        # Token overlap
lightrag.entity.resolution.weight.containment=0.25    # Substring matching
lightrag.entity.resolution.weight.edit=0.30           # Edit distance
lightrag.entity.resolution.weight.abbreviation=0.10   # Acronym matching
```

**Performance Tuning**:

```properties
# Batch processing
lightrag.entity.resolution.batch.size=200

# Parallel processing
lightrag.entity.resolution.parallel.enabled=true
lightrag.entity.resolution.parallel.threads=4
```

**Logging Configuration**:

```properties
# Log merge decisions at INFO level
lightrag.entity.resolution.log.merges=true

# Log detailed similarity scores at DEBUG level (verbose)
lightrag.entity.resolution.log.similarity.scores=false
```

### Configuration Presets

**Conservative (Fewer Merges, Higher Precision)**:

```properties
lightrag.entity.resolution.enabled=true
lightrag.entity.resolution.similarity.threshold=0.85
lightrag.entity.resolution.weight.jaccard=0.40
lightrag.entity.resolution.weight.containment=0.20
lightrag.entity.resolution.weight.edit=0.30
lightrag.entity.resolution.weight.abbreviation=0.10
```

**Aggressive (More Merges, Lower Precision)**:

```properties
lightrag.entity.resolution.enabled=true
lightrag.entity.resolution.similarity.threshold=0.70
lightrag.entity.resolution.weight.jaccard=0.30
lightrag.entity.resolution.weight.containment=0.30
lightrag.entity.resolution.weight.edit=0.30
lightrag.entity.resolution.weight.abbreviation=0.10
```

**Academic Papers** (Focus on exact names):

```properties
lightrag.entity.resolution.enabled=true
lightrag.entity.resolution.similarity.threshold=0.80
lightrag.entity.resolution.weight.jaccard=0.30
lightrag.entity.resolution.weight.containment=0.20
lightrag.entity.resolution.weight.edit=0.40  # High weight for typo tolerance
lightrag.entity.resolution.weight.abbreviation=0.10
```

**News Articles** (Handle abbreviations):

```properties
lightrag.entity.resolution.enabled=true
lightrag.entity.resolution.similarity.threshold=0.75
lightrag.entity.resolution.weight.jaccard=0.30
lightrag.entity.resolution.weight.containment=0.25
lightrag.entity.resolution.weight.edit=0.25
lightrag.entity.resolution.weight.abbreviation=0.20  # High weight for acronyms
```

---

## Testing Your Configuration

### Test 1: Basic Duplicate Detection

**Input Document** (`test-duplicates.txt`):

```text
The Massachusetts Institute of Technology (MIT) is a renowned university.
MIT was founded in 1861. Students at Massachusetts Institute of Technology
study engineering and science.
```

**Expected Behavior**:

Without resolution:
- 3 entities: "Massachusetts Institute of Technology", "MIT", "MIT"

With resolution (default threshold 0.75):
- 1 entity: "Massachusetts Institute of Technology" (aliases: MIT)

**Test Command**:

```bash
curl -X POST "http://localhost:8080/api/documents/upload" \
  -F "file=@test-duplicates.txt" \
  -F "projectId=test-project"

# Check logs
tail -f logs/application.log | grep "Entity resolution"
```

### Test 2: Type-Aware Matching

**Input Document** (`test-types.txt`):

```text
Apple Inc. is a technology company. I ate an apple for lunch today.
The apple was delicious. Apple announced new products.
```

**Expected Behavior**:

- 2 entities:
  - "Apple Inc." (ORGANIZATION) - mentioned 2 times
  - "apple" (FOOD) - mentioned 2 times

**Verification**: No merge between company and fruit (different types)

### Test 3: Threshold Tuning

**Goal**: Find optimal threshold for your domain

**Process**:

1. Index a representative document
2. Check entity count without resolution:
   ```bash
   # Temporarily disable
   lightrag.entity.resolution.enabled=false
   # Reindex and note entity count
   ```

3. Enable resolution and test thresholds:
   ```properties
   lightrag.entity.resolution.similarity.threshold=0.70
   # Reindex, check entity count
   
   lightrag.entity.resolution.similarity.threshold=0.75
   # Reindex, check entity count
   
   lightrag.entity.resolution.similarity.threshold=0.80
   # Reindex, check entity count
   ```

4. Select threshold with best quality/precision trade-off

**Recommended Thresholds**:

| Domain | Threshold | Rationale |
|--------|-----------|-----------|
| Literary texts | 0.70-0.75 | Many name variations, nicknames |
| Academic papers | 0.80-0.85 | Need precision, avoid false merges |
| News articles | 0.75-0.80 | Balanced for abbreviations |
| Technical docs | 0.75-0.80 | Acronyms common, need balance |

---

## Monitoring & Troubleshooting

### Log Analysis

**INFO Level Logs** (always enabled if `log.merges=true`):

```
INFO  Entity resolution: 200 → 150 entities (-50 duplicates, 25.0% reduction) | 45 clusters | 120ms (0.60ms/entity)
INFO  Cluster: 'Warren State Home and Training School' [3 entities merged] Aliases: Warren Home, Warren State Home
```

**DEBUG Level Logs** (enable with `log.similarity.scores=true`):

```
DEBUG Similarity: 'Warren State Home and Training School' vs 'Warren Home' = 0.730 [J:0.67 C:0.80 L:0.75 A:0.00]
```

### Common Issues

#### Issue 1: Too Many False Positives (Incorrect Merges)

**Symptom**: Entities merged that shouldn't be

**Solution**: Increase similarity threshold

```properties
# Increase from 0.75 to 0.80 or 0.85
lightrag.entity.resolution.similarity.threshold=0.80
```

**Example Log**:
```
WARN High deduplication rate detected: 75.0% (200 → 50 entities)
```

#### Issue 2: Too Few Merges (Duplicates Remain)

**Symptom**: Duplicate entities still in graph

**Solution**: Decrease similarity threshold or adjust weights

```properties
# Decrease from 0.75 to 0.70
lightrag.entity.resolution.similarity.threshold=0.70

# OR increase containment weight for substring matching
lightrag.entity.resolution.weight.containment=0.35
lightrag.entity.resolution.weight.jaccard=0.25
```

#### Issue 3: Performance Degradation

**Symptom**: Document indexing >2x slower

**Solution**: Disable parallel processing or reduce batch size

```properties
# Option 1: Disable parallel processing
lightrag.entity.resolution.parallel.enabled=false

# Option 2: Reduce batch size
lightrag.entity.resolution.batch.size=100

# Option 3: Disable resolution temporarily
lightrag.entity.resolution.enabled=false
```

**Check Performance Logs**:
```
INFO  Entity resolution: 200 → 150 entities | 450ms (2.25ms/entity)
```
Target: <1ms per entity

#### Issue 4: Configuration Errors

**Symptom**: Application fails to start

**Error Example**:
```
ERROR Similarity weights must sum to 1.0, got 1.15 (jaccard=0.35, containment=0.35, edit=0.35, abbrev=0.10)
```

**Solution**: Verify weights sum to 1.0

```properties
# Correct weights (sum = 1.0)
lightrag.entity.resolution.weight.jaccard=0.35
lightrag.entity.resolution.weight.containment=0.25
lightrag.entity.resolution.weight.edit=0.30
lightrag.entity.resolution.weight.abbreviation=0.10
```

---

## Integration Testing

### End-to-End Test Script

**File**: `test-entity-resolution.sh`

```bash
#!/bin/bash

# Configuration
PROJECT_ID="test-dedup-$(date +%s)"
BASE_URL="http://localhost:8080/api"

echo "=== Entity Resolution E2E Test ==="
echo "Project ID: $PROJECT_ID"

# Step 1: Create project
echo "[1/4] Creating project..."
curl -X POST "$BASE_URL/projects" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"Test Dedup Project\"}" \
  | jq '.id' > /tmp/project_id.txt

PROJECT_ID=$(cat /tmp/project_id.txt)
echo "Project created: $PROJECT_ID"

# Step 2: Upload test document
echo "[2/4] Uploading document with entity duplicates..."
cat > /tmp/test-doc.txt <<EOF
The Warren State Home and Training School was established in 1907.
Warren Home provided care for mentally disabled individuals.
The Warren State Home was located in Pennsylvania.
Warren Home School offered educational programs.
EOF

DOC_ID=$(curl -X POST "$BASE_URL/documents/upload" \
  -F "file=@/tmp/test-doc.txt" \
  -F "projectId=$PROJECT_ID" \
  | jq -r '.id')

echo "Document uploaded: $DOC_ID"

# Step 3: Wait for processing
echo "[3/4] Waiting for document processing..."
sleep 10

# Step 4: Query entities
echo "[4/4] Querying entities..."
RESPONSE=$(curl -X POST "$BASE_URL/chat" \
  -H "Content-Type: application/json" \
  -d "{
    \"query\": \"What institutions are mentioned?\",
    \"projectId\": \"$PROJECT_ID\",
    \"mode\": \"GLOBAL\"
  }")

echo "Response: $RESPONSE"

# Step 5: Validate results
ENTITY_COUNT=$(echo "$RESPONSE" | jq -r '.entities | length')
echo "Entity count: $ENTITY_COUNT"

if [ "$ENTITY_COUNT" -eq 1 ]; then
  echo "✅ SUCCESS: Entities merged correctly (expected 1, got $ENTITY_COUNT)"
else
  echo "❌ FAILURE: Expected 1 entity, got $ENTITY_COUNT"
  exit 1
fi

echo "=== Test Complete ==="
```

**Run Test**:

```bash
chmod +x test-entity-resolution.sh
./test-entity-resolution.sh
```

---

## Best Practices

### 1. Start Conservative

Begin with a high threshold (0.80) and gradually decrease if duplicates remain:

```properties
# Week 1: Conservative
lightrag.entity.resolution.similarity.threshold=0.80

# Week 2: Review results, adjust if needed
lightrag.entity.resolution.similarity.threshold=0.75
```

### 2. Monitor Deduplication Rate

Acceptable range: 20-40% reduction

- **<20%**: Resolution not catching enough duplicates
- **20-40%**: Healthy deduplication rate ✅
- **>40%**: Possible false positives, review merged entities

### 3. Test with Domain Data

Don't tune thresholds on toy examples. Use real documents from your domain:

```bash
# Test with actual corpus
./mvnw test -Dtest=EntityDeduplicationIT
```

### 4. Enable Logging During Tuning

```properties
# During configuration phase
lightrag.entity.resolution.log.merges=true
lightrag.entity.resolution.log.similarity.scores=true

# In production
lightrag.entity.resolution.log.merges=true
lightrag.entity.resolution.log.similarity.scores=false
```

### 5. Document Your Configuration

Add comments to `application.properties`:

```properties
# Entity Resolution Configuration
# Domain: Academic papers (computer science)
# Tuning date: 2025-11-15
# Rationale: High threshold to avoid merging different papers with similar titles
lightrag.entity.resolution.similarity.threshold=0.85
```

---

## Performance Benchmarks

### Expected Performance

| Entity Count | Processing Time (without resolution) | Processing Time (with resolution) | Overhead |
|--------------|-------------------------------------|-----------------------------------|----------|
| 50 entities | 50ms | 75ms | 1.5x |
| 100 entities | 100ms | 160ms | 1.6x |
| 200 entities | 200ms | 350ms | 1.75x |
| 500 entities | 500ms | 950ms | 1.9x |

### Optimization Tips

**For Large Documents** (>500 entities):

```properties
# Enable parallel processing
lightrag.entity.resolution.parallel.enabled=true
lightrag.entity.resolution.parallel.threads=8

# Increase batch size
lightrag.entity.resolution.batch.size=300
```

**For Real-Time Processing**:

```properties
# Disable resolution for low-latency scenarios
lightrag.entity.resolution.enabled=false

# OR use aggressive early termination
lightrag.entity.resolution.parallel.enabled=false
lightrag.entity.resolution.batch.size=100
```

---

## Next Steps

1. ✅ **Complete**: Quickstart guide created
2. ⏭️ **Next**: Run agent context update
3. ⏭️ **Next**: Generate task breakdown with `/speckit.tasks`
4. ⏭️ **Next**: Begin TDD implementation

---

## Additional Resources

- **Specification**: `specs/001-semantic-entity-dedup/spec.md`
- **Data Model**: `specs/001-semantic-entity-dedup/data-model.md`
- **API Contracts**: `specs/001-semantic-entity-dedup/contracts/EntityResolver.interface.md`
- **Research Findings**: `specs/001-semantic-entity-dedup/research.md`
- **Implementation Plan**: `specs/001-semantic-entity-dedup/plan.md`

---

## Support

For issues or questions:

1. Check logs in `logs/application.log`
2. Review configuration in `application.properties`
3. Run test suite: `./mvnw test`
4. File issue with reproduction steps and logs
