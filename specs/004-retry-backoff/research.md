# Research: Retry Logic with Exponential Backoff

**Feature Branch**: `004-retry-backoff`  
**Created**: 2025-01-25  
**Status**: Complete

## Research Questions

### RQ1: Resilience4j vs SmallRye Fault Tolerance vs Custom Implementation

**Question**: Which approach should we use for retry logic in Quarkus 3.x?

**Options Evaluated**:

| Option | Pros | Cons |
|--------|------|------|
| **SmallRye Fault Tolerance** | Quarkus native, MicroProfile standard, annotation-based, hot-reload config | Limited customization of retry logic |
| **Resilience4j** | Feature-rich, programmatic API, circuit breaker | Requires additional dependency, not Quarkus-native |
| **Custom Implementation** | Full control, no dependencies | More code to maintain, edge cases |

**Findings**:

1. **Quarkus 3.x uses SmallRye Fault Tolerance by default** - it's the implementation of MicroProfile Fault Tolerance spec
2. **Extension**: `quarkus-smallrye-fault-tolerance` (already available in Quarkus BOM)
3. **Annotation-based**: `@Retry`, `@Timeout`, `@CircuitBreaker`, `@Fallback`
4. **Configuration**: Supports external configuration via `application.properties`

**Decision**: **SmallRye Fault Tolerance** (Quarkus-native)

**Rationale**:
1. Already part of Quarkus ecosystem - no additional dependencies
2. Follows MicroProfile standard (portable)
3. Constitution §IV mentions "exponential backoff" - SmallRye supports this natively
4. Annotation-based approach is cleaner than programmatic wrappers
5. Circuit breaker available for future enhancement without changing approach

---

### RQ2: SmallRye Fault Tolerance Annotation Placement

**Question**: Should retry be at method-level or class-level?

**Findings**:

```java
// Option 1: Method-level (RECOMMENDED)
@Retry(maxRetries = 3, delay = 500, jitter = 50, retryOn = SQLException.class)
public void upsertEntity(...) { }

// Option 2: Class-level (applies to all methods)
@Retry(maxRetries = 3, delay = 500)
@ApplicationScoped
public class AgeGraphStorage { }
```

**Decision**: **Method-level annotations**

**Rationale**:
1. Different operations may need different retry behavior
2. Explicit about which methods are protected
3. Easier to test individual method retry behavior
4. Can exclude specific methods if needed

---

### RQ3: Transient Error Classification with SmallRye

**Question**: How to configure which exceptions trigger retry?

**Findings**:

SmallRye Fault Tolerance supports:
- `retryOn`: List of exception classes to retry
- `abortOn`: List of exception classes to NOT retry (overrides retryOn)

```java
@Retry(
    maxRetries = 3,
    delay = 500,
    maxDuration = 30000,  // 30 seconds total
    jitter = 100,
    retryOn = { SQLException.class, SQLTransientException.class },
    abortOn = { SQLIntegrityConstraintViolationException.class }
)
```

**PostgreSQL Transient Errors** (SQLSTATE codes):
| Code Class | Description | Retry? |
|------------|-------------|--------|
| `08xxx` | Connection Exception | YES |
| `40xxx` | Transaction Rollback | YES |
| `53xxx` | Insufficient Resources | YES |
| `57xxx` | Operator Intervention | YES |
| `58xxx` | System Error | YES |
| `23xxx` | Integrity Constraint | NO |
| `42xxx` | Syntax/Access Error | NO |

**Decision**: Use `retryOn = SQLException.class` with custom exception classifier

**Implementation**:
```java
// Custom exception predicate for fine-grained control
public class TransientSQLExceptionPredicate implements Predicate<Throwable> {
    private static final Set<String> TRANSIENT_PREFIXES = Set.of("08", "40", "53", "57", "58");
    
    @Override
    public boolean test(Throwable t) {
        if (t instanceof SQLException sql) {
            String state = sql.getSQLState();
            return state != null && TRANSIENT_PREFIXES.contains(state.substring(0, 2));
        }
        return false;
    }
}
```

---

### RQ4: Async/CompletableFuture Support

**Question**: Does SmallRye retry work with `CompletableFuture` returns?

**Findings**:

SmallRye Fault Tolerance **fully supports async methods**:

```java
// Works with CompletableFuture
@Retry(maxRetries = 3)
public CompletableFuture<Void> upsertEntityAsync(...) {
    return CompletableFuture.runAsync(() -> { ... });
}

// Works with Uni (Mutiny)
@Retry(maxRetries = 3)
public Uni<Void> upsertEntityReactive(...) {
    return Uni.createFrom().item(() -> { ... });
}
```

**Current Codebase**: Uses `CompletableFuture` extensively in storage layer

**Decision**: Retry annotations work transparently with existing async code - no changes needed to return types.

---

### RQ5: Configuration Hot-Reload

**Question**: Does SmallRye support runtime configuration changes without restart?

**Findings**:

SmallRye Fault Tolerance configuration via `application.properties`:

```properties
# Global defaults
smallrye.faulttolerance.global.retry.max-retries=3
smallrye.faulttolerance.global.retry.delay=500
smallrye.faulttolerance.global.retry.jitter=100

# Per-method override (uses fully qualified method name)
br.edu.ifba.lightrag.storage.impl.AgeGraphStorage/upsertEntity/Retry/maxRetries=5
```

**Hot-reload behavior**:
- Quarkus dev mode: YES (live reload)
- Production: **NO** (requires restart)

