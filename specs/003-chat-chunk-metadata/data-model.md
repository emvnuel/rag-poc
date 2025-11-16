# Data Model: Chat Response Chunk Metadata Enhancement

**Date**: 2025-11-15  
**Feature**: 003-chat-chunk-metadata  
**Purpose**: Define data structures and their relationships

## Overview

This feature exposes existing metadata fields (`id`, `chunkIndex`) in the `SearchResult` entity through the chat API response. No new entities or database schemas are required - this is purely a validation exercise to ensure proper JSON serialization.

---

## Entities

### ChatResponse (Existing - No Changes)

**Purpose**: Response object returned by the chat completion endpoint

**Fields**:
| Field | Type | Required | Description | Validation |
|-------|------|----------|-------------|------------|
| `response` | `String` | Yes | AI-generated response text | Non-null |
| `messages` | `List<ChatMessage>` | Yes | Conversation history including user and assistant messages | Non-null, may be empty |
| `sources` | `List<SearchResult>` | Yes | Source chunks used to generate response (KEY FIELD FOR THIS FEATURE) | Non-null, may be empty |
| `model` | `String` | Yes | LLM model identifier used for generation | Non-null |
| `totalDuration` | `Long` | No | Total processing time in milliseconds | Nullable |
| `promptEvalCount` | `Long` | No | Number of tokens in prompt | Nullable |
| `evalCount` | `Long` | No | Number of tokens in completion | Nullable |

**Relationships**:
- Contains 0..N `SearchResult` objects in `sources` array
- Contains 0..N `ChatMessage` objects in `messages` array

**State**: Immutable (Java record)

**Notes**: 
- This entity is already correctly structured
- The `sources` field already contains `SearchResult` objects with all required metadata
- No code changes needed

---

### SearchResult (Existing - Feature Focus)

**Purpose**: Represents a single document chunk used as context for AI response generation

**Fields**:
| Field | Type | Required | Description | Validation | Edge Cases |
|-------|------|----------|-------------|------------|------------|
| `id` | `UUID` | No | **Document identifier** - unique ID of the source document | Valid UUID v7 or null | Null for synthesized answers (e.g., LightRAG Answer) |
| `chunkText` | `String` | Yes | Text content of the chunk | Non-null, non-empty | May contain multi-line text |
| `chunkIndex` | `Integer` | No | **Chunk position** within the document (0-based or 1-based) | Non-negative integer or null | Null for synthesized answers or non-chunked sources |
| `source` | `String` | Yes | Human-readable source label (e.g., "document.pdf", "LightRAG Answer") | Non-null, non-empty | Special value "LightRAG Answer" identifies synthesized content |
| `distance` | `Double` | No | Similarity score from vector search (0.0 = identical, 1.0 = dissimilar) | 0.0-1.0 or null | Null for synthesized answers |

**Relationships**:
- Belongs to 0..1 `Document` (via `id` field)
- Referenced by `ChatResponse.sources`

**State**: Immutable (Java record)

**Notes**:
- **This is the PRIMARY entity for this feature**
- Fields `id` and `chunkIndex` are the focus - must be serialized to JSON
- Already correctly implemented in code; feature is about validation and documentation

---

### ChatMessage (Existing - No Changes)

**Purpose**: Represents a single message in the conversation history

**Fields**:
| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `role` | `String` | Yes | Message sender: "user", "assistant", or "system" |
| `content` | `String` | Yes | Message text content |

**Relationships**: None (simple DTO)

**State**: Immutable (Java record)

---

## Data Flow

```text
User Request
    ↓
ChatResources (REST endpoint)
    ↓
ChatService.chat(ChatRequest)
    ↓
SearchService.search(query, projectId)
    ↓
SearchResponse { List<SearchResult> }
    ↓ (populate sources)
ChatResponse {
    response: "...",
    messages: [...],
    sources: [
        SearchResult {
            id: UUID("a1b2c3d4..."),          ← Feature requirement: expose this
            chunkIndex: 5,                     ← Feature requirement: expose this
            chunkText: "...",
            source: "document.pdf",
            distance: 0.85
        },
        SearchResult {
            id: null,                          ← Edge case: synthesized answer
            chunkIndex: null,
            chunkText: "...",
            source: "LightRAG Answer",
            distance: null
        }
    ],
    model: "gpt-4",
    ...
}
    ↓
JSON Serialization (Jackson)
    ↓
HTTP Response to Client
```

---

## Field Population Rules

### SearchResult.id (documentId)

**Population Logic**:
- **Document chunks**: Populated with the UUID of the source document from database
- **Synthesized answers**: `null` (no backing document)
- **Source**: `SearchService` queries the database and populates this field

