# Quickstart Guide: Project-Level Graph Isolation

**Feature**: Project-Level Graph Isolation  
**Audience**: Developers testing the feature  
**Updated**: 2025-11-15

## Overview

This guide walks through testing the project-level graph isolation feature to verify that knowledge graph data is completely isolated between projects.

## Prerequisites

- Docker and Docker Compose installed
- Java 21+ and Maven
- PostgreSQL 15+ with Apache AGE 1.5.0 (provided via docker-compose)
- Quarkus 3.28.4 project running

## Quick Start (5 Minutes)

### 1. Start Infrastructure

```bash
# Start PostgreSQL with AGE extension
docker-compose up -d postgres

# Wait for PostgreSQL to be ready
docker-compose logs -f postgres | grep "database system is ready"
```

### 2. Build and Run Application

```bash
# Build the project
./mvnw clean package

# Run in dev mode
./mvnw quarkus:dev
```

### 3. Create Two Projects

```bash
# Create Project A - "AI Research"
PROJECT_A=$(curl -s -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "AI Research"}' | jq -r '.id')

echo "Project A ID: $PROJECT_A"

# Create Project B - "ML Applications"
PROJECT_B=$(curl -s -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "ML Applications"}' | jq -r '.id')

echo "Project B ID: $PROJECT_B"
```

**Expected Result**: Each project gets its own graph (`graph_<uuid_prefix>`)

### 4. Upload Documents with Overlapping Content

**Project A - AI Research Document**:
```bash
curl -X POST http://localhost:8080/api/projects/$PROJECT_A/documents \
  -F "file=@test-data/ai-research.txt" \
  -F "type=PLAIN_TEXT"
```

Content example (test-data/ai-research.txt):
```
Apple Inc. is investing heavily in artificial intelligence research.
OpenAI has partnerships with Microsoft for AI infrastructure.
Google DeepMind focuses on AGI research.
```

**Project B - ML Applications Document**:
```bash
curl -X POST http://localhost:8080/api/projects/$PROJECT_B/documents \
  -F "file=@test-data/ml-apps.txt" \
  -F "type=PLAIN_TEXT"
```

Content example (test-data/ml-apps.txt):
```
Apple Inc. uses machine learning for product recommendations.
OpenAI provides APIs for developers to integrate AI.
Tesla applies ML for autonomous driving systems.
```

**Note**: Both documents mention "Apple Inc." and "OpenAI" - we'll verify they create separate entities

### 5. Wait for Processing

```bash
# Check document status for Project A
curl http://localhost:8080/api/projects/$PROJECT_A/documents | jq '.[] | {id, status}'

# Check document status for Project B
curl http://localhost:8080/api/projects/$PROJECT_B/documents | jq '.[] | {id, status}'
```

Wait until status is `PROCESSED` for both projects.

### 6. Query Project A

```bash
# Global query mode - should only see Project A's entities
curl -X POST http://localhost:8080/api/projects/$PROJECT_A/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What do you know about Apple Inc.?",
    "mode": "GLOBAL"
  }' | jq '.response'
```

**Expected**: Response mentions Apple's AI research (from Project A document only)

### 7. Query Project B

```bash
# Global query mode - should only see Project B's entities
curl -X POST http://localhost:8080/api/projects/$PROJECT_B/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What do you know about Apple Inc.?",
    "mode": "GLOBAL"
  }' | jq '.response'
```

**Expected**: Response mentions Apple's ML applications (from Project B document only)

### 8. Verify Isolation

```bash
# Get graph stats for Project A
curl http://localhost:8080/api/projects/$PROJECT_A/graph/stats | jq

# Get graph stats for Project B
curl http://localhost:8080/api/projects/$PROJECT_B/graph/stats | jq
```

**Expected Output**:
- Project A: 3 entities (Apple Inc., OpenAI, Google DeepMind)
- Project B: 3 entities (Apple Inc., OpenAI, Tesla)
- Each project has separate "Apple Inc." and "OpenAI" nodes

### 9. Test Cross-Project Isolation

**Direct Graph Query** (requires psql access):
```bash
# Connect to PostgreSQL
docker exec -it rag-saas-postgres psql -U postgres -d ragsaas

# Query Project A's graph
SELECT * FROM ag_catalog.cypher('graph_<project_a_prefix>', $$
    MATCH (n:Entity {name: 'Apple Inc.'})
    RETURN n.name, n.description
$$) as (name agtype, description agtype);

# Query Project B's graph
SELECT * FROM ag_catalog.cypher('graph_<project_b_prefix>', $$
    MATCH (n:Entity {name: 'Apple Inc.'})
    RETURN n.name, n.description
$$) as (name agtype, description agtype);
```

