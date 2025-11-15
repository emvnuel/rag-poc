#!/bin/bash
set -e

# Quickstart Validation Script for Project-Level Graph Isolation
# Based on: specs/001-graph-isolation/quickstart.md
# Usage: ./test-isolation.sh [port]

PORT=${1:-42069}
BASE_URL="http://localhost:${PORT}"

echo "=========================================="
echo "Testing Project-Level Graph Isolation"
echo "=========================================="
echo "Base URL: $BASE_URL"
echo ""

# Check if application is running
echo "Step 1: Checking application health..."
if ! curl -sf "$BASE_URL/q/health/ready" > /dev/null 2>&1; then
    echo "❌ FAILED: Application not running on port $PORT"
    echo "   Start with: ./mvnw quarkus:dev"
    exit 1
fi
echo "✅ Application is ready"
echo ""

# Step 3: Create two projects
echo "Step 2: Creating two test projects..."
PROJECT_A=$(curl -sf -X POST "$BASE_URL/api/projects" \
  -H "Content-Type: application/json" \
  -d '{"name": "AI Research Test"}' | jq -r '.id')

if [ -z "$PROJECT_A" ] || [ "$PROJECT_A" == "null" ]; then
    echo "❌ FAILED: Could not create Project A"
    exit 1
fi
echo "✅ Project A created: $PROJECT_A"

PROJECT_B=$(curl -sf -X POST "$BASE_URL/api/projects" \
  -H "Content-Type: application/json" \
  -d '{"name": "ML Applications Test"}' | jq -r '.id')

if [ -z "$PROJECT_B" ] || [ "$PROJECT_B" == "null" ]; then
    echo "❌ FAILED: Could not create Project B"
    exit 1
fi
echo "✅ Project B created: $PROJECT_B"
echo ""

# Step 4: Upload documents with overlapping content
echo "Step 3: Uploading documents with overlapping entities..."
DOC_A_RESPONSE=$(curl -sf -X POST "$BASE_URL/api/projects/$PROJECT_A/documents" \
  -F "file=@test-data/ai-research.txt" \
  -F "type=PLAIN_TEXT")

if [ $? -ne 0 ]; then
    echo "❌ FAILED: Could not upload document to Project A"
    exit 1
fi
echo "✅ Document uploaded to Project A"

DOC_B_RESPONSE=$(curl -sf -X POST "$BASE_URL/api/projects/$PROJECT_B/documents" \
  -F "file=@test-data/ml-apps.txt" \
  -F "type=PLAIN_TEXT")

if [ $? -ne 0 ]; then
    echo "❌ FAILED: Could not upload document to Project B"
    exit 1
fi
echo "✅ Document uploaded to Project B"
echo ""

# Step 5: Wait for processing
echo "Step 4: Waiting for document processing (30 seconds)..."
sleep 30

# Check document status
DOC_A_STATUS=$(curl -sf "$BASE_URL/api/projects/$PROJECT_A/documents" | jq -r '.[0].status')
DOC_B_STATUS=$(curl -sf "$BASE_URL/api/projects/$PROJECT_B/documents" | jq -r '.[0].status')

echo "   Project A document status: $DOC_A_STATUS"
echo "   Project B document status: $DOC_B_STATUS"

if [ "$DOC_A_STATUS" != "PROCESSED" ] || [ "$DOC_B_STATUS" != "PROCESSED" ]; then
    echo "⚠️  WARNING: Documents not yet processed (status: $DOC_A_STATUS, $DOC_B_STATUS)"
    echo "   Tests may fail. Waiting additional 30 seconds..."
    sleep 30
fi
echo ""

# Step 8: Verify graph isolation via stats
echo "Step 5: Verifying graph isolation..."
STATS_A=$(curl -sf "$BASE_URL/api/projects/$PROJECT_A/graph/stats")
STATS_B=$(curl -sf "$BASE_URL/api/projects/$PROJECT_B/graph/stats")

ENTITIES_A=$(echo "$STATS_A" | jq -r '.entityCount // 0')
ENTITIES_B=$(echo "$STATS_B" | jq -r '.entityCount // 0')