**Business Rules**:
- If `id` is `null`, the source is not directly citable to a specific document
- If `id` is not `null`, clients can use it to fetch the full document or link to it

### SearchResult.chunkIndex

**Population Logic**:
- **Chunked documents**: Populated with the sequential position (0-based or 1-based) of the chunk within the document
- **Non-chunked sources**: `null` (no chunking applied)
- **Synthesized answers**: `null` (no backing document)
- **Source**: `TextChunker` assigns indices during document processing; stored in database

**Business Rules**:
- If `chunkIndex` is not `null`, clients can use it with `id` to construct citations like `[UUID:chunk-N]`
- If `chunkIndex` is `null`, the source cannot be cited at chunk-level granularity

---

## JSON Schema

### ChatResponse JSON Example

```json
{
  "response": "Machine learning is a subset of artificial intelligence...",
  "messages": [
    {
      "role": "user",
      "content": "What is machine learning?"
    },
    {
      "role": "assistant",
      "content": "Machine learning is a subset of artificial intelligence..."
    }
  ],
  "sources": [
    {
      "id": "a1b2c3d4-5678-9abc-def0-123456789abc",
      "chunkText": "Machine learning is a subset of artificial intelligence that focuses on...",
      "chunkIndex": 5,
      "source": "ai-research.pdf",
      "distance": 0.85
    },
    {
      "id": "e5f6a7b8-9012-3cde-f456-789012345678",
      "chunkText": "Neural networks are a key component of modern ML systems...",
      "chunkIndex": 12,
      "source": "ml-fundamentals.pdf",
      "distance": 0.78
    },
    {
      "id": null,
      "chunkText": "Based on the knowledge graph, machine learning encompasses...",
      "chunkIndex": null,
      "source": "LightRAG Answer",
      "distance": null
    }
  ],
  "model": "gpt-4",
  "totalDuration": 1234,
  "promptEvalCount": 150,
  "evalCount": 75
}
```

---

## Edge Cases

### Case 1: Empty Sources Array

**Scenario**: Chat request with no matching documents

**Expected Behavior**:
```json
{
  "response": "I don't have information about that in the available documents.",
  "messages": [...],
  "sources": [],
  "model": "gpt-4",
  ...
}
```

**Validation**: `sources` array is empty but not null

---

### Case 2: Synthesized Answer (Null IDs)

**Scenario**: LightRAG generates an answer from the knowledge graph without direct document chunks

**Expected Behavior**:
```json
{
  "sources": [
    {
      "id": null,
      "chunkText": "Based on entities: MIT, Stanford...",
      "chunkIndex": null,
      "source": "LightRAG Answer",
      "distance": null
    }
  ]
}
```

**Validation**: `id` and `chunkIndex` are explicitly `null`, not omitted

---

### Case 3: Mixed Sources (Documents + Synthesized)

**Scenario**: Chat response uses both document chunks and synthesized answer

**Expected Behavior**:
```json
{
  "sources": [
    {
      "id": null,
      "chunkText": "Synthesized context from knowledge graph...",
      "chunkIndex": null,
      "source": "LightRAG Answer",
      "distance": null
    },
    {
      "id": "a1b2c3d4...",
      "chunkText": "Direct excerpt from document...",
      "chunkIndex": 5,
      "source": "document.pdf",
      "distance": 0.85
    }
  ]
}
```

**Validation**: Array contains heterogeneous sources; each has consistent null handling

---

## Database Schema

**No changes required** - this feature does not modify any database tables. The data already exists in the vector storage and is retrieved by `SearchService`.

---

## Validation Rules

### Request Validation (Existing)
- Chat request must include valid `projectId` (UUID)
- Chat request must include non-empty `message` string

### Response Validation (New Tests)
- `ChatResponse.sources` must be non-null (may be empty array)
- Each `SearchResult` in `sources` must have:
  - `chunkText`: non-null, non-empty string
  - `source`: non-null, non-empty string
  - `id`: null OR valid UUID string in JSON
  - `chunkIndex`: null OR non-negative integer
  - `distance`: null OR number between 0.0 and 1.0

---

## Summary

**No new entities or schemas** - this feature validates that existing entities are properly serialized to JSON:
1. ✅ `SearchResult` record already has `id` and `chunkIndex` fields
2. ✅ `ChatResponse` already includes `List<SearchResult> sources`
3. ✅ Jackson serialization automatically handles records
4. 🎯 **Implementation Focus**: Write integration tests to verify JSON structure
5. 📝 **Documentation Focus**: Update API contracts and quickstart guide
