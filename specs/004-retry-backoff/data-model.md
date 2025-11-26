# Data Model: Retry Logic with Exponential Backoff

**Feature Branch**: `004-retry-backoff`  
**Created**: 2025-01-25  
**Dependencies**: research.md (complete)

## Overview

This document defines the data models for the retry logic feature using SmallRye Fault Tolerance annotations. The design prioritizes simplicity, testability, and zero overhead on the success path.

## Core Classes

### TransientSQLExceptionPredicate

**Location**: `src/main/java/br/edu/ifba/lightrag/utils/TransientSQLExceptionPredicate.java`

**Purpose**: Determines if a SQLException represents a transient failure eligible for retry based on PostgreSQL SQLSTATE codes.

```java
package br.edu.ifba.lightrag.utils;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Classifies SQL exceptions as transient (retryable) or permanent.
 * Based on PostgreSQL SQLSTATE codes.
 * 
 * <p>Transient errors (will retry):
 * <ul>
 *   <li>08xxx - Connection exceptions</li>
 *   <li>40xxx - Transaction rollback (deadlock)</li>
 *   <li>53xxx - Insufficient resources</li>
 *   <li>57xxx - Operator intervention</li>
 *   <li>58xxx - System error</li>
 * </ul>
 * 
 * <p>Permanent errors (will NOT retry):
 * <ul>
 *   <li>22xxx - Data exception</li>
 *   <li>23xxx - Integrity constraint violation</li>
 *   <li>28xxx - Authentication failure</li>
 *   <li>42xxx - Syntax/access error</li>
 * </ul>
 * 
 * @see <a href="https://www.postgresql.org/docs/current/errcodes-appendix.html">PostgreSQL Error Codes</a>
 */
@ApplicationScoped
public class TransientSQLExceptionPredicate implements Predicate<Throwable> {
    
    /**
     * SQLSTATE class prefixes indicating transient errors.
     */
    private static final Set<String> TRANSIENT_SQLSTATE_PREFIXES = Set.of(
        "08",  // Connection Exception
        "40",  // Transaction Rollback
        "53",  // Insufficient Resources
        "57",  // Operator Intervention
        "58"   // System Error
    );
    
    /**
     * Tests if the given throwable represents a transient failure.
     * 
     * @param t the throwable to test
     * @return true if transient (should retry), false if permanent
     */
    @Override
    public boolean test(Throwable t) {
        if (t == null) {
            return false;
        }
        
        // Check JDBC transient exception types
        if (t instanceof SQLTransientConnectionException) {
            return true;
        }
        if (t instanceof SQLTimeoutException) {
            return true;
        }
        
        // Check SQLSTATE codes for SQLException
        if (t instanceof SQLException sqlEx) {
            String sqlState = sqlEx.getSQLState();
            if (sqlState != null && sqlState.length() >= 2) {
                String prefix = sqlState.substring(0, 2);
                return TRANSIENT_SQLSTATE_PREFIXES.contains(prefix);
            }
        }
        
        // Check common network exceptions
        if (t instanceof java.net.SocketException ||
            t instanceof java.net.ConnectException ||
            t instanceof java.io.EOFException) {
            return true;
        }
        
        // Check cause chain
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            return test(cause);
        }
        
        return false;
    }
    
    /**
     * Static utility method for use in non-CDI contexts.
     * 
     * @param t the throwable to test
     * @return true if transient
     */
    public static boolean isTransient(Throwable t) {
        return new TransientSQLExceptionPredicate().test(t);
    }
}
```

---

### RetryEventLogger

**Location**: `src/main/java/br/edu/ifba/lightrag/utils/RetryEventLogger.java`

**Purpose**: Observes SmallRye Fault Tolerance retry events and logs them with structured context per Constitution §V.

```java
package br.edu.ifba.lightrag.utils;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.smallrye.faulttolerance.api.FaultToleranceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Logs retry events from SmallRye Fault Tolerance.
 * Uses structured logging per Constitution §V requirements.
 */
@ApplicationScoped
public class RetryEventLogger {
    
    private static final Logger logger = LoggerFactory.getLogger(RetryEventLogger.class);
    
    /**
     * Observes retry events and logs with structured context.
     * 
     * @param event the fault tolerance event
     */
    public void onRetryEvent(@Observes FaultToleranceEvent event) {
        String operation = event.getMethod().getDeclaringClass().getSimpleName() 
            + "." + event.getMethod().getName();
        
        try {
            MDC.put("operation", operation);
            MDC.put("retryAttempt", String.valueOf(event.getRetryAttempt()));
            
            if (event.isSuccess()) {
                if (event.getRetryAttempt() > 0) {
                    logger.info("Operation '{}' succeeded after {} retry attempts",
                        operation, event.getRetryAttempt());
                } else {
                    logger.debug("Operation '{}' succeeded (no retry needed)", operation);
                }
            } else if (event.isRetrying()) {
                logger.info("Retry attempt {}/{} for '{}' after {}ms: {}",
                    event.getRetryAttempt(),
                    event.getMaxRetries(),
                    operation,
                    event.getDelay(),
                    event.getFailure().getMessage());
            } else {
                // Retries exhausted
                logger.warn("Retry exhausted for '{}' after {} attempts: {}",
                    operation,
                    event.getRetryAttempt(),
                    event.getFailure().getMessage());
            }
        } finally {
            MDC.remove("operation");
            MDC.remove("retryAttempt");
        }
    }
}
```