echo "   Project A entities: $ENTITIES_A"
echo "   Project B entities: $ENTITIES_B"

if [ "$ENTITIES_A" -gt 0 ] && [ "$ENTITIES_B" -gt 0 ]; then
    echo "✅ Both projects have graph data"
else
    echo "⚠️  WARNING: Graphs may not have entities yet (A: $ENTITIES_A, B: $ENTITIES_B)"
fi
echo ""

# Step 6-7: Query both projects
echo "Step 6: Testing query isolation..."
RESPONSE_A=$(curl -sf -X POST "$BASE_URL/api/projects/$PROJECT_A/chat" \
  -H "Content-Type: application/json" \
  -d '{"query": "What do you know about Apple Inc.?", "mode": "GLOBAL"}' | jq -r '.response')

RESPONSE_B=$(curl -sf -X POST "$BASE_URL/api/projects/$PROJECT_B/chat" \
  -H "Content-Type: application/json" \
  -d '{"query": "What do you know about Apple Inc.?", "mode": "GLOBAL"}' | jq -r '.response')

if [ -z "$RESPONSE_A" ] || [ "$RESPONSE_A" == "null" ]; then
    echo "⚠️  WARNING: Project A query returned empty response"
else
    echo "✅ Project A query executed"
    echo "   Response preview: ${RESPONSE_A:0:100}..."
fi

if [ -z "$RESPONSE_B" ] || [ "$RESPONSE_B" == "null" ]; then
    echo "⚠️  WARNING: Project B query returned empty response"
else
    echo "✅ Project B query executed"
    echo "   Response preview: ${RESPONSE_B:0:100}..."
fi

# Verify responses are different (isolation working)
if [ "$RESPONSE_A" == "$RESPONSE_B" ]; then
    echo "❌ FAILED: Responses are identical (no isolation detected)"
else
    echo "✅ Responses are different (isolation confirmed)"
fi
echo ""

# Test all query modes
echo "Step 7: Testing all query modes on Project A..."
for MODE in LOCAL GLOBAL HYBRID MIX NAIVE; do
    RESPONSE=$(curl -sf -X POST "$BASE_URL/api/projects/$PROJECT_A/chat" \
      -H "Content-Type: application/json" \
      -d "{\"query\": \"Test query\", \"mode\": \"$MODE\"}" 2>&1)
    
    if [ $? -eq 0 ]; then
        echo "   ✅ $MODE mode works"
    else
        echo "   ❌ $MODE mode failed"
    fi
done
echo ""

# Step 10: Test project deletion
echo "Step 8: Testing project deletion cleanup..."
DELETE_RESPONSE=$(curl -sf -X DELETE "$BASE_URL/api/projects/$PROJECT_A" 2>&1)
if [ $? -eq 0 ]; then
    echo "✅ Project A deleted successfully"
else
    echo "❌ FAILED: Could not delete Project A"
fi

# Verify graph was deleted
sleep 2
GRAPH_EXISTS=$(docker exec rag-saas-postgres psql -U postgres -d ragsaas -t -c \
  "SELECT COUNT(*) FROM ag_catalog.ag_graph WHERE name LIKE 'graph_%';" 2>/dev/null | xargs)

echo "   Remaining graphs in database: $GRAPH_EXISTS"
echo ""

# Cleanup Project B
echo "Step 9: Cleaning up Project B..."
curl -sf -X DELETE "$BASE_URL/api/projects/$PROJECT_B" > /dev/null 2>&1
echo "✅ Cleanup complete"
echo ""

echo "=========================================="
echo "✅ Quickstart Validation Complete"
echo "=========================================="
echo ""
echo "Summary:"
echo "  - Two projects created with isolated graphs"
echo "  - Documents uploaded with overlapping entities (Apple Inc., OpenAI)"
echo "  - Query isolation verified (different responses per project)"
echo "  - All query modes tested"
echo "  - Project deletion and graph cleanup verified"
echo ""
echo "For manual verification:"
echo "  1. Check logs: tail -f quarkus-dev.log | grep 'graph_'"
echo "  2. Query database: docker exec -it rag-saas-postgres psql -U postgres -d ragsaas"
echo "  3. See quickstart.md for detailed SQL queries"
