# API Security Contracts: Keycloak REST API Authorization

**Feature Branch**: `011-keycloak-api-auth`  
**Date**: 2025-12-21  
**Spec**: [spec.md](file:///Users/emanuelcerqueira/Documents/rag-saas/specs/011-keycloak-api-auth/spec.md)

## Overview

This document defines the security contract changes for protected REST API endpoints. All endpoints (except public ones) now require Bearer token authentication.

---

## Common Security Headers

### Request Headers (Required)

```http
Authorization: Bearer <keycloak_jwt_token>
```

### Error Responses

#### 401 Unauthorized

Returned when authentication fails (missing, invalid, or expired token).

```json
{
  "error": "Unauthorized",
  "message": "Invalid or missing authentication token",
  "timestamp": "2025-12-21T17:00:00Z"
}
```

#### 403 Forbidden

Returned when authenticated user lacks required permissions.

```json
{
  "error": "Forbidden",
  "message": "Access denied to resource",
  "timestamp": "2025-12-21T17:00:00Z"
}
```

---

## Public Endpoints (No Authentication Required)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/q/health/*` | GET | Health check endpoints |
| `/q/health/live` | GET | Liveness probe |
| `/q/health/ready` | GET | Readiness probe |
| `/openapi` | GET | OpenAPI specification |
| `/swagger-ui/*` | GET | Swagger UI |

---

## Projects API

### Create Project

```http
POST /projects
Authorization: Bearer <token>
Content-Type: application/json
```

**Required Role**: `user`  
**Resource Check**: None

**Request Body**:
```json
{
  "name": "My Project"
}
```

**Response** (201 Created):
```json
{
  "id": "019a-8612-045d-..."
}
```

**Changes**: Project is now created with `owner_id` set to authenticated user's Keycloak subject ID.

---

### List Projects

```http
GET /projects
Authorization: Bearer <token>
```

**Required Role**: `user`  
**Resource Check**: Filtered by ownership

**Response** (200 OK):
```json
[
  {
    "id": "019a-8612-045d-...",
    "name": "My Project",
    "ownerId": "a1b2c3d4-e5f6-...",
    "createdAt": "2025-12-21T17:00:00",
    "updatedAt": "2025-12-21T17:00:00",
    "documentCount": 5
  }
]
```

**Behavior by Role**:
- `user`: Returns only projects where `owner_id` matches user's subject ID OR `owner_id` is NULL (legacy)
- `admin`: Returns all projects

**Changes**: Added `ownerId` field to response; list filtered by ownership.

---

### Get Project

```http
GET /projects/{id}
Authorization: Bearer <token>
```

**Required Role**: `user`  
**Resource Check**: Owner or admin

**Response** (200 OK):
```json
{
  "id": "019a-8612-045d-...",
  "name": "My Project",
  "ownerId": "a1b2c3d4-e5f6-...",
  "createdAt": "2025-12-21T17:00:00",
  "updatedAt": "2025-12-21T17:00:00",
  "documentCount": 5
}
```

**Changes**: Added `ownerId` field; returns 403 if not owner or admin.

---

### Update Project

```http
PUT /projects/{id}
Authorization: Bearer <token>
Content-Type: application/json
```

**Required Role**: `user`  
**Resource Check**: Owner or admin

**Request Body**:
```json
{
  "name": "Updated Project Name"
}
```

**Response** (200 OK): Updated project object

**Changes**: Returns 403 if not owner or admin.

---

### Delete Project

```http
DELETE /projects/{id}
Authorization: Bearer <token>
```

**Required Role**: `user`  
**Resource Check**: Owner or admin

**Response** (204 No Content)

**Changes**: Returns 403 if not owner or admin.

---

## Documents API

### Upload Document

```http
POST /documents
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

**Required Role**: `user`  
**Resource Check**: Project owner or admin (based on `projectId` form field)

**Form Fields**:
- `file`: File to upload
- `projectId`: UUID of target project

**Changes**: Returns 403 if user is not owner of the target project (unless admin).

---

### Delete Document

```http
DELETE /documents/{id}
Authorization: Bearer <token>
```

**Required Role**: `user`  
**Resource Check**: Project owner or admin (based on document's project)

**Query Parameters**:
- `projectId`: UUID of the project (required)

**Changes**: Returns 403 if user is not owner of the document's project (unless admin).

---

## Chat API

### Query

```http
POST /chat
Authorization: Bearer <token>
Content-Type: application/json
```

**Required Role**: `user`  
**Resource Check**: Project owner or admin (based on `projectId` in request)

**Request Body**:
```json
{
  "projectId": "019a-8612-045d-...",
  "query": "What is the main topic?",
  "mode": "HYBRID"
}
```

**Changes**: Returns 403 if user is not owner of the queried project (unless admin).

---

## JWT Token Structure

### Expected Claims

```json
{
  "iss": "http://localhost:8180/realms/rag-saas",
  "sub": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "aud": "rag-saas-api",
  "exp": 1734800000,
  "iat": 1734796400,
  "preferred_username": "testuser",
  "email": "testuser@example.com",
  "realm_access": {
    "roles": ["user"]
  }
}
```

### Claims Used by Application

| Claim | Usage |
|-------|-------|
| `sub` | User identifier, stored as `owner_id` in projects |
| `realm_access.roles` | Role-based authorization |
| `preferred_username` | Logging/auditing (optional) |
| `exp` | Token expiration validation |
| `iss` | Issuer validation |

---

## Configuration Properties

### application.properties

```properties
# OIDC Authentication
quarkus.oidc.auth-server-url=${KEYCLOAK_URL:http://localhost:8180}/realms/${KEYCLOAK_REALM:rag-saas}
quarkus.oidc.client-id=${KEYCLOAK_CLIENT_ID:rag-saas-api}
quarkus.oidc.credentials.secret=${KEYCLOAK_CLIENT_SECRET:dev-secret}
quarkus.oidc.tls.verification=${KEYCLOAK_TLS_VERIFICATION:none}

# Role Mapping
quarkus.oidc.roles.role-claim-path=realm_access/roles

# Public Endpoints
quarkus.http.auth.permission.public.paths=/q/*,/openapi,/swagger-ui/*
quarkus.http.auth.permission.public.policy=permit

# Authenticated Endpoints
quarkus.http.auth.permission.authenticated.paths=/*
quarkus.http.auth.permission.authenticated.policy=authenticated
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KEYCLOAK_URL` | `http://localhost:8180` | Keycloak server URL |
| `KEYCLOAK_REALM` | `rag-saas` | Keycloak realm name |
| `KEYCLOAK_CLIENT_ID` | `rag-saas-api` | OIDC client ID |
| `KEYCLOAK_CLIENT_SECRET` | `dev-secret` | Client secret |
| `KEYCLOAK_TLS_VERIFICATION` | `none` | TLS verification mode |
