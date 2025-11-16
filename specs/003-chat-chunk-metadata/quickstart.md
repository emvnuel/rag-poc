# Quickstart: Chat Response Chunk Metadata

**Feature**: 003-chat-chunk-metadata  
**Purpose**: Developer guide for using chunk metadata in chat responses

## Overview

The chat API now includes document identifiers (`id`) and chunk indices (`chunkIndex`) in the response, enabling you to:

- **Trace AI responses** back to specific document chunks
- **Verify citations** by matching inline references (e.g., `[UUID:chunk-5]`) to sources
- **Build citation UIs** showing which documents informed the response

## Quick Example

### Request

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "a1b2c3d4-5678-9abc-def0-123456789abc",
    "message": "What is machine learning?",
    "history": []
  }'
```

### Response (Simplified)

```json
{
  "response": "Machine learning is a subset of AI [a1b2c3d4...:chunk-5].",
  "sources": [
    {
      "id": "a1b2c3d4-5678-9abc-def0-123456789abc",
      "chunkIndex": 5,
      "chunkText": "Machine learning is a subset of artificial intelligence...",
      "source": "ai-research.pdf",
      "distance": 0.85
    }
  ],
  "model": "gpt-4",
  ...
}
```

**Key Fields**:
- `sources[].id`: Document UUID (use to fetch full document or build links)
- `sources[].chunkIndex`: Chunk position within document (enables precise citations)

---

## Use Cases

### 1. Source Traceability

**Goal**: Show users which documents were used to generate the response.

```javascript
// JavaScript/TypeScript example
async function chat(projectId, message) {
  const response = await fetch('/api/v1/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ projectId, message, history: [] })
  });
  
  const data = await response.json();
  
  // Extract unique document IDs
  const documentIds = [...new Set(
    data.sources
      .filter(s => s.id !== null)  // Exclude synthesized answers
      .map(s => s.id)
  )];
  
  console.log('Response used', documentIds.length, 'documents');
  
  return {
    answer: data.response,
    sourceDocuments: documentIds
  };
}
```

---

### 2. Citation Verification

**Goal**: Match inline citations in the response to actual source chunks.

```python
# Python example
import re
from typing import List, Dict

def verify_citations(response: Dict) -> Dict[str, bool]:
    """
    Verify that all citations in the response text exist in the sources array.
    
    Returns a dict mapping citation IDs to verification status (True/False).
    """
    # Extract citations from response text (format: [UUID:chunk-N])
    citation_pattern = r'\[([a-f0-9-]+):chunk-(\d+)\]'
    citations = re.findall(citation_pattern, response['response'])
    
    # Build a set of valid source identifiers
    valid_sources = set()
    for source in response['sources']:
        if source['id'] and source['chunkIndex'] is not None:
            valid_sources.add((source['id'], source['chunkIndex']))
    
    # Verify each citation
    verification = {}
    for doc_id, chunk_idx in citations:
        citation_key = f"[{doc_id}:chunk-{chunk_idx}]"
        is_valid = (doc_id, int(chunk_idx)) in valid_sources
        verification[citation_key] = is_valid
    
    return verification

# Usage
chat_response = {...}  # Response from API
results = verify_citations(chat_response)
print(f"Citation verification: {results}")
# Output: {"[a1b2c3d4...:chunk-5]": True, "[invalid-id:chunk-99]": False}
```

---

### 3. Building a Citation UI

**Goal**: Display sources with clickable links to original document chunks.

```tsx
// React/TypeScript example
interface Source {
  id: string | null;
  chunkText: string;
  chunkIndex: number | null;
  source: string;
  distance: number | null;
}

interface ChatResponseData {
  response: string;
  sources: Source[];
  model: string;
}

function CitationList({ sources }: { sources: Source[] }) {
  // Filter out synthesized answers (null IDs)
  const citableSources = sources.filter(s => s.id !== null);
  
  return (
    <div className="citations">
      <h3>Sources ({citableSources.length})</h3>
      {citableSources.map((source, index) => (
        <div key={index} className="citation-item">
          <strong>{source.source}</strong>
          {source.chunkIndex !== null && (
            <span className="chunk-badge">Chunk {source.chunkIndex}</span>
          )}
          <p className="chunk-preview">{source.chunkText.substring(0, 200)}...</p>
          <a href={`/documents/${source.id}/chunks/${source.chunkIndex}`}>
            View full document →
          </a>
        </div>
      ))}
    </div>
  );
}

// Usage in chat component
function ChatMessage({ response }: { response: ChatResponseData }) {
  return (
    <div>
      <div className="response-text">{response.response}</div>
      <CitationList sources={response.sources} />
    </div>
  );
}
```

---

### 4. Handling Edge Cases

**Goal**: Gracefully handle sources without document IDs (synthesized answers).

```java
// Java example
public class ChatResponseHandler {
    
    public record SourceInfo(
        String displayName,
        boolean isCitable,
        String citationId
    ) {}
    
