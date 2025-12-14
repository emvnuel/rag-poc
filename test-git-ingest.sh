#!/bin/bash

# Test script for Git repository ingestion endpoint
# Usage: ./test-git-ingest.sh [PROJECT_ID] [REPO_URL] [BRANCH]

set -e

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8080}"
PROJECT_ID="${1:-}"
REPO_URL="${2:-https://github.com/spring-projects/spring-petclinic.git}"
BRANCH="${3:-main}"

echo "====================================================="
echo "Git Repository Ingestion Test"
echo "====================================================="
echo "Base URL: $BASE_URL"
echo "Repository: $REPO_URL"
echo "Branch: $BRANCH"
echo "====================================================="

# Create project if PROJECT_ID is not provided
if [ -z "$PROJECT_ID" ]; then
    echo ""
    echo "Step 1: Creating new project..."
    PROJECT_RESPONSE=$(curl -s -X POST "$BASE_URL/projects" \
        -H "Content-Type: application/json" \
        -d '{"name": "Git Ingest Test Project"}')
    
    PROJECT_ID=$(echo "$PROJECT_RESPONSE" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
    echo "Created project with ID: $PROJECT_ID"
else
    echo ""
    echo "Using existing project ID: $PROJECT_ID"
fi

# Ingest repository
echo ""
echo "Step 2: Ingesting Git repository (all code files)..."
INGEST_URL="$BASE_URL/documents/projects/$PROJECT_ID/ingest-repo"
INGEST_URL="$INGEST_URL?repoUrl=$(echo $REPO_URL | sed 's/:/%3A/g' | sed 's#/#%2F#g')"
INGEST_URL="$INGEST_URL&branch=$BRANCH"

echo "Request URL: $INGEST_URL"
echo ""

INGEST_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "$INGEST_URL")

HTTP_STATUS=$(echo "$INGEST_RESPONSE" | tr -d '\n' | sed -e 's/.*HTTP_STATUS://')
RESPONSE_BODY=$(echo "$INGEST_RESPONSE" | sed -e 's/HTTP_STATUS:.*//')

echo "Response:"
echo "$RESPONSE_BODY" | python3 -m json.tool || echo "$RESPONSE_BODY"
echo ""
echo "HTTP Status: $HTTP_STATUS"

if [ "$HTTP_STATUS" -eq 200 ]; then
    echo ""
    echo "====================================================="
    echo "SUCCESS! Repository ingested successfully"
    echo "====================================================="
    echo ""
    echo "Next steps:"
    echo "1. Wait for documents to be processed (async)"
    echo "2. Query the project: curl \"$BASE_URL/projects/$PROJECT_ID/chat?q=your+question\""
    echo "3. List documents: curl \"$BASE_URL/projects/$PROJECT_ID/documents\""
else
    echo ""
    echo "====================================================="
    echo "FAILED! HTTP Status: $HTTP_STATUS"
    echo "====================================================="
    exit 1
fi