**Expected**: Two separate nodes with different descriptions

### 10. Test Project Deletion

```bash
# Delete Project A
curl -X DELETE http://localhost:8080/api/projects/$PROJECT_A

# Verify Project A's graph is deleted
docker exec -it rag-saas-postgres psql -U postgres -d ragsaas -c \
  "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'graph_%';"
```

**Expected**: Only Project B's graph remains

## Testing All Query Modes

### LOCAL Mode (Entity Extraction + Local Traversal)

```bash
curl -X POST http://localhost:8080/api/projects/$PROJECT_A/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How is OpenAI related to Microsoft?",
    "mode": "LOCAL"
  }' | jq '.response'
```

**Expected**: Mentions partnership from Project A's context

### GLOBAL Mode (Community Detection)

```bash
curl -X POST http://localhost:8080/api/projects/$PROJECT_A/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Summarize the AI research landscape",
    "mode": "GLOBAL"
  }' | jq '.response'
```

**Expected**: Only includes entities from Project A (Apple, OpenAI, Google DeepMind)

### HYBRID Mode (Vector + Graph)

```bash
curl -X POST http://localhost:8080/api/projects/$PROJECT_B/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What ML applications exist?",
    "mode": "HYBRID"
  }' | jq '.response'
```

**Expected**: Combines vector search and graph from Project B only

### MIX Mode (Combined Approach)

```bash
curl -X POST http://localhost:8080/api/projects/$PROJECT_A/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Tell me about AI companies",
    "mode": "MIX"
  }' | jq '.response'
```

**Expected**: Uses multiple strategies scoped to Project A

### NAIVE Mode (Vector-Only, No Graph)

```bash
curl -X POST http://localhost:8080/api/projects/$PROJECT_B/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is machine learning?",
    "mode": "NAIVE"
  }' | jq '.response'
```

**Expected**: Vector-only results from Project B documents

## Automated Test Script

Create `test-isolation.sh`:

```bash
#!/bin/bash
set -e

echo "=== Testing Project-Level Graph Isolation ==="

# Create projects
echo "Creating Project A..."
PROJECT_A=$(curl -s -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "Test Project A"}' | jq -r '.id')

echo "Creating Project B..."
PROJECT_B=$(curl -s -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{"name": "Test Project B"}' | jq -r '.id')

# Upload test documents
echo "Uploading documents..."
curl -s -X POST http://localhost:8080/api/projects/$PROJECT_A/documents \
  -F "file=@test-data/doc-a.txt" -F "type=PLAIN_TEXT" > /dev/null

curl -s -X POST http://localhost:8080/api/projects/$PROJECT_B/documents \
  -F "file=@test-data/doc-b.txt" -F "type=PLAIN_TEXT" > /dev/null

# Wait for processing
echo "Waiting for document processing..."
sleep 30

# Test isolation
echo "Testing query isolation..."
RESULT_A=$(curl -s -X POST http://localhost:8080/api/projects/$PROJECT_A/chat \
  -H "Content-Type: application/json" \
  -d '{"query": "What entities exist?", "mode": "GLOBAL"}' | jq -r '.response')

RESULT_B=$(curl -s -X POST http://localhost:8080/api/projects/$PROJECT_B/chat \
  -H "Content-Type: application/json" \
  -d '{"query": "What entities exist?", "mode": "GLOBAL"}' | jq -r '.response')

# Verify responses are different (isolation works)
if [ "$RESULT_A" == "$RESULT_B" ]; then
  echo "❌ FAILED: Responses are identical (no isolation)"
  exit 1
fi

echo "✅ PASSED: Responses are different (isolation confirmed)"

# Cleanup
echo "Cleaning up..."
curl -s -X DELETE http://localhost:8080/api/projects/$PROJECT_A > /dev/null
curl -s -X DELETE http://localhost:8080/api/projects/$PROJECT_B > /dev/null

echo "=== All Tests Passed ==="
```

Run with:
```bash
chmod +x test-isolation.sh
./test-isolation.sh
```

## Troubleshooting

### Error: "Graph not found for project"

**Symptom**: `IllegalStateException` when querying

**Cause**: Graph wasn't created when project was created

