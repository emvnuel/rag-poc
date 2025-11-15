#!/bin/bash

# Entity Resolution End-to-End Test Script
# Tests the semantic entity deduplication feature
# Based on specs/002-semantic-entity-dedup/quickstart.md

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
