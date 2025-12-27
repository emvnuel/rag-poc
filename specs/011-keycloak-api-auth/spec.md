# Feature Specification: Keycloak REST API Authorization

**Feature Branch**: `011-keycloak-api-auth`  
**Created**: 2025-12-21  
**Status**: Draft  
**Input**: User description: "Keycloak REST API authorization"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Secure API Access with Bearer Token (Priority: P1)

As an API consumer, I want all REST endpoints to require valid Keycloak-issued JWT bearer tokens so that only authenticated users can access the platform's resources.

**Why this priority**: Without authentication, the API is completely open, allowing anyone to create/delete projects, upload documents, and access sensitive knowledge graphs. This is the foundational security layer that must exist before any other authorization rules can be enforced.

**Independent Test**: Can be fully tested by attempting to call any endpoint without a token (should receive 401), and then with a valid Keycloak token (should succeed). Delivers immediate security value by preventing unauthorized access.

**Acceptance Scenarios**:

1. **Given** a request to any API endpoint without an Authorization header, **When** the request is received, **Then** the system returns HTTP 401 Unauthorized with an appropriate error message.
2. **Given** a request with an invalid or expired JWT token, **When** the request is received, **Then** the system returns HTTP 401 Unauthorized.
3. **Given** a request with a valid Keycloak-issued JWT token, **When** the request targets an authorized resource, **Then** the request is processed normally.
4. **Given** a token issued by a different identity provider (non-Keycloak), **When** the request is received, **Then** the system validates the issuer and returns HTTP 401 if not from the configured Keycloak realm.

---

### User Story 2 - Role-Based Endpoint Access (Priority: P2)

As a platform administrator, I want different user roles to have different API access levels so that I can control what operations each user type can perform.

**Why this priority**: After authentication is in place, role-based access control (RBAC) provides granular security by limiting what authenticated users can do based on their responsibilities. This prevents regular users from performing administrative operations.

**Independent Test**: Can be tested by obtaining tokens for users with different roles and verifying access to specific endpoints (e.g., admin-only endpoints should reject non-admin users with 403).

**Acceptance Scenarios**:

1. **Given** an authenticated user with "user" role, **When** they access project creation/query endpoints, **Then** access is granted.
2. **Given** an authenticated user with "user" role, **When** they attempt to access admin-only endpoints, **Then** the system returns HTTP 403 Forbidden.
3. **Given** an authenticated user with "admin" role, **When** they access any endpoint, **Then** access is granted.
4. **Given** an authenticated user with no assigned roles, **When** they access protected endpoints, **Then** the system returns HTTP 403 Forbidden.

---

### User Story 3 - Project Ownership Authorization (Priority: P3)

As a project owner, I want only myself or administrators to be able to modify or delete my projects so that other users cannot tamper with my data.

**Why this priority**: Beyond roles, resource-level authorization ensures users can only modify resources they own. This is critical for multi-tenant isolation where multiple users share the same platform.

**Independent Test**: Can be tested by creating a project with User A, then attempting to delete it with User B's token (should fail with 403), while User A and admins can delete it.

**Acceptance Scenarios**:

1. **Given** a project created by User A, **When** User A attempts to update or delete it, **Then** the operation succeeds.
2. **Given** a project created by User A, **When** User B (non-admin) attempts to update or delete it, **Then** the system returns HTTP 403 Forbidden.
3. **Given** a project created by User A, **When** an admin user attempts to update or delete it, **Then** the operation succeeds.
4. **Given** a project with no owner (legacy data), **When** any authenticated user attempts to modify it, **Then** only admins can modify it.

---

### Edge Cases

- What happens when the Keycloak server is unavailable during token validation?
- How does the system handle tokens that are valid but for a user deleted from Keycloak?
- What happens when a user's roles are changed in Keycloak mid-session?
- How are requests handled during Keycloak realm key rotation?
- What happens when the JWT token is approaching expiration (within seconds)?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST validate JWT bearer tokens on every protected endpoint request.
- **FR-002**: System MUST reject requests without valid Authorization headers with HTTP 401.
- **FR-003**: System MUST validate that JWT tokens are issued by the configured Keycloak realm.
- **FR-004**: System MUST validate JWT token signatures using Keycloak's public keys (JWKS).
- **FR-005**: System MUST validate token expiration and reject expired tokens.
- **FR-006**: System MUST extract user identity (subject/username) from valid tokens.
- **FR-007**: System MUST extract user roles from JWT token claims.
- **FR-008**: System MUST support role-based access control with at least two roles: "user" and "admin".
- **FR-009**: System MUST allow configuring which endpoints require which roles.
- **FR-010**: System MUST return HTTP 403 Forbidden when an authenticated user lacks required roles.
- **FR-011**: System MUST track project ownership by storing the creator's user ID.
- **FR-012**: System MUST enforce that only project owners or admins can modify/delete projects.
- **FR-013**: System MUST allow all authenticated users to create new projects.
- **FR-014**: System MUST allow project owners to grant access to other users (future enhancement).
- **FR-015**: System MUST provide a mechanism for public/unauthenticated health check endpoints.
- **FR-016**: System MUST cache Keycloak public keys to avoid per-request key fetches.
- **FR-017**: System MUST log authentication failures for security auditing.
- **FR-018**: System MUST support CORS configuration for web-based API consumers.

### Key Entities

- **User**: Represents an authenticated user, identified by Keycloak subject ID, with associated roles and username.
- **Role**: Represents an authorization level (e.g., "user", "admin") that determines endpoint access permissions.
- **Project Ownership**: Association between a Project and the User who created it, enabling resource-level authorization.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of protected API endpoints reject unauthenticated requests with HTTP 401.
- **SC-002**: Token validation adds less than 50ms latency to request processing (P95).
- **SC-003**: Users can authenticate and access authorized resources within 2 seconds of obtaining a token.
- **SC-004**: Administrators can manage any project regardless of ownership.
- **SC-005**: Regular users cannot access or modify projects they do not own.
- **SC-006**: System remains operational when Keycloak is temporarily unavailable (cached keys).
- **SC-007**: All authentication failures are logged with sufficient detail for security auditing.
- **SC-008**: Zero unauthorized data access incidents after deployment (measured by audit logs).

## Assumptions

- **Keycloak Instance**: A Keycloak instance (version 20+) is available and configured with the appropriate realm, clients, and roles.
- **Standard JWT Claims**: Keycloak tokens use standard JWT claims (iss, sub, exp, iat) plus realm_access for roles.
- **OIDC Protocol**: Standard OpenID Connect flows are used; this spec focuses on resource server (API) authorization, not the login flow.
- **Existing Projects**: Existing projects created before this feature will have no owner and will be modifiable only by admins.
- **Role Structure**: Initial implementation will support "user" and "admin" roles; additional roles can be added later.
- **Quarkus OIDC Extension**: Quarkus provides built-in OIDC support that will be leveraged for token validation.
