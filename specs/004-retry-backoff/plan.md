# Implementation Plan: Retry Logic with Exponential Backoff

**Branch**: `004-retry-backoff` | **Date**: 2025-01-25 | **Spec**: [spec.md](./spec.md)  
**Input**: Feature specification from `/specs/004-retry-backoff/spec.md`

## Summary

Implement automatic retry logic with exponential backoff for database operations in the storage layer. This addresses a critical gap identified when comparing with the official LightRAG Python implementation, which includes robust retry mechanisms via the `tenacity` library. The feature will transparently handle transient database failures (connection timeouts, pool exhaustion, network errors) without requiring changes to higher-level code.

**Technical Approach**: Use **Resilience4j** library (recommended by user) integrated with Quarkus for retry logic. Apply retry decorators at the storage layer (`AgeGraphStorage` and `PgVectorStorage`) to transparently protect all database operations. Configuration will be externalized via `application.properties`.

**Decision**: Resilience4j was chosen over custom implementation because:
1. Battle-tested library with extensive production usage
2. Native Quarkus integration via `quarkus-resilience4j` extension
3. Provides additional patterns (circuit breaker) for future enhancement
4. Reduces custom code maintenance burden

## Technical Context

**Language/Version**: Java 21  
**Primary Dependencies**: Quarkus 3.28.4, Resilience4j (via quarkus-smallrye-fault-tolerance), PostgreSQL 14+, Apache AGE, pgvector  
**Storage**: `AgeGraphStorage.java` for graph ops, `PgVectorStorage.java` for vector ops  
**Testing**: JUnit 5 with `@QuarkusTest`, Testcontainers for PostgreSQL  
**Target Platform**: Linux server (Docker containerized)  
**Project Type**: Single backend project (Quarkus)  
**Performance Goals**: Recovery from single transient failure <2s, max retry duration <30s  
**Constraints**: Database operations only (no external APIs per scope), circuit breaker out of scope for MVP  
**Scale/Scope**: Support 100 concurrent document uploads, maintain existing P95 latency targets

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Code Quality Standards ✅

- **Jakarta EE compliance**: Feature uses existing Jakarta patterns, no new REST endpoints
- **Naming conventions**: `RetryConfig.java` follows project standards; annotations follow Quarkus/Resilience4j conventions
- **Error handling**: Wrap original exceptions with context, preserve stack trace per Constitution requirements
- **Javadoc**: All public methods documented with `@param` and `@return` tags
- **Cyclomatic complexity**: Retry logic via annotations keeps complexity low (<5)

### II. Testing Standards & Test-First Development ✅

- **TDD required**: Tests MUST be written before implementation
- **Test coverage targets**:
  - Unit tests for retry configuration validation (>80% branch coverage)
  - Integration tests simulating database failures with Testcontainers
  - Contract tests for error classification behavior
- **Test independence**: Each test isolates retry behavior, no shared state
- **Testcontainers**: Use PostgreSQL container for integration tests

### III. API Consistency & User Experience ✅

- **No REST API changes**: Feature is internal storage layer only
- **Transparent to callers**: Higher-level code unchanged, error responses unchanged
- **Configuration**: Standard Quarkus `application.properties` patterns

### IV. Performance Requirements ✅

- **Constitution §IV**: "Retry strategy: 3 attempts with exponential backoff (1s, 2s, 4s)" - ALIGNS WITH THIS FEATURE
- **Max retry duration**: 30 seconds (bounded user wait time per SC-003)
- **Single retry overhead**: <2 seconds for first retry attempt (per SC-002)
- **Jitter**: Random delay variance to prevent thundering herd

### V. Observability & Debugging ✅

- **Structured logging**: INFO logs for retry attempts with context per Constitution §V
- **Required fields**: correlationId, operation, duration, error will be included
- **Log levels**: INFO for retry attempts, WARN for exhausted retries, ERROR preserved from original exception
- **Metrics**: Retry count available via Resilience4j metrics (future enhancement)

### Performance Standards ✅

- **Database Queries**: Retry applies at storage layer, does not change query patterns
- **LLM API Calls**: Constitution already specifies "3 attempts with exponential backoff" - this feature implements it for database layer
- **Connection checkout timeout**: 2 seconds (existing) - retry helps when pool temporarily exhausted

### Quality Gates ✅

