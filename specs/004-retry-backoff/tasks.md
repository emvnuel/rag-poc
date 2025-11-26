# Tasks: Retry Logic with Exponential Backoff

**Input**: Design documents from `/specs/004-retry-backoff/`  
**Prerequisites**: plan.md, spec.md, research.md, data-model.md  
**Architecture**: SmallRye Fault Tolerance with `@Retry` annotations

**Tests**: Constitution §II requires TDD - tests MUST be written before implementation

**Organization**: Tasks grouped by user story for independent implementation.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files)
- **[Story]**: US1/US2/US3/US4 maps to user stories

## Path Conventions

- **Source**: `src/main/java/br/edu/ifba/`
- **Tests**: `src/test/java/br/edu/ifba/`
- **Config**: `src/main/resources/application.properties`

---

## Phase 1: Setup (Foundation)

**Purpose**: Add dependency and configuration

- [x] T001 Add `quarkus-smallrye-fault-tolerance` dependency to `pom.xml`
- [x] T002 [P] Add retry configuration to `src/main/resources/application.properties`
- [x] T003 [P] Add retry configuration to `src/test/resources/application.properties`

---

## Phase 2: Core Components (Blocking)

**Purpose**: Create utility classes - MUST complete before user stories

### Exception Classification

- [x] T004 Create `TransientSQLExceptionPredicate.java` in `src/main/java/br/edu/ifba/lightrag/utils/`
- [x] T005 Implement `test(Throwable)` method with SQLSTATE prefix matching
- [x] T006 Add cause chain traversal logic
- [x] T007 Add Javadoc with PostgreSQL error code reference
- [x] T008 Create `TransientSQLExceptionPredicateTest.java` in `src/test/java/br/edu/ifba/lightrag/utils/`
- [x] T009 [P] Write test for SQLSTATE `08xxx` (connection) classification
- [x] T010 [P] Write test for SQLSTATE `40xxx` (deadlock) classification
- [x] T011 [P] Write test for SQLSTATE `53xxx` (resources) classification
- [x] T012 [P] Write test for SQLTransientConnectionException
- [x] T013 [P] Write test for SQLTimeoutException
- [x] T014 [P] Write test for permanent errors (23xxx, 42xxx) returning false
- [x] T015 [P] Write test for cause chain traversal

### Retry Event Logging

- [x] T016 Create `RetryEventLogger.java` in `src/main/java/br/edu/ifba/lightrag/utils/`
- [x] T017 Add CDI `@Observes` method for SmallRye fault tolerance events
- [x] T018 Add structured logging with MDC context (operation, attemptNumber)
- [x] T019 Add Javadoc per Constitution §I

**Checkpoint**: Core infrastructure ready

---

## Phase 3: User Story 1 - Resilient Document Processing (P1)

**Goal**: Document upload/processing survives transient failures

### Tests (TDD - Write FIRST)

- [x] T020 [P] [US1] Create `AgeGraphStorageRetryIT.java` in `src/test/java/br/edu/ifba/lightrag/storage/impl/`
- [x] T021 [P] [US1] Write test `testUpsertEntityRetriesOnConnectionFailure()`
- [x] T022 [P] [US1] Write test `testUpsertRelationRetriesOnConnectionFailure()`
- [x] T023 [P] [US1] Write test `testBatchUpsertEntitiesRetriesOnFailure()`
- [x] T024 [P] [US1] Write test `testConstraintViolationNotRetried()`

### Implementation

- [x] T025 [US1] Add `@Retry` annotation to `upsertEntity()` in `AgeGraphStorage.java`
- [x] T026 [US1] Add `@Retry` annotation to `upsertRelation()` in `AgeGraphStorage.java`
- [x] T027 [US1] Add `@Retry` annotation to `upsertEntities()` batch method
- [x] T028 [US1] Add `@Retry` annotation to `upsertRelations()` batch method
- [x] T029 [US1] Configure `retryOn` and `abortOn` exception classes
- [x] T030 [US1] Run tests and verify all pass

**Checkpoint**: Document processing resilient

---

## Phase 4: User Story 2 - Reliable Chat Queries (P1)

**Goal**: Chat queries survive database hiccups

### Tests (TDD)

- [x] T031 [P] [US2] Write test `testGetEntityRetriesOnTimeout()` in AgeGraphStorageRetryIT
- [x] T032 [P] [US2] Write test `testGetEntitiesRetriesOnTimeout()`
- [x] T033 [P] [US2] Write test `testGetRelationsForEntityRetriesOnTimeout()`
- [x] T034 [P] [US2] Write test `testGetAllEntitiesRetriesOnConnectionFailure()`

### Implementation

