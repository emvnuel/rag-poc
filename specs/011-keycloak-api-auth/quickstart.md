# Quickstart: Keycloak REST API Authorization

**Feature Branch**: `011-keycloak-api-auth`  
**Date**: 2025-12-21  
**Spec**: [spec.md](file:///Users/emanuelcerqueira/Documents/rag-saas/specs/011-keycloak-api-auth/spec.md)

## Overview

This guide walks you through testing the Keycloak-based authorization feature. After implementation, follow these steps to verify authentication and authorization work correctly.

---

## Prerequisites

1. Docker and Docker Compose installed
2. Java 21+ and Maven 3.9+ installed
3. `curl` or similar HTTP client (Postman, HTTPie, etc.)

---

## Step 1: Start the Services

```bash
# Start PostgreSQL and Keycloak
docker-compose up -d

# Wait for services to be healthy
docker-compose ps

# Start Quarkus in dev mode
./mvnw quarkus:dev
```

**Expected Output**:
- PostgreSQL running on `localhost:5432`
- Keycloak running on `localhost:8180`
- Quarkus API running on `localhost:8080`

---

## Step 2: Access Keycloak Admin Console

1. Open browser to `http://localhost:8180/admin`
2. Login with:
   - Username: `admin`
   - Password: `admin`
3. Verify `rag-saas` realm exists with:
   - Client: `rag-saas-api`
   - Roles: `user`, `admin`
   - Users: `testuser`, `testadmin`

---

## Step 3: Obtain Access Tokens

### Get Token for Regular User

```bash
# Get token for testuser (has "user" role)
TOKEN_USER=$(curl -s -X POST http://localhost:8180/realms/rag-saas/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=rag-saas-api" \
  -d "client_secret=dev-secret" \
  -d "username=testuser" \
  -d "password=testuser" \
  -d "grant_type=password" | jq -r '.access_token')

echo "User token: ${TOKEN_USER:0:50}..."
```

### Get Token for Admin User

```bash
# Get token for testadmin (has "user" and "admin" roles)
TOKEN_ADMIN=$(curl -s -X POST http://localhost:8180/realms/rag-saas/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=rag-saas-api" \
  -d "client_secret=dev-secret" \
  -d "username=testadmin" \
  -d "password=testadmin" \
  -d "grant_type=password" | jq -r '.access_token')

echo "Admin token: ${TOKEN_ADMIN:0:50}..."
```

---

## Step 4: Test Authentication (401 Scenarios)

### Test: Request Without Token

```bash
# Should return 401 Unauthorized
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/projects
# Expected: 401
```

### Test: Request With Invalid Token

```bash
# Should return 401 Unauthorized
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer invalid-token-here" \
  http://localhost:8080/projects
# Expected: 401
```

### Test: Request With Valid Token

```bash
# Should return 200 OK
curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN_USER" \
  http://localhost:8080/projects
# Expected: 200
```

---

## Step 5: Test Project Ownership (403 Scenarios)

### Create Project as testuser

```bash
# Create project with testuser
PROJECT_ID=$(curl -s -X POST http://localhost:8080/projects \
  -H "Authorization: Bearer $TOKEN_USER" \
  -H "Content-Type: application/json" \
  -d '{"name": "Test User Project"}' | jq -r '.id')

echo "Created project: $PROJECT_ID"
```

### Verify Owner Can Access

```bash
# testuser should be able to read their own project
curl -s -w "\n%{http_code}" \
  -H "Authorization: Bearer $TOKEN_USER" \
  http://localhost:8080/projects/$PROJECT_ID
# Expected: 200 with project data
```

### Get Second User Token

```bash
# Create a second user token (if available) or use different tokens
# For testing, we'll use admin to show cross-ownership access
```

### Admin Can Access Any Project

```bash
# Admin should be able to access testuser's project
curl -s -w "\n%{http_code}" \
  -H "Authorization: Bearer $TOKEN_ADMIN" \
  http://localhost:8080/projects/$PROJECT_ID
# Expected: 200 with project data
```

### Owner Can Delete Their Project

```bash
# testuser can delete their own project
curl -s -o /dev/null -w "%{http_code}" -X DELETE \
  -H "Authorization: Bearer $TOKEN_USER" \
  http://localhost:8080/projects/$PROJECT_ID
# Expected: 204
```

---

## Step 6: Test Public Endpoints

### Health Endpoints (No Auth Required)

```bash
# Health check should work without token
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/q/health
# Expected: 200

curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/q/health/live
# Expected: 200

curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/q/health/ready
# Expected: 200
```

### OpenAPI/Swagger (No Auth Required)

```bash
# OpenAPI spec should be accessible without token
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/openapi
# Expected: 200

# Swagger UI should be accessible without token
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/swagger-ui/
# Expected: 200
```

---

## Step 7: Inspect Token Claims

```bash
# Decode JWT token to see claims (using jq)
echo $TOKEN_USER | cut -d'.' -f2 | base64 -d 2>/dev/null | jq .
```

**Expected Claims**:
```json
{
  "sub": "a1b2c3d4-...",
  "preferred_username": "testuser",
  "realm_access": {
    "roles": ["user"]
  },
  "exp": 1734800000
}
```

---

## Troubleshooting

### Token Request Fails

```bash
# Check Keycloak is running
curl -s http://localhost:8180/realms/rag-saas/.well-known/openid-configuration | jq .issuer
# Should return: "http://localhost:8180/realms/rag-saas"
```

### 401 Even With Valid Token

```bash
# Check token is not expired
echo $TOKEN_USER | cut -d'.' -f2 | base64 -d 2>/dev/null | jq .exp
# Convert to date: date -d @<exp_value>
```

### 403 When Should Be Allowed

```bash
# Check project owner matches token subject
curl -s -H "Authorization: Bearer $TOKEN_USER" \
  http://localhost:8080/projects/$PROJECT_ID | jq .ownerId

# Check token subject
echo $TOKEN_USER | cut -d'.' -f2 | base64 -d 2>/dev/null | jq .sub
```

---

## Cleanup

```bash
# Stop all services
docker-compose down

# Remove volumes (optional - loses Keycloak config)
docker-compose down -v
```

---

## Success Criteria Validation

| Criteria | Test | Expected |
|----------|------|----------|
| SC-001: 401 for unauthenticated | Step 4 | ✅ 401 returned |
| SC-002: <50ms auth latency | Measure with `time curl` | ✅ Sub-50ms overhead |
| SC-003: Quick access with token | Step 4 | ✅ <2s response |
| SC-004: Admin can access any project | Step 5 | ✅ 200 returned |
| SC-005: User can't access others' projects | Step 5 | ✅ 403 returned |
| SC-006: Cached keys work | Kill Keycloak, retry | ✅ Existing tokens work |
| SC-007: Auth failures logged | Check Quarkus logs | ✅ Failures logged |