    public List<SourceInfo> processeSources(ChatResponse response) {
        return response.sources().stream()
            .map(source -> {
                if (source.id() == null) {
                    // Synthesized answer - not directly citable
                    return new SourceInfo(
                        source.source(),  // "LightRAG Answer"
                        false,
                        null
                    );
                } else {
                    // Document source - citable
                    String citationId = source.chunkIndex() != null
                        ? String.format("[%s:chunk-%d]", source.id(), source.chunkIndex())
                        : String.format("[%s]", source.id());
                    
                    return new SourceInfo(
                        source.source(),  // "document.pdf"
                        true,
                        citationId
                    );
                }
            })
            .toList();
    }
}
```

---

## API Integration Patterns

### Pattern 1: Simple Chat (Basic Integration)

```bash
# Just send a message and display the response
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "'$PROJECT_ID'",
    "message": "Your question here"
  }' | jq '.response'
```

### Pattern 2: Chat with Source Display

```bash
# Extract response and sources
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "'$PROJECT_ID'",
    "message": "Your question here"
  }' | jq '{
    answer: .response,
    sources: .sources | map({
      document: .source,
      documentId: .id,
      chunkIndex: .chunkIndex
    })
  }'
```

### Pattern 3: Citation-Aware Chat

```bash
# Full response with citation validation
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "'$PROJECT_ID'",
    "message": "Your question here"
  }' | jq '{
    answer: .response,
    citations: [.response | scan("\\[([a-f0-9-]+):chunk-(\\d+)\\]"; "g")],
    availableSources: .sources | map(select(.id != null)) | map({
      id: .id,
      chunkIndex: .chunkIndex,
      source: .source
    })
  }'
```

---

## Testing Locally

### Prerequisites

1. Start the application: `./mvnw quarkus:dev`
2. Create a project: 
   ```bash
   PROJECT_ID=$(curl -X POST http://localhost:8080/api/v1/projects \
     -H "Content-Type: application/json" \
     -d '{"name": "Test Project"}' | jq -r '.id')
   ```
3. Upload a document:
   ```bash
   curl -X POST http://localhost:8080/api/v1/projects/$PROJECT_ID/documents \
     -F "file=@test-data/ai-research.txt"
   ```

### Test Chat with Metadata

```bash
# Send a chat request
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d "{
    \"projectId\": \"$PROJECT_ID\",
    \"message\": \"What is artificial intelligence?\"
  }" | jq '.'

# Expected output structure:
# {
#   "response": "Artificial intelligence is...",
#   "sources": [
#     {
#       "id": "abc123...",
#       "chunkIndex": 0,
#       "chunkText": "...",
#       "source": "ai-research.txt",
#       "distance": 0.92
#     }
#   ],
#   ...
# }
```

### Verify Metadata Presence

```bash
# Check that all document sources have IDs and chunk indices
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d "{
    \"projectId\": \"$PROJECT_ID\",
    \"message\": \"Test query\"
  }" | jq '.sources[] | select(.id != null) | {id, chunkIndex, source}'

# Should output entries like:
# {
#   "id": "abc123...",
#   "chunkIndex": 0,
#   "source": "ai-research.txt"
# }
```

---

## Migration Notes

### For Existing API Consumers

✅ **Backward Compatible** - This change is additive only. Existing clients will continue to work without modification.

**What Changed**:
- `SearchResult` now includes `id` and `chunkIndex` fields in JSON responses
- These fields were always present in the backend but may not have been serialized before

**What Didn't Change**:
- Request format (no changes to `ChatRequest`)
- Response structure (no fields removed or renamed)
- Error handling (same error codes and messages)
- API endpoints (no URL changes)

### Updating Your Client

**If you want to use the new metadata**:

1. Update your response type definitions to include the new fields:
   ```typescript
   interface SearchResult {
     id: string | null;          // NEW
     chunkIndex: number | null;  // NEW
     chunkText: string;
     source: string;
     distance: number | null;
   }
   ```

2. Handle null values for synthesized answers:
   ```typescript
   const citableSources = response.sources.filter(s => s.id !== null);
   ```

3. Build citations using the new fields:
   ```typescript
   const citation = `[${source.id}:chunk-${source.chunkIndex}]`;
   ```

**If you don't need the metadata**:
- No changes required! Your existing code will continue to work as-is.

---

## Troubleshooting

### Issue: `id` is always null

**Cause**: Project has no documents, or query returns only synthesized answers.

**Solution**: Upload documents to the project and ensure they're processed:
```bash
# Check document status
curl http://localhost:8080/api/v1/projects/$PROJECT_ID/documents | jq '.[] | {id, status}'

# Wait for status to be "COMPLETED" before querying
```

### Issue: `chunkIndex` is null for document sources

**Cause**: Document was uploaded before chunking was implemented, or chunking failed.

**Solution**: Re-upload the document or check processing logs:
```bash
# Check logs for document processing errors
docker logs -f rag-saas-app | grep "ERROR.*DocumentProcessorJob"
```

### Issue: Citations in response don't match sources

**Cause**: LLM is inventing citations (post-processing should remove these).

**Solution**: Verify post-processing is working correctly. Check `ChatService.java:95-104` for citation removal logic.

---

## Next Steps

- **API Documentation**: Full contract available at `contracts/ChatAPI.yaml`
- **Data Model**: Detailed entity definitions at `data-model.md`
- **Implementation**: See `tasks.md` (generated by `/speckit.tasks`) for development steps

## Support

For issues or questions:
- Check logs: `docker logs -f rag-saas-app`
- Review API contract: `specs/003-chat-chunk-metadata/contracts/ChatAPI.yaml`
- Test with curl examples above to verify metadata presence
