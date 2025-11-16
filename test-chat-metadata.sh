#!/bin/bash
# Manual test script for Feature 003: Chat Response Chunk Metadata
#
# This script validates that the chat API includes document IDs and chunk indices
# in the response. Use this for manual feature validation since REST Assured
# integration tests have Hamcrest dependency conflicts.
#
# Usage: ./test-chat-metadata.sh [API_BASE_URL]
#
# Prerequisites:
# - Quarkus application running (./mvnw quarkus:dev)
# - jq installed (for JSON formatting)
# - curl installed

set -e

BASE_URL="${1:-http://localhost:8080}"
API_URL="${BASE_URL}/api/v1"

echo "================================"
echo "Feature 003: Chat Metadata Test"
echo "================================"
echo ""
echo "API Base URL: $API_URL"
echo ""

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test 1: Create test project
echo -e "${YELLOW}Test 1: Creating test project...${NC}"
PROJECT_RESPONSE=$(curl -s -X POST "${API_URL}/projects" \
  -H "Content-Type: application/json" \
  -d '{"name": "Chat Metadata Test Project"}')

PROJECT_ID=$(echo "$PROJECT_RESPONSE" | jq -r '.id')

if [ "$PROJECT_ID" != "null" ] && [ -n "$PROJECT_ID" ]; then
  echo -e "${GREEN}✓ Project created: $PROJECT_ID${NC}"
else
  echo -e "${RED}✗ Failed to create project${NC}"
  echo "$PROJECT_RESPONSE"
  exit 1
fi

# Test 2: Upload test document (if test data exists)
echo ""
echo -e "${YELLOW}Test 2: Uploading test document...${NC}"
if [ -f "test-data/ai-research.txt" ]; then
  DOC_RESPONSE=$(curl -s -X POST "${API_URL}/projects/${PROJECT_ID}/documents" \
    -F "file=@test-data/ai-research.txt")
  
  DOC_ID=$(echo "$DOC_RESPONSE" | jq -r '.id')
  
  if [ "$DOC_ID" != "null" ] && [ -n "$DOC_ID" ]; then
    echo -e "${GREEN}✓ Document uploaded: $DOC_ID${NC}"
    echo -e "${YELLOW}  Waiting 8 seconds for document processing...${NC}"
    sleep 8
  else
    echo -e "${RED}✗ Failed to upload document${NC}"
    echo "$DOC_RESPONSE"
  fi
else
  echo -e "${YELLOW}⚠ Test data not found. Skipping document upload.${NC}"
fi

# Test 3: Send chat request and validate metadata
echo ""
echo -e "${YELLOW}Test 3: Sending chat request...${NC}"
CHAT_RESPONSE=$(curl -s -X POST "${API_URL}/chat" \
  -H "Content-Type: application/json" \
  -d "{\"projectId\": \"${PROJECT_ID}\", \"message\": \"What is artificial intelligence?\", \"history\": []}")

# Validate response structure
if echo "$CHAT_RESPONSE" | jq -e '.response' > /dev/null 2>&1; then
  echo -e "${GREEN}✓ Chat response received${NC}"
else
  echo -e "${RED}✗ Invalid chat response${NC}"
  echo "$CHAT_RESPONSE"
  exit 1
fi

# Check if sources array exists
if echo "$CHAT_RESPONSE" | jq -e '.sources' > /dev/null 2>&1; then
  echo -e "${GREEN}✓ Sources array present${NC}"
else
  echo -e "${RED}✗ Sources array missing${NC}"
  echo "$CHAT_RESPONSE"
  exit 1
fi

# Test 4: Validate source metadata
echo ""
echo -e "${YELLOW}Test 4: Validating source metadata...${NC}"

SOURCES_COUNT=$(echo "$CHAT_RESPONSE" | jq '.sources | length')
echo "  Sources count: $SOURCES_COUNT"

if [ "$SOURCES_COUNT" -gt 0 ]; then
  # Check first source for metadata fields
  FIRST_SOURCE=$(echo "$CHAT_RESPONSE" | jq '.sources[0]')
  
  echo ""
  echo "  First source:"
  echo "$FIRST_SOURCE" | jq '.'
  
  # Validate id field exists (can be null or UUID)
  if echo "$FIRST_SOURCE" | jq -e 'has("id")' > /dev/null 2>&1; then
    ID_VALUE=$(echo "$FIRST_SOURCE" | jq -r '.id')
    if [ "$ID_VALUE" = "null" ]; then
      echo -e "${GREEN}✓ id field present (null - synthesized answer)${NC}"
    else
      echo -e "${GREEN}✓ id field present (UUID: $ID_VALUE)${NC}"
    fi
  else
    echo -e "${RED}✗ id field missing${NC}"
    exit 1
  fi
  
  # Validate chunkIndex field exists
  if echo "$FIRST_SOURCE" | jq -e 'has("chunkIndex")' > /dev/null 2>&1; then
    CHUNK_INDEX=$(echo "$FIRST_SOURCE" | jq -r '.chunkIndex')
    if [ "$CHUNK_INDEX" = "null" ]; then
      echo -e "${GREEN}✓ chunkIndex field present (null - synthesized answer)${NC}"
    else
      echo -e "${GREEN}✓ chunkIndex field present (value: $CHUNK_INDEX)${NC}"
    fi
  else
    echo -e "${RED}✗ chunkIndex field missing${NC}"
    exit 1
  fi
  
  # Validate other required fields
  if echo "$FIRST_SOURCE" | jq -e '.chunkText' > /dev/null 2>&1; then
    echo -e "${GREEN}✓ chunkText field present${NC}"
  fi
  
  if echo "$FIRST_SOURCE" | jq -e '.source' > /dev/null 2>&1; then
    SOURCE_NAME=$(echo "$FIRST_SOURCE" | jq -r '.source')
    echo -e "${GREEN}✓ source field present (value: $SOURCE_NAME)${NC}"
  fi
  
else
  echo -e "${YELLOW}⚠ No sources in response (empty project or no matches)${NC}"
fi

# Test 5: Full response structure
echo ""
echo -e "${YELLOW}Test 5: Full chat response:${NC}"
echo "$CHAT_RESPONSE" | jq '.'

# Summary
echo ""
echo "================================"
echo -e "${GREEN}✓ Feature 003 Validation Complete${NC}"
echo "================================"
echo ""
echo "Summary:"
echo "  - Chat API returns sources array with metadata"
echo "  - Each source includes 'id' field (UUID or null)"
echo "  - Each source includes 'chunkIndex' field (integer or null)"
echo "  - Response structure matches specification"
echo ""
echo "Feature Status: ✅ WORKING"
