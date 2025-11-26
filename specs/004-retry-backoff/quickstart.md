# Quickstart: Retry Logic with Exponential Backoff

**Feature Branch**: `004-retry-backoff`  
**Created**: 2025-01-25

## Overview

This feature adds automatic retry logic with exponential backoff to all database operations using SmallRye Fault Tolerance (MicroProfile standard). When transient failures occur (connection timeouts, pool exhaustion, network errors), the system automatically retries operations without requiring changes to application code.

## Quick Configuration

### Default Settings (application.properties)

```properties
# SmallRye Fault Tolerance - Retry Configuration
smallrye.faulttolerance.global.retry.max-retries=3
smallrye.faulttolerance.global.retry.delay=500
smallrye.faulttolerance.global.retry.max-duration=30s
smallrye.faulttolerance.global.retry.jitter=100
smallrye.faulttolerance.global.retry.enabled=true
```

### Configuration Presets

**High Availability (more retries, longer delays)**
```properties
smallrye.faulttolerance.global.retry.max-retries=5
smallrye.faulttolerance.global.retry.delay=1000
smallrye.faulttolerance.global.retry.max-duration=60s
```

**Low Latency (fewer retries, faster failure)**
```properties
smallrye.faulttolerance.global.retry.max-retries=2
smallrye.faulttolerance.global.retry.delay=200
smallrye.faulttolerance.global.retry.max-duration=5s
```

**Disabled (no retries)**
```properties
smallrye.faulttolerance.global.retry.enabled=false
```

## Adding Dependency

The SmallRye Fault Tolerance extension is included in Quarkus BOM. Add to `pom.xml`:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
</dependency>
```

## Using @Retry Annotation

### Basic Usage

```java
import org.eclipse.microprofile.faulttolerance.Retry;
import java.sql.SQLException;

@ApplicationScoped
public class MyService {
    
    @Retry(maxRetries = 3, delay = 500, jitter = 100)
    public void databaseOperation() throws SQLException {
        // Operation that may fail transiently
    }
}
```

### Advanced Usage with Exception Filtering

```java
import org.eclipse.microprofile.faulttolerance.Retry;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

@Retry(
    maxRetries = 3,
    delay = 500,
    jitter = 100,
    maxDuration = 30000,
    retryOn = { SQLException.class },
    abortOn = { SQLIntegrityConstraintViolationException.class }
)
public CompletableFuture<Void> upsertEntity(String projectId, Entity entity) {
    // Retries on SQL exceptions except constraint violations
}
```

## Testing Retry Behavior

### 1. Verify Configuration Loaded

```bash
# Start application in dev mode
./mvnw quarkus:dev

# Check logs for SmallRye FT initialization
# Look for: "SmallRye Fault Tolerance initialized"
```

### 2. Simulate Transient Failure

```bash
# Upload a document
curl -X POST http://localhost:8080/api/v1/documents \
  -F "file=@test-doc.pdf" \
  -F "projectId=<PROJECT_ID>"

# During processing, temporarily stop PostgreSQL
docker stop rag-postgres

# Wait 2-3 seconds, then restart
docker start rag-postgres

# Check logs for retry attempts:
# "Retry attempt 1/3 for 'AgeGraphStorage.upsertEntity' after 500ms"
```

### 3. Enable Debug Logging

```properties
# In application.properties
quarkus.log.category."io.smallrye.faulttolerance".level=DEBUG
quarkus.log.category."br.edu.ifba.lightrag.utils".level=DEBUG
```

## Running Tests

```bash
# Unit tests for exception classification
./mvnw test -Dtest=TransientSQLExceptionPredicateTest

# Integration tests (requires Docker/Testcontainers)
./mvnw verify -DskipITs=false -Dit.test=AgeGraphStorageRetryIT
./mvnw verify -DskipITs=false -Dit.test=PgVectorStorageRetryIT