**Note**: The exact SmallRye event API may vary - verify during implementation against SmallRye 6.x documentation.

---

## Configuration Schema

**Location**: `src/main/resources/application.properties`

```properties
# ============================================
# SmallRye Fault Tolerance - Retry Configuration
# ============================================

# Global retry defaults (apply to all @Retry annotated methods)
smallrye.faulttolerance.global.retry.max-retries=3
smallrye.faulttolerance.global.retry.delay=500
smallrye.faulttolerance.global.retry.max-duration=30s
smallrye.faulttolerance.global.retry.jitter=100

# Enable/disable retry globally (useful for testing)
smallrye.faulttolerance.global.retry.enabled=true

# ============================================
# Per-Class Overrides (optional)
# ============================================
# Override for AgeGraphStorage (example - use if needed)
# "br.edu.ifba.lightrag.storage.impl.AgeGraphStorage/Retry/maxRetries"=5

# Override for PgVectorStorage (example - use if needed)
# "br.edu.ifba.lightrag.storage.impl.PgVectorStorage/Retry/maxRetries"=5
```

### Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `max-retries` | int | 3 | Maximum number of retry attempts |
| `delay` | long | 500 | Initial delay in milliseconds |
| `max-duration` | duration | 30s | Maximum total duration for all retries |
| `jitter` | long | 100 | Random jitter in milliseconds |
| `enabled` | boolean | true | Enable/disable retry globally |

### Exponential Backoff Calculation

SmallRye uses exponential backoff by default:

```
delay_n = min(delay * 2^n, maxDelay) + random(0, jitter)
```

With defaults (delay=500, jitter=100):
- Attempt 1: 500-600ms
- Attempt 2: 1000-1100ms
- Attempt 3: 2000-2100ms
- **Total max**: ~3700ms (well under 30s limit)

---

## Annotation Pattern

### Storage Method Annotation

```java
import org.eclipse.microprofile.faulttolerance.Retry;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

public class AgeGraphStorage implements GraphStorage {
    
    /**
     * Upserts an entity to the project-specific graph.
     * Retries on transient SQL failures.
     *
     * @param projectId the project identifier
     * @param entity the entity to upsert
     * @return CompletableFuture that completes when upsert is done
     */
    @Retry(
        maxRetries = 3,
        delay = 500,
        jitter = 100,
        maxDuration = 30000,
        retryOn = { SQLException.class, RuntimeException.class },
        abortOn = { SQLIntegrityConstraintViolationException.class, IllegalArgumentException.class }
    )
    @Override
    public CompletableFuture<Void> upsertEntity(@NotNull String projectId, @NotNull Entity entity) {
        // Existing implementation unchanged
    }
}
```

### Annotation Parameters

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| `maxRetries` | 3 | Balance between recovery and fast failure |
| `delay` | 500 | 500ms initial delay per spec |
| `jitter` | 100 | 100ms jitter prevents thundering herd |
| `maxDuration` | 30000 | 30s max per SC-003 |
| `retryOn` | SQLException, RuntimeException | Catch wrapped SQL exceptions |
| `abortOn` | SQLIntegrityConstraintViolationException, IllegalArgumentException | Don't retry permanent errors |

---

## Class Diagram

```
┌────────────────────────────────────┐
│     application.properties         │
│  (SmallRye FT configuration)       │
└────────────────────────────────────┘
                 │
                 │ configures
                 ▼
┌────────────────────────────────────┐
│   SmallRye Fault Tolerance         │
│   (CDI interceptor)                │
├────────────────────────────────────┤
│ - Intercepts @Retry methods        │
│ - Applies exponential backoff      │
│ - Fires CDI events                 │
└────────────────────────────────────┘
         │                    │
         │ intercepts         │ fires events
         ▼                    ▼
┌─────────────────────┐  ┌─────────────────────────┐
│   AgeGraphStorage   │  │   RetryEventLogger      │
│   PgVectorStorage   │  ├─────────────────────────┤
├─────────────────────┤  │ @Observes events        │
│ @Retry annotations  │  │ Structured logging      │
│ on storage methods  │  │ MDC context             │
└─────────────────────┘  └─────────────────────────┘
         │
         │ uses (for classification)
         ▼
┌───────────────────────────────────┐
│ TransientSQLExceptionPredicate    │
├───────────────────────────────────┤
│ + test(Throwable): boolean        │
│ + isTransient(Throwable): boolean │
│ - TRANSIENT_SQLSTATE_PREFIXES     │
└───────────────────────────────────┘
```

---

## Validation Rules

| Check | Rule | Error Handling |
|-------|------|----------------|
| SQLSTATE prefix | Must be 2+ characters | Return false (not transient) |
| Null exception | Handle gracefully | Return false |
| Cause chain | Traverse recursively | Stop at null or self-reference |

---

## Thread Safety

- **TransientSQLExceptionPredicate**: Stateless, thread-safe
- **RetryEventLogger**: Stateless, uses MDC (thread-local), thread-safe
- **SmallRye interceptor**: Managed by CDI, thread-safe

---

## Backward Compatibility

- **No breaking changes**: Existing method signatures unchanged
- **Transparent to callers**: Higher-level code unaffected
- **Default enabled**: Retry is ON by default
- **Graceful degradation**: If SmallRye FT disabled, methods work without retry