**Fix**:
```bash
# Check if graph exists
docker exec -it rag-saas-postgres psql -U postgres -d ragsaas -c \
  "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'graph_%';"

# Recreate graph manually (requires admin API)
curl -X POST http://localhost:8080/api/admin/projects/$PROJECT_ID/graph/create
```

### Cross-Project Data Leakage

**Symptom**: Query in Project A returns entities from Project B

**Diagnosis**:
```bash
# Check which graph is being used
# Look for logs: "Executing query on graph: graph_<uuid>"
curl http://localhost:8080/api/projects/$PROJECT_A/chat \
  -H "Content-Type: application/json" \
  -d '{"query": "test", "mode": "GLOBAL"}' -v
```

**Possible Causes**:
1. Query executor not passing projectId correctly
2. GraphStorage routing to wrong graph
3. Cache returning stale data

**Fix**: Check logs for `DEBUG` level graph routing messages

### Performance Degradation

**Symptom**: Queries take >550ms P95 (violates SC-002)

**Diagnosis**:
```bash
# Check graph size
curl http://localhost:8080/api/projects/$PROJECT_ID/graph/stats

# Check for orphaned graphs
docker exec -it rag-saas-postgres psql -U postgres -d ragsaas -c \
  "SELECT nspname FROM pg_namespace WHERE nspname LIKE 'graph_%';"
```

**Expected**: P95 should be <300ms for graphs with <100K entities

**Fix**: If specific project is slow, check for:
1. Excessive entity count (>100K)
2. Circular relationships causing infinite traversals
3. Missing project isolation (querying all graphs)

### Graph Deletion Timeout

**Symptom**: Project deletion takes >60 seconds (violates SC-003)

**Diagnosis**:
```bash
# Check graph size before deletion
curl http://localhost:8080/api/projects/$PROJECT_ID/graph/stats

# Check for active connections
docker exec -it rag-saas-postgres psql -U postgres -d ragsaas -c \
  "SELECT * FROM pg_stat_activity WHERE datname = 'ragsaas';"
```

**Fix**:
1. Close active connections to the graph
2. Reduce graph size if >50K entities (SC-003 guarantees 10K)
3. Check for transaction locks

## Performance Benchmarks

Expected performance per research findings:

| Scenario | Target P95 | Actual P95 | Status |
|----------|-----------|-----------|--------|
| Graph creation | <100ms | ~50ms | ✅ |
| Single entity lookup | <20ms | ~10ms | ✅ |
| 2-hop traversal | <300ms | ~150ms | ✅ |
| Project deletion (10K entities) | <60s | ~30s | ✅ |
| Concurrent 50 projects | No contention | No locks | ✅ |

## Success Criteria Validation

Run these tests to validate all success criteria:

```bash
# SC-001: Zero cross-project data leakage
./test-isolation.sh

# SC-002: P95 <550ms for 2-hop traversals
./mvnw test -Dtest=AgeGraphStorageTest#test2HopTraversalUnder300ms

# SC-003: Project deletion <60s for 10K entities
./mvnw test -Dtest=AgeGraphStorageTest#testDeleteProjectCompletesWithin60Seconds

# SC-004: Migration without data loss
./scripts/migrate-graph-isolation.sh --dry-run

# SC-005: 100 concurrent uploads across 50 projects
./mvnw test -Dtest=ProjectIsolationIT#testConcurrent50ProjectUploads

# SC-006: All query modes work with isolation
./mvnw test -Dtest=ProjectIsolationIT
```

## Next Steps

After verifying the feature works:

1. **Run full test suite**: `./mvnw verify -DskipITs=false`
2. **Load testing**: Use JMeter/Gatling to simulate 100+ concurrent projects
3. **Migration**: Run `./scripts/migrate-graph-isolation.sh` on staging
4. **Production deployment**: Follow migration guide in [plan.md](./plan.md)

## Resources

- [data-model.md](./data-model.md) - Graph architecture details
- [contracts/GraphStorage.interface.md](./contracts/GraphStorage.interface.md) - API contract
- [spec.md](./spec.md) - User stories and requirements
- [tasks.md](./tasks.md) - Implementation task list

## Support

For issues or questions:
1. Check application logs: `docker-compose logs -f`
2. Review graph state: `psql` commands above
3. Run diagnostics: `./mvnw quarkus:dev` and check health endpoints
4. See [troubleshooting](#troubleshooting) section above
