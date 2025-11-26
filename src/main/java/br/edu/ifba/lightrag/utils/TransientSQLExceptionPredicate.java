package br.edu.ifba.lightrag.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Predicate to determine if an exception represents a transient SQL error
 * that should be retried.
 * 
 * <p>Classifies PostgreSQL SQLSTATE codes into transient (retryable) and
 * permanent (non-retryable) categories. Also recognizes Java SQL transient
 * exception types.</p>
 * 
 * <h2>Transient Error Categories (Will Retry):</h2>
 * <ul>
 *   <li><b>08xxx</b> - Connection exceptions (connection lost, unable to connect)</li>
 *   <li><b>40xxx</b> - Transaction rollback (deadlock, serialization failure)</li>
 *   <li><b>53xxx</b> - Insufficient resources (disk full, out of memory, too many connections)</li>
 *   <li><b>57xxx</b> - Operator intervention (admin shutdown, query canceled)</li>
 * </ul>
 * 
 * <h2>Permanent Error Categories (Will NOT Retry):</h2>
 * <ul>
 *   <li><b>23xxx</b> - Integrity constraint violation (unique, foreign key, not null)</li>
 *   <li><b>42xxx</b> - Syntax error or access rule violation</li>
 *   <li>All other codes</li>
 * </ul>
 * 
 * <h2>Usage with SmallRye Fault Tolerance:</h2>
 * <pre>{@code
 * @Retry(retryOn = SQLException.class, 
 *        abortOn = {IllegalArgumentException.class})
 * public void databaseOperation() {
 *     // Uses TransientSQLExceptionPredicate via CDI
 * }
 * }</pre>
 * 
 * @see <a href="https://www.postgresql.org/docs/current/errcodes-appendix.html">PostgreSQL Error Codes</a>
 * @see java.sql.SQLTransientException
 */
public final class TransientSQLExceptionPredicate implements Predicate<Throwable> {

    private static final Logger logger = LoggerFactory.getLogger(TransientSQLExceptionPredicate.class);

    /**
     * SQLSTATE prefixes that indicate transient (retryable) errors.
     * 
     * <ul>
     *   <li>08 - Connection Exception</li>
     *   <li>40 - Transaction Rollback (includes deadlock)</li>
     *   <li>53 - Insufficient Resources</li>
     *   <li>57 - Operator Intervention</li>
     * </ul>
     */
    private static final Set<String> TRANSIENT_SQLSTATE_PREFIXES = Set.of(
        "08", // Connection Exception
        "40", // Transaction Rollback (deadlock)
        "53", // Insufficient Resources
        "57"  // Operator Intervention
    );

    /**
     * Tests whether the given exception represents a transient SQL error
     * that should be retried.
     * 
     * <p>This method traverses the exception cause chain to find any
     * SQLException and checks its SQLSTATE code against known transient
     * error categories.</p>
     * 
     * @param throwable the exception to test (may be null)
     * @return {@code true} if the exception is transient and should be retried,
     *         {@code false} otherwise
     */
    @Override
    public boolean test(final Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        // Check Java transient SQL exception types first
        if (throwable instanceof SQLTransientConnectionException) {
            logger.debug("Transient connection exception detected: {}", throwable.getMessage());
            return true;
        }

        if (throwable instanceof SQLTimeoutException) {
            logger.debug("SQL timeout exception detected: {}", throwable.getMessage());
            return true;
        }

        // Check SQLSTATE for SQLException (also check chained SQLExceptions)
        if (throwable instanceof SQLException sqlException) {
            if (isTransientSqlState(sqlException)) {
                return true;
            }
            // Also check SQLException chain via getNextException()
            SQLException next = sqlException.getNextException();
            while (next != null) {
                if (isTransientSqlState(next)) {
                    return true;
                }
                next = next.getNextException();
            }
        }

        // Traverse cause chain
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return test(cause);
        }

        return false;
    }

    /**
     * Determines if the SQLException has a transient SQLSTATE code.
     * 
     * @param sqlException the SQL exception to check
     * @return {@code true} if the SQLSTATE indicates a transient error
     */
    private boolean isTransientSqlState(final SQLException sqlException) {
        final String sqlState = sqlException.getSQLState();

        if (sqlState == null || sqlState.isEmpty()) {
            logger.debug("SQLException with null/empty SQLSTATE: {}", sqlException.getMessage());
            return false;
        }

        // Check if SQLSTATE prefix matches any transient category
        if (sqlState.length() >= 2) {
            final String prefix = sqlState.substring(0, 2);
            if (TRANSIENT_SQLSTATE_PREFIXES.contains(prefix)) {
                logger.debug("Transient SQLSTATE detected: {} (prefix: {}), message: {}",
                    sqlState, prefix, sqlException.getMessage());
                return true;
            }
        }

        logger.debug("Permanent SQLSTATE detected: {}, message: {}", sqlState, sqlException.getMessage());
        return false;
    }
}