- **Build**: `./mvnw clean package` must pass
- **Tests**: `./mvnw test` (unit + integration) must pass
- **Coverage**: >80% branch coverage via Jacoco
- **No new dependencies with CVEs**: Resilience4j is well-maintained

## Project Structure

### Documentation (this feature)

```text
specs/004-retry-backoff/
├── plan.md              # This file
├── research.md          # Phase 0: Resilience4j vs alternatives, error classification
├── data-model.md        # Phase 1: RetryConfig, TransientErrorClassifier
├── quickstart.md        # Phase 1: Developer guide for configuration
├── contracts/           # Phase 1: N/A (no new REST APIs)
└── checklists/
    └── requirements.md  # Spec validation checklist
```

### Source Code (repository root)

```text
src/main/java/br/edu/ifba/
├── lightrag/
│   ├── utils/
│   │   ├── TransientErrorClassifier.java  # NEW: Error classification utility
│   │   └── RetryLoggingEventListener.java # NEW: Logging for retry events
│   └── storage/
│       └── impl/
│           ├── AgeGraphStorage.java       # UPDATE: Add @Retry annotations
│           └── PgVectorStorage.java       # UPDATE: Add @Retry annotations

src/test/java/br/edu/ifba/
├── lightrag/
│   ├── utils/
│   │   └── TransientErrorClassifierTest.java  # NEW: Error classification tests
│   └── storage/
│       └── impl/
│           ├── AgeGraphStorageRetryIT.java    # NEW: Integration tests
│           └── PgVectorStorageRetryIT.java    # NEW: Integration tests

src/main/resources/
└── application.properties                     # UPDATE: Add retry configuration
```

**Structure Decision**: Single backend project (Option 1) - this is a storage layer enhancement affecting existing Quarkus Java backend. No frontend changes needed. Test structure follows existing pattern: unit tests under `src/test/java` matching source package structure, integration tests with `IT` suffix.

## Complexity Tracking

> **No violations** - feature aligns with all constitution principles.

**Complexity Justification**:
- **Added**: Resilience4j dependency (well-maintained, Quarkus-native)
- **Reason**: Constitution §IV already mandates "3 attempts with exponential backoff" for LLM calls; extending to database layer is consistent
- **Alternative rejected**: Custom implementation would require more code to maintain and lacks battle-tested edge case handling

## Phase 0: Research & Discovery

**Status**: PENDING - See research.md after completion

### Research Questions

1. **Resilience4j vs SmallRye Fault Tolerance**: Which is preferred in Quarkus 3.x?
2. **Annotation Placement**: Method-level vs class-level retry configuration
3. **Error Classification**: How to configure retryOn/ignoreOn for PostgreSQL errors
4. **Async Support**: Does retry work with CompletableFuture returns?
5. **Configuration Hot-Reload**: Does Quarkus support runtime retry config changes?

### Expected Findings

- Quarkus uses SmallRye Fault Tolerance (implements MicroProfile Fault Tolerance spec)
- Both annotation-based (`@Retry`) and programmatic APIs available
- Can specify `retryOn` exception classes for transient errors
- Works with async methods via proper method signatures
- Config changes via `application.properties` require restart (SC-007 may need workaround)

## Phase 1: Design & Data Model

**Prerequisites**: research.md complete

### 1.1 Data Model

See `data-model.md` for:
- TransientErrorClassifier utility class
- Retry configuration properties schema
- Exception hierarchy for retry decisions

### 1.2 Contracts

No new REST APIs - internal storage layer change only.

### 1.3 Quickstart Guide

See `quickstart.md` for:
- Configuration examples
- Testing retry behavior
- Troubleshooting guide
- Tuning recommendations

## Next Steps

1. **Execute Phase 0 Research**: Validate Resilience4j/SmallRye choice, document findings
2. **Create research.md**: Document decision rationale and alternatives
3. **Execute Phase 1 Design**: Create data-model.md, quickstart.md
4. **Update Agent Context**: Run update-agent-context.sh
5. **Proceed to `/speckit.tasks`**: Generate detailed task breakdown

**Estimated Timeline**:
- Phase 0 Research: 0.5 day
- Phase 1 Design: 0.5 day
- Implementation: 2-3 days (code + tests)
- Total: ~3-4 days for complete feature delivery

**Blockers**: None - all dependencies internal
