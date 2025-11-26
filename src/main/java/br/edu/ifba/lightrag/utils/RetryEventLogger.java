package br.edu.ifba.lightrag.utils;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Logger utility for SmallRye Fault Tolerance retry events.
 * 
 * <p>Provides structured logging for retry attempts to enable observability
 * and troubleshooting of transient failures in storage operations.</p>
 * 
 * <h2>Usage:</h2>
 * <p>This logger is used via the {@code @BeforeRetry} annotation on methods:</p>
 * <pre>{@code
 * @Retry(maxRetries = 3)
 * @BeforeRetry(methodName = "logRetry")
 * public void databaseOperation() {
 *     // ...
 * }
 * }</pre>
 * 
 * <p>Or called directly within exception handlers:</p>
 * <pre>{@code
 * retryEventLogger.logRetryAttempt("upsertEntity", attempt, exception);
 * }</pre>
 * 
 * <h2>MDC Context:</h2>
 * <ul>
 *   <li><code>retry.operation</code> - The operation being retried</li>
 *   <li><code>retry.attempt</code> - Current attempt number (1-based)</li>
 *   <li><code>retry.exception</code> - Exception class name that triggered retry</li>
 * </ul>
 * 
 * <h2>Log Format Example:</h2>
 * <pre>
 * INFO  [RetryEventLogger] Retry attempt 2/4 for upsertEntity: SQLException - Connection reset
 * WARN  [RetryEventLogger] Retry exhausted for upsertEntity after 4 attempts: SQLException - Connection refused
 * INFO  [RetryEventLogger] Retry succeeded for upsertEntity on attempt 3
 * </pre>
 */
@ApplicationScoped
public class RetryEventLogger {

    private static final Logger logger = LoggerFactory.getLogger(RetryEventLogger.class);

    private static final String MDC_RETRY_OPERATION = "retry.operation";
    private static final String MDC_RETRY_ATTEMPT = "retry.attempt";
    private static final String MDC_RETRY_EXCEPTION = "retry.exception";

    private static final int DEFAULT_MAX_RETRIES = 4;
    private static final int MAX_MESSAGE_LENGTH = 200;

    /**
     * Logs a retry attempt with structured context.
     * 
     * <p>This method can be referenced from {@code @BeforeRetry} annotations
     * or called directly when a retry is about to occur.</p>
     * 
     * @param operation the name of the operation being retried
     * @param attempt the current attempt number (1-based)
     * @param failure the exception that triggered the retry (may be null)
     */
    public void logRetryAttempt(final String operation, final int attempt, final Throwable failure) {
        logRetryAttempt(operation, attempt, DEFAULT_MAX_RETRIES, failure);
    }

    /**
     * Logs a retry attempt with structured context and max retries info.
     * 
     * @param operation the name of the operation being retried
     * @param attempt the current attempt number (1-based)
     * @param maxAttempts the maximum number of attempts configured
     * @param failure the exception that triggered the retry (may be null)
     */
    public void logRetryAttempt(final String operation, final int attempt, final int maxAttempts, final Throwable failure) {
        final String exceptionName = failure != null ? failure.getClass().getSimpleName() : "unknown";
        final String message = failure != null ? failure.getMessage() : "no message";

        try {
            MDC.put(MDC_RETRY_OPERATION, operation);
            MDC.put(MDC_RETRY_ATTEMPT, String.valueOf(attempt));
            MDC.put(MDC_RETRY_EXCEPTION, exceptionName);

            logger.info("Retry attempt {}/{} for {}: {} - {}",
                attempt, maxAttempts, operation, exceptionName, truncateMessage(message));
        } finally {
            clearMDC();
        }
    }

    /**
     * Logs when all retry attempts have been exhausted.
     * 
     * @param operation the name of the operation that failed
     * @param totalAttempts the total number of attempts made
     * @param failure the final exception
     */
    public void logRetryExhausted(final String operation, final int totalAttempts, final Throwable failure) {
        final String exceptionName = failure != null ? failure.getClass().getSimpleName() : "unknown";
        final String message = failure != null ? failure.getMessage() : "no message";

        try {
            MDC.put(MDC_RETRY_OPERATION, operation);
            MDC.put(MDC_RETRY_ATTEMPT, String.valueOf(totalAttempts));
            MDC.put(MDC_RETRY_EXCEPTION, exceptionName);

            logger.warn("Retry exhausted for {} after {} attempts: {} - {}",
                operation, totalAttempts, exceptionName, truncateMessage(message));
        } finally {
            clearMDC();
        }
    }

    /**
     * Logs when an operation succeeds after one or more retries.
     * 
     * @param operation the name of the operation that succeeded
     * @param totalAttempts the total number of attempts made
     */
    public void logRetrySuccess(final String operation, final int totalAttempts) {
        try {
            MDC.put(MDC_RETRY_OPERATION, operation);
            MDC.put(MDC_RETRY_ATTEMPT, String.valueOf(totalAttempts));

            if (totalAttempts > 1) {
                logger.info("Retry succeeded for {} on attempt {}", operation, totalAttempts);
            }
        } finally {
            clearMDC();
        }
    }

    /**
     * Clears retry-related MDC context.
     */
    private void clearMDC() {
        MDC.remove(MDC_RETRY_OPERATION);
        MDC.remove(MDC_RETRY_ATTEMPT);
        MDC.remove(MDC_RETRY_EXCEPTION);
    }

    /**
     * Truncates long error messages to avoid log bloat.
     * 
     * @param message the message to truncate
     * @return truncated message (max 200 characters)
     */
    private String truncateMessage(final String message) {
        if (message == null) {
            return "null";
        }
        if (message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }
}