**SC-007 Impact**: "Retry configuration changes take effect without system restart"
- This is **NOT fully achievable** with annotation-based approach
- Workaround: Use ConfigSource with polling for dynamic config (adds complexity)

**Decision**: Accept restart requirement for config changes

**Rationale**:
1. Retry config changes are rare operational events
2. Adding dynamic config polling adds complexity without proportional benefit
3. Constitution doesn't mandate hot-reload for all config
4. Document limitation in quickstart.md

---

### RQ6: Logging Retry Events

**Question**: How to log retry attempts with SmallRye?

**Findings**:

SmallRye provides two approaches:

**Option 1: Built-in logging** (via config)
```properties
quarkus.log.category."io.smallrye.faulttolerance".level=DEBUG
```

**Option 2: Custom FaultToleranceInterceptor** (more control)
```java
@Interceptor
@Priority(Interceptor.Priority.LIBRARY_BEFORE)
public class RetryLoggingInterceptor {
    // Custom logging logic
}
```

**Option 3: CDI Events** (RECOMMENDED)
```java
// SmallRye fires CDI events for fault tolerance actions
public void onRetry(@Observes RetryRetried event) {
    logger.info("Retry attempt {} for {}", 
        event.getRetryCount(), 
        event.getMethod().getName());
}
```

**Decision**: Use **CDI Events** for retry logging

**Rationale**:
1. Clean separation of concerns
2. No interceptor complexity
3. Can include structured logging fields per Constitution §V
4. Can be enabled/disabled without code changes

---

## Architecture Decisions

### AD1: Use SmallRye Fault Tolerance

**Decision**: Use `quarkus-smallrye-fault-tolerance` extension with `@Retry` annotations.

**Rationale**:
1. Quarkus-native, no additional dependencies
2. MicroProfile standard (portable)
3. Annotation-based is cleaner than programmatic
4. Aligns with Constitution §IV retry requirements

### AD2: Method-Level Annotations

**Decision**: Apply `@Retry` to individual storage methods, not class-level.

**Rationale**:
1. Fine-grained control per operation
2. Explicit documentation of retry behavior
3. Easier testing and debugging

### AD3: Custom Exception Classifier

**Decision**: Create `TransientSQLExceptionPredicate` for SQLSTATE-based classification.

**Rationale**:
1. PostgreSQL-specific transient error detection
2. Prevents retry on permanent errors
3. Reusable across storage implementations

### AD4: CDI Event Logging

**Decision**: Use CDI events (`@Observes`) for retry logging.

**Rationale**:
1. Clean separation from business logic
2. Structured logging per Constitution §V
3. No invasive changes to storage classes

### AD5: Accept Config Restart Requirement

**Decision**: Retry configuration changes require application restart.

**Rationale**:
1. Simpler implementation
2. Retry config changes are rare
3. Dynamic config adds unnecessary complexity

---

## Configuration Schema

```properties
# SmallRye Fault Tolerance - Retry Configuration
# These override annotation defaults

# Global retry settings (apply to all @Retry methods)
smallrye.faulttolerance.global.retry.max-retries=3
smallrye.faulttolerance.global.retry.delay=500
smallrye.faulttolerance.global.retry.max-duration=30s
smallrye.faulttolerance.global.retry.jitter=100

# Enable/disable globally
smallrye.faulttolerance.global.retry.enabled=true

# Per-class override example (for AgeGraphStorage)
"br.edu.ifba.lightrag.storage.impl.AgeGraphStorage/Retry/maxRetries"=5
```

---

## Performance Impact Analysis

### Normal Operation (No Errors)

| Metric | Without Retry | With Retry | Delta |
|--------|---------------|------------|-------|
| Method call overhead | 0 | ~5μs (interceptor check) | Negligible |
| Memory | 0 | CDI proxy overhead (~1KB) | Negligible |

**Conclusion**: Annotation-based approach has minimal overhead on success path.

### Transient Failure Recovery

| Scenario | Recovery Time | User Experience |
|----------|---------------|-----------------|
| Single retry success | 500-600ms | Barely noticeable |
| Two retries success | 1500-1700ms | Slight delay |
| Three retries success | 3500-4000ms | Noticeable but acceptable |
| All retries fail | ~4000ms + error | Clear error message |

**Conclusion**: Meets SC-002 (<2s average recovery) and SC-003 (<30s max).

---

## Implementation Approach

### Step 1: Add Dependency

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
</dependency>
```

### Step 2: Create Exception Classifier

```java
@ApplicationScoped
public class TransientSQLExceptionPredicate implements Predicate<Throwable> {
    // SQLSTATE-based classification
}
```

### Step 3: Add Annotations to Storage Classes

```java
@Retry(maxRetries = 3, delay = 500, jitter = 100,
       retryOn = SQLException.class,
       abortOn = SQLIntegrityConstraintViolationException.class)
public CompletableFuture<Void> upsertEntity(...) { }
```

### Step 4: Add Retry Event Logging

```java
@ApplicationScoped
public class RetryEventLogger {
    @Observes
    public void onRetry(RetryRetried event) {
        // Structured logging
    }
}
```

---

## References

- SmallRye Fault Tolerance: https://smallrye.io/docs/smallrye-fault-tolerance/6.2.6/index.html
- Quarkus Fault Tolerance Guide: https://quarkus.io/guides/smallrye-fault-tolerance
- MicroProfile Fault Tolerance Spec: https://microprofile.io/specifications/microprofile-fault-tolerance/
- PostgreSQL Error Codes: https://www.postgresql.org/docs/current/errcodes-appendix.html
