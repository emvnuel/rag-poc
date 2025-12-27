# Tasks: Keycloak REST API Authorization

**Input**: Design documents from `/specs/011-keycloak-api-auth/`  
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/api-security.md ✅, quickstart.md ✅

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Infrastructure initialization for Keycloak integration

- [x] T001 Add quarkus-oidc dependency in pom.xml
- [x] T002 [P] Create Keycloak database schema script in docker-init/04-init-keycloak-schema.sql
- [x] T003 [P] Create Keycloak realm export in src/main/resources/keycloak/rag-saas-realm.json
- [x] T004 Add Keycloak service to docker-compose.yaml

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core security infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T005 Add OIDC configuration properties to src/main/resources/application.properties
- [x] T006 [P] Create ForbiddenException class in src/main/java/br/edu/ifba/exception/ForbiddenException.java
- [x] T007 [P] Create SecurityExceptionMapper in src/main/java/br/edu/ifba/security/SecurityExceptionMapper.java

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - Secure API Access with Bearer Token (Priority: P1) 🎯 MVP

**Goal**: Require valid Keycloak-issued JWT bearer tokens on all protected endpoints. Requests without tokens or with invalid tokens receive 401 Unauthorized.

**Independent Test**: Call any endpoint without a token → expect 401. Call with valid Keycloak token → expect success.

### Implementation for User Story 1

- [x] T008 [US1] Configure public endpoint permissions (health, openapi, swagger) in src/main/resources/application.properties
- [x] T009 [US1] Configure authenticated endpoint permissions in src/main/resources/application.properties
- [x] T010 [US1] Add @RolesAllowed({"user", "admin"}) annotation to ProjectResources in src/main/java/br/edu/ifba/project/ProjectResources.java
- [x] T011 [P] [US1] Add @RolesAllowed({"user", "admin"}) annotation to DocumentResources in src/main/java/br/edu/ifba/document/DocumentResources.java
- [x] T012 [P] [US1] Add @RolesAllowed({"user", "admin"}) annotation to ChatResources in src/main/java/br/edu/ifba/chat/ChatResources.java
- [x] T013 [US1] Verify 401 is returned for requests without Authorization header
- [x] T014 [US1] Verify 401 is returned for requests with invalid/expired tokens

**Checkpoint**: At this point, User Story 1 should be fully functional - all endpoints require authentication

---

## Phase 4: User Story 2 - Role-Based Endpoint Access (Priority: P2)

**Goal**: Enforce role-based access control where users with "user" role can access standard endpoints, and admin-only endpoints require "admin" role.

**Independent Test**: Get tokens for users with different roles and verify access to specific endpoints (admin-only endpoints reject non-admin with 403).

### Implementation for User Story 2

- [x] T015 [US2] Inject SecurityIdentity into ProjectResources in src/main/java/br/edu/ifba/project/ProjectResources.java
- [x] T016 [P] [US2] Inject SecurityIdentity into DocumentResources in src/main/java/br/edu/ifba/document/DocumentResources.java
- [x] T017 [P] [US2] Inject SecurityIdentity into ChatResources in src/main/java/br/edu/ifba/chat/ChatResources.java
- [x] T018 [US2] Verify authenticated user with "user" role can access project endpoints
- [ ] T019 [US2] Verify authenticated user with no roles receives 403 Forbidden
- [x] T020 [US2] Verify admin role can access any endpoint

**Checkpoint**: At this point, User Story 2 is complete - role-based access is enforced

---

## Phase 5: User Story 3 - Project Ownership Authorization (Priority: P3)

**Goal**: Only project owners or administrators can modify/delete projects. Users can only see their own projects (admins see all).

**Independent Test**: Create project with User A, attempt to delete with User B → expect 403. Admin can delete any project.

### Implementation for User Story 3

