package br.edu.ifba.lightrag.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TransientSQLExceptionPredicate}.
 * 
 * Tests cover SQLSTATE classification based on PostgreSQL error codes:
 * - 08xxx: Connection exceptions (transient)
 * - 40xxx: Transaction rollback (transient - deadlock)
 * - 53xxx: Insufficient resources (transient)
 * - 57xxx: Operator intervention (transient)
 * - 23xxx: Integrity constraint violation (permanent)
 * - 42xxx: Syntax error or access rule violation (permanent)
 * 
 * @see <a href="https://www.postgresql.org/docs/current/errcodes-appendix.html">PostgreSQL Error Codes</a>
 */
class TransientSQLExceptionPredicateTest {

    private TransientSQLExceptionPredicate predicate;

    @BeforeEach
    void setUp() {
        predicate = new TransientSQLExceptionPredicate();
    }

    @Nested
    @DisplayName("Connection Exceptions (08xxx)")
    class ConnectionExceptions {

        @Test
        @DisplayName("should return true for SQLSTATE 08000 (connection exception)")
        void testConnectionException() {
            final SQLException ex = new SQLException("Connection failed", "08000");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 08003 (connection does not exist)")
        void testConnectionDoesNotExist() {
            final SQLException ex = new SQLException("Connection does not exist", "08003");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 08006 (connection failure)")
        void testConnectionFailure() {
            final SQLException ex = new SQLException("Connection failure", "08006");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 08001 (SQL client unable to establish connection)")
        void testClientUnableToConnect() {
            final SQLException ex = new SQLException("Unable to connect", "08001");
            assertTrue(predicate.test(ex));
        }
    }

    @Nested
    @DisplayName("Transaction Rollback / Deadlock (40xxx)")
    class DeadlockExceptions {

        @Test
        @DisplayName("should return true for SQLSTATE 40001 (serialization failure)")
        void testSerializationFailure() {
            final SQLException ex = new SQLException("Serialization failure", "40001");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 40P01 (deadlock detected)")
        void testDeadlockDetected() {
            final SQLException ex = new SQLException("Deadlock detected", "40P01");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 40000 (transaction rollback)")
        void testTransactionRollback() {
            final SQLException ex = new SQLException("Transaction rollback", "40000");
            assertTrue(predicate.test(ex));
        }
    }

    @Nested
    @DisplayName("Insufficient Resources (53xxx)")
    class ResourceExceptions {

        @Test
        @DisplayName("should return true for SQLSTATE 53000 (insufficient resources)")
        void testInsufficientResources() {
            final SQLException ex = new SQLException("Insufficient resources", "53000");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 53100 (disk full)")
        void testDiskFull() {
            final SQLException ex = new SQLException("Disk full", "53100");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 53200 (out of memory)")
        void testOutOfMemory() {
            final SQLException ex = new SQLException("Out of memory", "53200");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 53300 (too many connections)")
        void testTooManyConnections() {
            final SQLException ex = new SQLException("Too many connections", "53300");
            assertTrue(predicate.test(ex));
        }
    }

    @Nested
    @DisplayName("Operator Intervention (57xxx)")
    class OperatorInterventionExceptions {

        @Test
        @DisplayName("should return true for SQLSTATE 57000 (operator intervention)")
        void testOperatorIntervention() {
            final SQLException ex = new SQLException("Operator intervention", "57000");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 57014 (query canceled)")
        void testQueryCanceled() {
            final SQLException ex = new SQLException("Query canceled", "57014");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLSTATE 57P01 (admin shutdown)")
        void testAdminShutdown() {
            final SQLException ex = new SQLException("Admin shutdown", "57P01");
            assertTrue(predicate.test(ex));
        }
    }

    @Nested
    @DisplayName("Java SQL Transient Exception Types")
    class JavaTransientExceptions {

        @Test
        @DisplayName("should return true for SQLTransientConnectionException")
        void testSqlTransientConnectionException() {
            final SQLTransientConnectionException ex = new SQLTransientConnectionException("Transient connection issue");
            assertTrue(predicate.test(ex));
        }

        @Test
        @DisplayName("should return true for SQLTimeoutException")
        void testSqlTimeoutException() {
            final SQLTimeoutException ex = new SQLTimeoutException("Query timed out");
            assertTrue(predicate.test(ex));
        }
    }

    @Nested
    @DisplayName("Permanent Errors (should NOT retry)")
    class PermanentErrors {

        @Test
        @DisplayName("should return false for SQLSTATE 23505 (unique constraint violation)")
        void testUniqueConstraintViolation() {
            final SQLException ex = new SQLException("Duplicate key", "23505");
            assertFalse(predicate.test(ex));
        }

        @Test
        @DisplayName("should return false for SQLSTATE 23503 (foreign key violation)")
        void testForeignKeyViolation() {
            final SQLException ex = new SQLException("Foreign key violation", "23503");
            assertFalse(predicate.test(ex));
        }

        @Test
        @DisplayName("should return false for SQLSTATE 23502 (not null violation)")
        void testNotNullViolation() {
            final SQLException ex = new SQLException("Not null violation", "23502");
            assertFalse(predicate.test(ex));
        }

        @Test
        @DisplayName("should return false for SQLSTATE 42601 (syntax error)")
        void testSyntaxError() {
            final SQLException ex = new SQLException("Syntax error", "42601");
            assertFalse(predicate.test(ex));
        }

        @Test
        @DisplayName("should return false for SQLSTATE 42501 (insufficient privilege)")
        void testInsufficientPrivilege() {
            final SQLException ex = new SQLException("Permission denied", "42501");
            assertFalse(predicate.test(ex));
        }

        @Test
        @DisplayName("should return false for SQLSTATE 42P01 (undefined table)")
        void testUndefinedTable() {
            final SQLException ex = new SQLException("Table does not exist", "42P01");
            assertFalse(predicate.test(ex));
        }
    }

    @Nested
    @DisplayName("Cause Chain Traversal")
    class CauseChainTraversal {

        @Test
        @DisplayName("should return true when transient exception is in cause chain")
        void testTransientInCauseChain() {
            final SQLException transientCause = new SQLException("Connection failed", "08000");
            final RuntimeException wrapper = new RuntimeException("Wrapped exception", transientCause);
            assertTrue(predicate.test(wrapper));
        }

        @Test
        @DisplayName("should return true for deeply nested transient exception")
        void testDeeplyNestedTransient() {
            final SQLException transientRoot = new SQLException("Deadlock", "40001");
            final SQLException midLevel = new SQLException("SQL error", "00000", transientRoot);
            final RuntimeException wrapper = new RuntimeException("Wrapped", midLevel);
            assertTrue(predicate.test(wrapper));
        }

        @Test
        @DisplayName("should return false when permanent exception is only exception in chain")
        void testPermanentOnlyInChain() {
            final SQLException permanentCause = new SQLException("Constraint violation", "23505");
            final RuntimeException wrapper = new RuntimeException("Wrapped exception", permanentCause);
            assertFalse(predicate.test(wrapper));
        }

        @Test
        @DisplayName("should return false for non-SQLException")
        void testNonSqlException() {
            final RuntimeException ex = new RuntimeException("Not an SQL error");
            assertFalse(predicate.test(ex));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should return false for null input")
        void testNullInput() {
            assertFalse(predicate.test(null));
        }

        @Test
        @DisplayName("should return false for SQLException with null SQLSTATE")
        void testNullSqlState() {
            final SQLException ex = new SQLException("Error with null state", (String) null);
            assertFalse(predicate.test(ex));
        }

        @Test
        @DisplayName("should return false for SQLException with empty SQLSTATE")
        void testEmptySqlState() {
            final SQLException ex = new SQLException("Error with empty state", "");
            assertFalse(predicate.test(ex));
        }

        @Test
        @DisplayName("should return false for unknown SQLSTATE prefix")
        void testUnknownSqlStatePrefix() {
            final SQLException ex = new SQLException("Unknown error", "99999");
            assertFalse(predicate.test(ex));
        }
    }
}
