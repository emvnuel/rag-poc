# Implementation Plan: Chat Response Chunk Metadata Enhancement

**Branch**: `003-chat-chunk-metadata` | **Date**: 2025-11-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-chat-chunk-metadata/spec.md`

## Summary

Enhance the chat completion API response to include chunk identifiers (chunkIndex) and document identifiers (documentId) for each source used in generating responses. This enables users to trace AI responses back to specific document chunks for verification and citation validation. The data already exists in the SearchResult record; implementation focuses on ensuring proper JSON serialization and maintaining backward compatibility.

**Technical Approach**: Verify that ChatResponse already passes SearchResult objects to the sources array and that JSON serialization includes all fields (id, chunkIndex, chunkText, source, distance). Add integration tests to validate the response structure matches requirements. No schema changes or new data models required.

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Quarkus 3.28.4 (Jakarta REST, Hibernate ORM Panache), PostgreSQL with pgvector + Apache AGE  
**Storage**: PostgreSQL database with vector and graph extensions  
**Testing**: JUnit 5 + REST Assured for endpoint tests, `@QuarkusTest` for integration tests  
**Target Platform**: JVM application (Docker container deployment)  
**Project Type**: Single backend application (REST API)  
**Performance Goals**: Chat API response with first token <1 second, full response streaming <10 seconds  
**Constraints**: Backward compatibility required - existing API consumers must continue to work; zero breaking changes to ChatResponse structure  
**Scale/Scope**: Existing chat endpoint enhancement affecting ChatResponse, SearchResult serialization

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Initial Check**: ✅ PASSED (2025-11-15 - Pre-Phase 0)  
**Post-Design Check**: ✅ PASSED (2025-11-15 - Post-Phase 1)

### Code Quality Standards
- ✅ Use Jakarta EE annotations (`jakarta.ws.rs.*`) - Already in use throughout codebase
- ✅ Follow naming conventions (`*Resource`, `*Service`, `*Repository`) - ChatService, ChatResources follow convention
- ✅ Proper error handling with descriptive messages - Existing code has null checks and IllegalArgumentException
- ✅ Javadoc for public methods - Will add/verify for any modified methods
- ✅ Use `final` for immutability - SearchResult is already a record (immutable)
- ✅ Import organization - Will verify in changes

**Status**: ✅ PASS

### Testing Standards
- ✅ Tests written before implementation (TDD) - Will follow for new tests
- ✅ REST endpoint contract tests - Will add integration test for ChatResources
- ✅ Integration tests with happy path + 2 error scenarios - Will cover: sources with data, sources without data, synthesized answers
- ✅ JUnit 5 + REST Assured - Already used in ChatServiceTest
- ✅ `@QuarkusTest` for integration - Will use for endpoint test
- ✅ Independent tests with no shared state - Will ensure
- ✅ Mock external LLM/embedding APIs - Already mocked in tests

**Status**: ✅ PASS (tests to be written first)

### API Consistency
- ✅ Standard HTTP methods - Chat uses POST (appropriate for stateful operations)
- ✅ Consistent JSON response structure - ChatResponse already follows pattern with id, status, data fields
- ✅ UUID v7 for entity IDs - Documents already use UUID (spec confirms)
- ✅ ISO 8601 dates in UTC - Will verify in response
- ✅ Input validation at API boundary - Existing validation in place
- ⚠️ **IMPORTANT**: Backward compatibility - Adding fields to existing response structure; JSON serialization should ignore unknown fields in clients

**Status**: ✅ PASS (backward compatible addition)

### Performance Requirements
- ✅ Chat API response: First token <1 second - No change to response time (data already retrieved)
- ✅ No additional database queries - SearchResult already populated by SearchService
- ✅ Memory efficiency - No new objects created, just exposing existing fields

**Status**: ✅ PASS (negligible performance impact)

### Observability
- ✅ Structured logging - ChatService already logs key operations
- ✅ Include correlationId, projectId - Will verify in logs
- ✅ Track request rate, latency, error rate - Existing metrics infrastructure

**Status**: ✅ PASS

### Quality Gates
- ✅ Build success: `./mvnw clean package` - Will verify
- ✅ All tests passing: `./mvnw test` - Will ensure new tests pass
- ✅ Code coverage: >80% branch coverage - Will verify for modified code
- ✅ Documentation: Update API documentation if needed

**Status**: ✅ PASS

**Overall Gate Status**: ✅ PASS - No constitution violations. Feature is additive, maintains backward compatibility, and follows all established standards.

## Project Structure

### Documentation (this feature)

```text
specs/003-chat-chunk-metadata/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── ChatAPI.yaml    # OpenAPI spec for chat endpoint
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/main/java/br/edu/ifba/
├── chat/
│   ├── ChatMessage.java        # Existing - no changes needed
│   ├── ChatRequest.java         # Existing - no changes needed
│   ├── ChatResponse.java        # Review: verify SearchResult fields serialized
│   ├── ChatService.java         # Review: verify SearchResult passed correctly
│   └── ChatResources.java       # Existing - no changes needed
├── document/
│   ├── SearchResult.java        # Existing - contains id, chunkIndex, chunkText, source, distance
│   └── SearchService.java       # Existing - populates SearchResult
└── ...

src/test/java/br/edu/ifba/
├── chat/
│   ├── ChatServiceTest.java           # Existing - add tests for metadata presence
│   └── ChatResourcesIntegrationTest.java  # NEW - integration test for API contract
└── ...
```

**Structure Decision**: Single backend application structure. The feature only modifies the chat module response serialization. No new packages or services required. Focus is on verifying existing SearchResult fields are properly exposed in the API response.

## Complexity Tracking

> **No violations** - This feature is a straightforward enhancement that:
> - Uses existing data structures (SearchResult record)
> - Adds no new abstractions or patterns
> - Maintains backward compatibility
> - Requires only integration tests to verify serialization

**Complexity Budget**: WELL WITHIN LIMITS - Feature adds zero new complexity, only validates existing data flow.
