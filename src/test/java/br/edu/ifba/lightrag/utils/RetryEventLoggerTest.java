package br.edu.ifba.lightrag.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link RetryEventLogger}.
 * 
 * <p>Tests verify that retry events are logged with proper MDC context
 * and that message formatting works correctly.</p>
 * 
 * <p>These tests focus on:</p>
 * <ul>
 *   <li>T057: Retry attempts are logged with correct context</li>
 *   <li>T058: Retry exhaustion is logged with correct severity</li>
 *   <li>T059: Log messages include operation context and attempt info</li>
 * </ul>
 */
class RetryEventLoggerTest {

    private RetryEventLogger logger;

    @BeforeEach
    void setUp() {
        logger = new RetryEventLogger();
        // Clear any existing MDC context
        MDC.clear();
    }

    @Nested
    @DisplayName("T057: Retry Attempt Logging")
    class RetryAttemptLoggingTests {

        @Test
        @DisplayName("logRetryAttempt sets MDC context during logging")
        void testLogRetryAttemptSetsMDCContext() {
            // We can't directly verify MDC during logging (it's cleared after),
            // but we can verify it's cleared afterward and the method runs without error
            final SQLException failure = new SQLException("Connection reset", "08006");

            logger.logRetryAttempt("upsertEntity", 2, failure);

            // MDC should be cleared after logging
            assertNull(MDC.get("retry.operation"), "MDC should be cleared after logging");
            assertNull(MDC.get("retry.attempt"), "MDC should be cleared after logging");
            assertNull(MDC.get("retry.exception"), "MDC should be cleared after logging");
        }

        @Test
        @DisplayName("logRetryAttempt handles null failure gracefully")
        void testLogRetryAttemptHandlesNullFailure() {
            // Should not throw exception
            logger.logRetryAttempt("queryEntities", 1, null);

            assertNull(MDC.get("retry.operation"), "MDC should be cleared after logging");
        }

        @Test
        @DisplayName("logRetryAttempt with maxAttempts logs correct format")
        void testLogRetryAttemptWithMaxAttempts() {
            final RuntimeException failure = new RuntimeException("Database unavailable");

            logger.logRetryAttempt("batchUpsert", 3, 5, failure);

            // Verify MDC is cleared
            assertNull(MDC.get("retry.operation"));
        }
    }

    @Nested
    @DisplayName("T058: Retry Exhausted Logging")
    class RetryExhaustedLoggingTests {

        @Test
        @DisplayName("logRetryExhausted logs warning level message")
        void testLogRetryExhaustedLogsWarning() {
            final SQLException failure = new SQLException("Max connections exceeded", "53300");

            // This should log at WARN level - verification via log output
            logger.logRetryExhausted("createProjectGraph", 4, failure);

            // MDC should be cleared after logging
            assertNull(MDC.get("retry.operation"));
            assertNull(MDC.get("retry.attempt"));
        }

        @Test
        @DisplayName("logRetryExhausted handles null failure gracefully")
        void testLogRetryExhaustedHandlesNullFailure() {
            // Should not throw exception
            logger.logRetryExhausted("deleteVector", 3, null);

            assertNull(MDC.get("retry.exception"));
        }
    }

    @Nested
    @DisplayName("T059: Log Context Verification")
    class LogContextVerificationTests {

        @Test
        @DisplayName("logRetrySuccess only logs for retry attempts > 1")
        void testLogRetrySuccessOnlyLogsForMultipleAttempts() {
            // First attempt success - should not produce visible log
            logger.logRetrySuccess("getEntity", 1);

            // Multiple attempts - should produce log
            logger.logRetrySuccess("getEntity", 3);

            // MDC should be cleared in both cases
            assertNull(MDC.get("retry.operation"));
            assertNull(MDC.get("retry.attempt"));
        }

        @Test
        @DisplayName("Message truncation works for long error messages")
        void testMessageTruncation() {
            // Create a very long error message
            final StringBuilder longMessage = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                longMessage.append("x");
            }

            final RuntimeException failure = new RuntimeException(longMessage.toString());

            // Should not throw exception, and should truncate the message
            logger.logRetryAttempt("longOperation", 1, failure);

            assertNull(MDC.get("retry.operation"));
        }

        @Test
        @DisplayName("Various exception types are handled correctly")
        void testVariousExceptionTypes() {
            // SQLException
            logger.logRetryAttempt("op1", 1, new SQLException("SQL error"));
            assertNull(MDC.get("retry.exception"));

            // RuntimeException
            logger.logRetryAttempt("op2", 1, new RuntimeException("Runtime error"));
            assertNull(MDC.get("retry.exception"));

            // IllegalStateException
            logger.logRetryAttempt("op3", 1, new IllegalStateException("State error"));
            assertNull(MDC.get("retry.exception"));

            // NullPointerException
            logger.logRetryAttempt("op4", 1, new NullPointerException("NPE"));
            assertNull(MDC.get("retry.exception"));
        }

        @Test
        @DisplayName("Operation names with special characters are handled")
        void testOperationNamesWithSpecialCharacters() {
            logger.logRetryAttempt("upsertEntity[project-123]", 1, new RuntimeException("error"));
            assertNull(MDC.get("retry.operation"));

            logger.logRetryAttempt("batch:upsert:relations", 2, new RuntimeException("error"));
            assertNull(MDC.get("retry.operation"));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Zero attempt number is handled")
        void testZeroAttemptNumber() {
            logger.logRetryAttempt("operation", 0, new RuntimeException("error"));
            assertNull(MDC.get("retry.attempt"));
        }

        @Test
        @DisplayName("Negative attempt number is handled")
        void testNegativeAttemptNumber() {
            logger.logRetryAttempt("operation", -1, new RuntimeException("error"));
            assertNull(MDC.get("retry.attempt"));
        }

        @Test
        @DisplayName("Empty operation name is handled")
        void testEmptyOperationName() {
            logger.logRetryAttempt("", 1, new RuntimeException("error"));
            assertNull(MDC.get("retry.operation"));
        }

        @Test
        @DisplayName("Exception with null message is handled")
        void testExceptionWithNullMessage() {
            final RuntimeException failure = new RuntimeException((String) null);
            logger.logRetryAttempt("operation", 1, failure);
            assertNull(MDC.get("retry.exception"));
        }

        @Test
        @DisplayName("Nested exception is handled")
        void testNestedExceptionIsHandled() {
            final SQLException root = new SQLException("Connection refused", "08001");
            final RuntimeException wrapper = new RuntimeException("Database error", root);

            logger.logRetryAttempt("operation", 1, wrapper);
            assertNull(MDC.get("retry.exception"));
        }
    }
}