- [x] T021 [US3] Add ownerId field to Project entity in src/main/java/br/edu/ifba/project/Project.java
- [x] T022 [US3] Add ownerId to ProjectInfoResponse record in src/main/java/br/edu/ifba/project/ProjectInfoResponse.java
- [x] T023 [US3] Add findByOwnerId method to ProjectRepository in src/main/java/br/edu/ifba/project/ProjectRepository.java
- [x] T024 [US3] Create ProjectAuthorizationService in src/main/java/br/edu/ifba/security/ProjectAuthorizationService.java
- [x] T025 [US3] Implement checkReadAccess method in ProjectAuthorizationService
- [x] T026 [US3] Implement checkWriteAccess method in ProjectAuthorizationService
- [x] T027 [US3] Implement isAdmin and getCurrentUserId helper methods in ProjectAuthorizationService
- [x] T028 [US3] Modify ProjectService.create() to set ownerId from current user in src/main/java/br/edu/ifba/project/ProjectService.java
- [x] T029 [US3] Inject ProjectAuthorizationService into ProjectResources
- [x] T030 [US3] Add authorization check to ProjectResources.getById() method
- [x] T031 [US3] Add authorization check to ProjectResources.update() method
- [x] T032 [US3] Add authorization check to ProjectResources.delete() method
- [x] T033 [US3] Filter projects by ownership in ProjectResources.getAll() method (users see own, admins see all)
- [x] T034 [US3] Inject ProjectAuthorizationService into DocumentResources in src/main/java/br/edu/ifba/document/DocumentResources.java
- [x] T035 [US3] Add authorization check to DocumentResources.upload() method
- [x] T036 [US3] Add authorization check to DocumentResources.delete() method
- [x] T037 [US3] Inject ProjectAuthorizationService into ChatResources in src/main/java/br/edu/ifba/chat/ChatResources.java
- [x] T038 [US3] Add authorization check to ChatResources.chat() method
- [x] T039 [US3] Verify owner can access their own project
- [ ] T040 [US3] Verify non-owner receives 403 when accessing another user's project
- [x] T041 [US3] Verify admin can access any project

**Checkpoint**: All user stories should now be independently functional

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final validation, documentation, and cleanup

- [x] T042 [P] Create database migration script V1.1__add_project_ownership.sql
- [ ] T043 [P] Update API documentation with security requirements
- [ ] T044 Run quickstart.md validation (Step 4-7 tests)
- [ ] T045 Verify all health/public endpoints work without authentication
- [ ] T046 Add security audit logging for authentication failures
- [ ] T047 Test legacy project handling (owner_id = NULL → admin only can modify)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories should proceed sequentially (P1 → P2 → P3) as each builds on security layers
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Foundation only - provides base authentication
- **User Story 2 (P2)**: Depends on US1 - adds role checking on top of authentication
- **User Story 3 (P3)**: Depends on US2 - adds resource-level ownership on top of roles

### Within Each User Story

- Entity changes before service changes
- Services before resource/controller changes
- Authorization service before consumers
- Core implementation before integration

### Parallel Opportunities

**Phase 1 (Setup):**
```bash
# Can run in parallel:
Task T002: "Create Keycloak database schema script"
Task T003: "Create Keycloak realm export"
```

**Phase 2 (Foundational):**
```bash
# Can run in parallel:
Task T006: "Create ForbiddenException class"
Task T007: "Create SecurityExceptionMapper"
```

**Phase 3 (US1):**
```bash
# Can run in parallel:
Task T011: "Add @RolesAllowed to DocumentResources"
Task T012: "Add @RolesAllowed to ChatResources"
```

**Phase 4 (US2):**
```bash
# Can run in parallel:
Task T016: "Inject SecurityIdentity into DocumentResources"
Task T017: "Inject SecurityIdentity into ChatResources"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T004)
2. Complete Phase 2: Foundational (T005-T007)
3. Complete Phase 3: User Story 1 (T008-T014)
4. **STOP and VALIDATE**: Test with `curl` commands from quickstart.md Steps 4
5. Deploy/demo if ready - all endpoints now require authentication!

### Incremental Delivery

1. Complete Setup + Foundational → Security foundation ready
2. Add User Story 1 → Test 401 scenarios → **MVP: API is secured!**
3. Add User Story 2 → Test role-based 403 → Role-based access working
4. Add User Story 3 → Test ownership 403 → Full multi-tenant isolation
5. Each story adds security depth without breaking previous stories

### Single Developer Strategy

Follow priority order: P1 → P2 → P3

1. Phases 1-2: Infrastructure setup (~2 hours)
2. Phase 3 (US1): Authentication layer (~2 hours)
3. Phase 4 (US2): Role-based access (~1 hour)
4. Phase 5 (US3): Ownership authorization (~3 hours)
5. Phase 6: Polish and validation (~1 hour)

---

## Notes

- [P] tasks = different files, no dependencies on same-phase tasks
- [Story] label maps task to specific user story for traceability
- User stories are layered: US1 (authentication) → US2 (roles) → US3 (ownership)
- Legacy projects with NULL owner_id: readable by all, modifiable only by admin
- Validate each user story checkpoint before proceeding to next
- Use quickstart.md test scenarios to verify each phase
