# Feature Specification: Chat Response Chunk Metadata Enhancement

**Feature Branch**: `003-chat-chunk-metadata`  
**Created**: 2025-11-15  
**Status**: Draft  
**Input**: User description: "Chat completion endpoint should return the id of the chunk and documentId"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Source Traceability for Chat Responses (Priority: P1)

As a user of the chat system, I need to know which specific document chunks informed the AI's response so that I can verify the accuracy of the information and access the original source material for deeper understanding.

**Why this priority**: This is the core value proposition of a RAG system - enabling users to trace AI responses back to source documents. Without this, users cannot verify accuracy or trust the system's answers.

**Independent Test**: Can be fully tested by sending a chat request and verifying that the response includes both chunk identifiers and document identifiers for each source used. Delivers immediate value by enabling source verification and citation tracking.

**Acceptance Scenarios**:

1. **Given** a user sends a chat request that returns results from document sources, **When** the chat response is returned, **Then** each source in the response includes the chunk identifier (chunkIndex) and the document identifier (id)
2. **Given** a user sends a chat request that uses multiple chunks from the same document, **When** the chat response is returned, **Then** each chunk is individually identified with its unique chunk number and shared document identifier
3. **Given** a user sends a chat request with no matching sources, **When** the chat response is returned, **Then** the sources list is empty and no chunk or document identifiers are present

---

### User Story 2 - Citation Verification (Priority: P2)

As a user reviewing AI-generated responses, I need the ability to match inline citations in the response text (e.g., [UUID:chunk-5]) to the corresponding source metadata so that I can quickly locate and verify specific claims.

**Why this priority**: Enhances user trust and system transparency by providing a clear link between citations and source metadata. This builds on P1 by making the metadata actionable for citation verification.

**Independent Test**: Can be tested by sending a chat request, extracting citation references from the response text (e.g., [a1b2c3d4:chunk-5]), and matching them against the documentId and chunkIndex in the sources array. Delivers value by enabling automated citation validation.

**Acceptance Scenarios**:

1. **Given** a chat response contains inline citations like [UUID:chunk-N], **When** a user examines the sources array, **Then** each cited UUID matches a documentId in the sources array, and each chunk-N matches a chunkIndex
2. **Given** a chat response with multiple citations from different documents, **When** the user cross-references the sources, **Then** all citations can be traced to their corresponding source entry with matching documentId and chunkIndex

---

### Edge Cases

- What happens when a source has no chunk index (legacy data or non-chunked sources)? The system should include the documentId but may omit chunkIndex or set it to null
- How does the system handle LightRAG synthesized answers (which have no document ID)? These sources should be identifiable by null documentId and a distinct source label like "LightRAG Answer"
- What happens when the LLM invents citations that don't exist in the sources? The existing post-processing logic removes invalid citations, and the sources array will not include entries for invented citations

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Chat response MUST include a sources array where each source contains a documentId field representing the unique identifier of the originating document
- **FR-002**: Chat response MUST include a chunkIndex field in each source entry representing the sequential chunk number within the document (0-based or 1-based as per current implementation)
- **FR-003**: Sources array MUST maintain the current SearchResult structure, ensuring backward compatibility with existing fields (id, chunkText, source, distance)
- **FR-004**: System MUST handle sources without chunk indices gracefully by including documentId but allowing chunkIndex to be null or absent
- **FR-005**: System MUST distinguish between document sources (with documentId) and synthesized answers (without documentId) in the sources array
- **FR-006**: Chat response format MUST remain consistent with the existing ChatResponse structure to avoid breaking existing API consumers

### Key Entities

- **ChatResponse**: The primary response object returned by the chat endpoint, containing the AI-generated text, conversation history, source metadata, and usage statistics
- **SearchResult**: Represents a single source chunk used to inform the AI response, containing documentId (id), chunkText, chunkIndex, source name, and similarity distance
- **Document**: The original document from which chunks are extracted, identified by a unique UUID (documentId)
- **Chunk**: A segment of text extracted from a document, identified by its document ID and sequential index within that document

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of chat responses that include document-based sources contain both documentId and chunkIndex for each source (when chunk index is available)
- **SC-002**: Users can trace any citation in a chat response back to its source document and chunk within 5 seconds by matching the citation identifier to the sources array
- **SC-003**: System maintains backward compatibility with existing API consumers, with zero breaking changes to the ChatResponse structure
- **SC-004**: Source metadata is complete and accurate for 100% of responses, enabling reliable citation verification

## Assumptions

1. **Current Implementation**: The SearchResult record already contains the necessary fields (id for documentId, chunkIndex for chunk number) and only needs to be surfaced in the API response
2. **Chunk Indexing**: Documents are chunked sequentially, and the chunkIndex represents the position of the chunk within the document (likely 0-based or 1-based indexing)
3. **Document Identification**: Each document has a unique UUID identifier that persists across the system
4. **API Stability**: The ChatResponse structure can be extended without breaking existing clients (assuming JSON serialization ignores unknown fields or clients use permissive deserialization)
5. **Source Ordering**: The sources array maintains the same ordering as returned by SearchService, with potential special cases like "LightRAG Answer" at index 0

## Non-Functional Considerations

- **Performance**: Adding these fields to the response should have negligible performance impact since the data already exists in SearchResult
- **API Documentation**: API documentation should be updated to reflect the new fields and their purpose
- **Backward Compatibility**: Existing API consumers should continue to work without modification; new fields are additive

## Out of Scope

- Modifying the chunk extraction or indexing logic
- Changing the citation format or post-processing behavior
- Adding new search or retrieval capabilities
- UI/frontend changes to display the metadata (assumes API consumers will handle presentation)