- [x] T035 [US2] Add `@Retry` to `getEntity()` in AgeGraphStorage.java
- [x] T036 [US2] Add `@Retry` to `getEntities()` in AgeGraphStorage.java
- [x] T037 [US2] Add `@Retry` to `getRelationsForEntity()` in AgeGraphStorage.java
- [x] T038 [US2] Add `@Retry` to `getAllEntities()` and `getAllRelations()`
- [x] T039 [US2] Add `@Retry` to `getStats()`
- [x] T040 [US2] Run tests and verify all pass

**Checkpoint**: Chat queries resilient

---

## Phase 5: User Story 3 - Graph Operations (P2)

**Goal**: Graph lifecycle operations resilient

### Tests (TDD)

- [x] T041 [P] [US3] Write test `testCreateProjectGraphRetriesOnFailure()`
- [x] T042 [P] [US3] Write test `testDeleteProjectGraphRetriesOnFailure()`
- [x] T043 [P] [US3] Write test `testGraphExistsRetriesOnFailure()`

### Implementation

- [x] T044 [US3] Add `@Retry` to `createProjectGraph()` in AgeGraphStorage.java
- [x] T045 [US3] Add `@Retry` to `deleteProjectGraph()`
- [x] T046 [US3] Add `@Retry` to `graphExists()`
- [x] T047 [US3] Run tests and verify all pass

**Checkpoint**: Graph operations resilient

---

## Phase 6: Vector Storage Integration

**Goal**: PgVectorStorage protected by retry

### Tests (TDD)

- [x] T048 [P] Create `PgVectorStorageRetryIT.java` in `src/test/java/br/edu/ifba/lightrag/storage/impl/`
- [x] T049 [P] Write test `testUpsertVectorRetriesOnConnectionFailure()`
- [x] T050 [P] Write test `testSearchSimilarRetriesOnTimeout()`
- [x] T051 [P] Write test `testDeleteVectorRetriesOnFailure()`

### Implementation

- [x] T052 Add `@Retry` to `upsert()` in PgVectorStorage.java
- [x] T053 Add `@Retry` to `upsertBatch()` batch method
- [x] T054 Add `@Retry` to `query()` (searchSimilar)
- [x] T055 Add `@Retry` to `delete()` and `deleteBatch()`
- [x] T056 Run tests and verify all pass

**Checkpoint**: Both storage layers protected

---

## Phase 7: User Story 4 - Observability (P3)

**Goal**: Retry attempts visible in logs

### Tests

- [x] T057 [P] [US4] Write test `testRetryAttemptLogged()`
- [x] T058 [P] [US4] Write test `testRetryExhaustedLogged()`
- [x] T059 [P] [US4] Write test `testLogIncludesOperationContext()`

### Implementation

- [x] T060 [US4] Verify RetryEventLogger captures all retry events
- [x] T061 [US4] Add structured logging fields per Constitution §V
- [x] T062 [US4] Document log format in quickstart.md

**Checkpoint**: Full observability

---

## Phase 8: Documentation & Polish

- [x] T063 [P] Update `quickstart.md` with final configuration examples
- [x] T064 [P] Add troubleshooting section to quickstart.md
- [x] T065 [P] Update AGENTS.md with retry configuration documentation
- [x] T066 Add `final` to immutable variables per Constitution §I
- [x] T067 Run `./mvnw clean package` - verify build passes
- [x] T068 Run `./mvnw test` - verify all unit tests pass
- [x] T069 Run `./mvnw verify -DskipITs=false` - verify integration tests pass

---

## Dependencies

```
Phase 1 (Setup)
    │
    ▼
Phase 2 (Core Components)
    │
    ├───────┬───────┬───────┐
    ▼       ▼       ▼       ▼
Phase 3  Phase 4  Phase 5  Phase 6
(US1)    (US2)    (US3)    (Vector)
    │       │       │       │
    └───────┴───────┴───────┘
            │
            ▼
      Phase 7 (US4 - Observability)
            │
            ▼
      Phase 8 (Documentation)
```

---

## Success Validation

After completion, verify against spec.md Success Criteria:

- [x] **SC-001**: 95% recovery from transient failures (verified via integration tests)
- [x] **SC-002**: <2s average recovery time (200ms initial delay + exponential backoff)
- [x] **SC-003**: <30s max retry duration (maxDuration=30s configured)
- [x] **SC-005**: Zero duplicate data (idempotency via upsert operations)
- [x] **SC-006**: Logs enable 5-minute troubleshooting (RetryEventLogger with MDC context)

---

**Total Tasks**: 69  
**MVP Scope**: Phases 1-3 (T001-T030) = 30 tasks  
**Parallel Opportunities**: 25+ tasks marked [P]