# All retry-related tests
./mvnw test -Dtest="*Retry*,*Transient*"
```

## Troubleshooting

### Problem: "Retry exhausted" in logs

**Symptom**:
```
WARN [RetryEventLogger] Retry exhausted for 'AgeGraphStorage.upsertEntity' after 3 attempts
```

**Causes**:
1. Database completely unavailable (not transient)
2. Network partition longer than retry window
3. Connection pool permanently exhausted

**Solutions**:
1. Check PostgreSQL status: `docker ps | grep postgres`
2. Check connection pool: verify `quarkus.datasource.jdbc.max-size`
3. Increase retry configuration for longer outages

### Problem: Operations failing without retries

**Symptom**: Operations fail immediately without retry logs

**Causes**:
1. Retry disabled: Check `smallrye.faulttolerance.global.retry.enabled=true`
2. Permanent error: SQLSTATE indicates non-transient failure (23xxx, 42xxx)
3. Missing `@Retry` annotation on method

**Debug**:
```properties
quarkus.log.category."io.smallrye.faulttolerance".level=DEBUG
```

### Problem: Retries causing too much delay

**Symptom**: User requests timing out, logs show many retry attempts

**Solution**: Reduce retry configuration:
```properties
smallrye.faulttolerance.global.retry.max-retries=2
smallrye.faulttolerance.global.retry.max-duration=5s
```

### Problem: Thundering herd after failure

**Symptom**: After database recovery, all retries hit simultaneously

**Solution**: Increase jitter:
```properties
smallrye.faulttolerance.global.retry.jitter=500
```

## Tuning Guide

### Calculating Total Retry Time

With defaults (3 retries, 500ms delay, 2x exponential):
- Attempt 1: ~500ms delay
- Attempt 2: ~1000ms delay
- Attempt 3: ~2000ms delay
- **Total max**: ~3500ms + jitter

### When to Increase Retries

- **Unstable network**: Increase `max-retries` to 5
- **Slow failover**: Increase `max-duration` to 60s
- **Database restarts**: Increase `delay` to 1000ms

### When to Decrease Retries

- **User-facing latency critical**: Decrease `max-retries` to 2
- **Fast failure preferred**: Decrease `max-duration` to 5s
- **Predictable database**: Decrease `delay` to 200ms

## Error Classification Reference

### Transient Errors (Will Retry)

| SQLSTATE | Description |
|----------|-------------|
| 08xxx | Connection exceptions |
| 40xxx | Transaction rollback (deadlock) |
| 53xxx | Insufficient resources |
| 57xxx | Operator intervention |
| 58xxx | System error |

### Permanent Errors (Will NOT Retry)

| SQLSTATE | Description |
|----------|-------------|
| 22xxx | Data exception |
| 23xxx | Integrity constraint violation |
| 28xxx | Authentication failure |
| 42xxx | Syntax/access error |

## Monitoring

### Retry Event Logger Output Format

The `RetryEventLogger` produces structured logs with MDC context for easy filtering and analysis:

**Retry Attempt (INFO level)**:
```
2025-01-25 10:15:23,456 INFO  [RetryEventLogger] Retry attempt 2/4 for upsertEntity: SQLException - Connection reset
```

**Retry Exhausted (WARN level)**:
```
2025-01-25 10:15:28,789 WARN  [RetryEventLogger] Retry exhausted for upsertEntity after 4 attempts: SQLException - Connection refused
```

**Retry Success (INFO level, only logged if retries > 1)**:
```
2025-01-25 10:15:25,123 INFO  [RetryEventLogger] Retry succeeded for upsertEntity on attempt 3
```

### MDC Context Fields

The logger sets these MDC fields during logging (useful for log aggregation tools):

| Field | Description | Example |
|-------|-------------|---------|
| `retry.operation` | Operation name | `upsertEntity` |
| `retry.attempt` | Current attempt (1-based) | `2` |
| `retry.exception` | Exception class name | `SQLException` |

**Log4j/Logback pattern to include MDC**:
```
%d{HH:mm:ss.SSS} %-5level [%logger{20}] [%X{retry.operation}:%X{retry.attempt}] %msg%n
```

### Key Log Messages

| Level | Message Pattern | Meaning |
|-------|-----------------|---------|
| INFO | "Retry attempt N/M for 'operation'" | Retry in progress |
| WARN | "Retry exhausted for 'operation'" | All retries failed |
| DEBUG | "Operation succeeded after N retries" | Recovery success |

### Metrics (via SmallRye)

SmallRye Fault Tolerance exposes Micrometer metrics:

```
ft_retry_calls_total{method="...", result="valueReturned|retried|maxRetriesReached"}
ft_retry_retries_total{method="..."}
```

Enable with:
```properties
quarkus.smallrye-fault-tolerance.metrics.enabled=true
```

## Configuration Reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `max-retries` | int | 3 | Maximum retry attempts |
| `delay` | long | 500 | Initial delay (ms) |
| `max-duration` | duration | 30s | Max total duration |
| `jitter` | long | 100 | Random jitter (ms) |
| `enabled` | boolean | true | Enable/disable retry |

## Limitations

1. **Config changes require restart**: Unlike dev mode, production requires restart for config changes
2. **No circuit breaker** (out of scope): Consider adding `@CircuitBreaker` for future enhancement
3. **Database operations only**: External API calls (LLM, embeddings) not covered by this feature
