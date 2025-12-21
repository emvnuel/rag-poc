# Research: Keycloak REST API Authorization

**Feature Branch**: `011-keycloak-api-auth`  
**Date**: 2025-12-21  
**Status**: Complete

## Executive Summary

This document captures research findings for implementing Keycloak-based JWT authorization in the RAG-SaaS Quarkus application. The primary decision is to use Quarkus's built-in OIDC extension with Keycloak as the identity provider.

---

## Decision 1: Quarkus OIDC Integration Approach

### Decision
Use `quarkus-oidc` extension for bearer token validation (resource server mode).

### Rationale
- **Native Integration**: Quarkus provides first-class OIDC support specifically designed for Keycloak
- **Minimal Configuration**: Only requires `auth-server-url`, `client-id`, and `credentials.secret`
- **Automatic JWKS Caching**: Public keys are cached automatically, reducing per-request latency
- **Built-in Role Mapping**: Supports `@RolesAllowed` annotations with realm and client roles
- **Dev Services**: Quarkus can auto-start Keycloak container in dev mode (though we'll use docker-compose for consistency)

### Alternatives Considered
1. **Manual JWT Validation with SmallRye JWT**: More control but significantly more code; unnecessary complexity
2. **Spring Security Filter**: Not compatible with Quarkus reactive stack
3. **Custom HTTP Filter**: Would require implementing all JWT validation logic manually

---

## Decision 2: Authorization Model

### Decision
Three-tier authorization:
1. **Authentication**: Valid Keycloak JWT required (401 if missing/invalid)
2. **Role-Based Access Control (RBAC)**: `user` and `admin` roles control endpoint access (403 if insufficient role)
3. **Resource-Level Authorization**: Project ownership enforced for modification operations

### Rationale
- **Standard Pattern**: Matches industry-standard OAuth2/OIDC authorization patterns
- **Separation of Concerns**: Authentication (identity) separate from authorization (permissions)
- **Flexible**: Can add more roles or resource-level rules later without changing architecture

### Alternatives Considered
1. **Keycloak Authorization Services (UMA)**: Overkill for current requirements; adds significant complexity
2. **Policy Enforcer**: Would delegate all decisions to Keycloak; less flexible for resource ownership
3. **ABAC (Attribute-Based)**: More powerful but unnecessarily complex for current use case

---

## Decision 3: Project Ownership Storage

### Decision
Add `owner_id` column (VARCHAR/UUID) to `projects` table storing Keycloak subject ID.

### Rationale
- **Minimal Schema Change**: Single column addition to existing entity
- **Keycloak Subject ID**: The `sub` claim is immutable and unique per user
- **Nullable for Migration**: Existing projects will have NULL owner (admin-only modification)

### Alternatives Considered
1. **Separate Ownership Table**: Over-engineered for simple ownership model
2. **Username Storage**: Usernames can be changed in Keycloak; subject ID is stable
3. **JSONB Permissions Field**: More flexible but adds query complexity

---

## Decision 4: Docker-Compose Keycloak Configuration

### Decision
Add Keycloak service to existing `docker-compose.yaml`:
- Use `quay.io/keycloak/keycloak:26.0` (latest stable, Quarkus-based)
- Run in `start-dev` mode for development
- Share the existing `postgres` service as Keycloak's database (separate schema)
- Pre-configure realm via JSON import

### Rationale
- **Shared PostgreSQL**: Reduces resource usage; Keycloak uses separate `keycloak` schema
- **Realm Import**: Ensures consistent configuration across development environments
- **Development Mode**: Simpler setup; production deployments should use external Keycloak

### Alternatives Considered
1. **Separate Keycloak PostgreSQL**: More isolation but doubles database containers
2. **H2 Database for Keycloak**: Not persistent; would lose configuration on restart
3. **Quarkus Dev Services**: Would work but less control over realm configuration

---

## Decision 5: Role Claim Location

### Decision
Read roles from `realm_access.roles` claim (Keycloak standard location).

### Rationale
- **Keycloak Default**: Standard location for realm-level roles
- **Quarkus Support**: `quarkus-oidc` automatically maps these with proper configuration
- **Consistent Across Clients**: Realm roles apply to all clients

### Configuration
```properties
quarkus.oidc.roles.role-claim-path=realm_access/roles
```

### Alternatives Considered
1. **Client Roles (`resource_access`)**: More granular but adds complexity
2. **Custom Claim**: Would require Keycloak mapper configuration

---

## Decision 6: Health Check Endpoints

### Decision
Make `/q/health/*` endpoints publicly accessible (no authentication required).

### Rationale
- **Kubernetes Probes**: Liveness/readiness probes must succeed without auth
- **Quarkus Convention**: Standard path for Quarkus health endpoints
- **Explicit Configuration**: Will use `quarkus.http.auth.permission` to whitelist

---

## Decision 7: CORS Configuration

### Decision
Keep existing CORS configuration and ensure `Authorization` header is allowed.

### Rationale
- **Already Configured**: `application.properties` already allows `authorization` header
- **Web Clients**: Frontend applications need CORS for token-based authentication

---

## Technical Specifications

### Required Dependencies (pom.xml)
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-oidc</artifactId>
</dependency>
```

### Key Configuration Properties
```properties
# OIDC Configuration
quarkus.oidc.auth-server-url=http://localhost:8180/realms/rag-saas
quarkus.oidc.client-id=rag-saas-api
quarkus.oidc.credentials.secret=${KEYCLOAK_CLIENT_SECRET:dev-secret}
quarkus.oidc.tls.verification=none

# Role Mapping
quarkus.oidc.roles.role-claim-path=realm_access/roles

# Public Endpoints
quarkus.http.auth.permission.public.paths=/q/health/*,/openapi,/swagger-ui/*
quarkus.http.auth.permission.public.policy=permit

# Protected Endpoints (require authentication)
quarkus.http.auth.permission.authenticated.paths=/projects/*,/documents/*,/chat/*
quarkus.http.auth.permission.authenticated.policy=authenticated
```

### Keycloak Realm Configuration
- **Realm Name**: `rag-saas`
- **Client ID**: `rag-saas-api`
- **Client Protocol**: OpenID Connect
- **Access Type**: Confidential
- **Roles**: `user`, `admin`
- **Default Users**: `testuser` (user role), `testadmin` (admin role)

---

## Edge Case Handling

| Edge Case | Resolution |
|-----------|------------|
| Keycloak unavailable | JWKS keys are cached; existing tokens validated; new tokens fail |
| Token near expiration | Accept tokens until actual expiration; client handles refresh |
| Role changes mid-session | Roles cached in token; takes effect on next token request |
| Key rotation | OIDC extension auto-refreshes JWKS keys |
| Legacy projects (no owner) | Only admins can modify; documented in migration notes |

---

## References

1. [Quarkus OIDC Bearer Token Authentication Guide](https://quarkus.io/guides/security-oidc-bearer-token-authentication)
2. [Quarkus Security Authorization Guide](https://quarkus.io/guides/security-authorize-web-endpoints-reference)
3. [Keycloak Docker Compose Setup](https://www.keycloak.org/server/containers)
