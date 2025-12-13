package br.edu.ifba.lightrag.storage.impl;

import java.time.Duration;

/**
 * Exception thrown when SQLite database is locked and cannot be accessed.
 * 
 * <p>SQLite uses file-level locking, which means only one writer can access
 * the database at a time. This exception is thrown when an operation cannot
 * acquire a lock within the configured timeout period.</p>
 * 
 * <p>Common causes include:</p>
 * <ul>
 *   <li>Another write operation is in progress</li>
 *   <li>An external process has locked the database file</li>
 *   <li>High contention from concurrent operations</li>
 *   <li>Long-running transactions holding locks</li>
 * </ul>
 * 
 * <p>Recommendations:</p>
 * <ul>
 *   <li>Increase the busy timeout via {@code lightrag.storage.sqlite.busy-timeout}</li>
 *   <li>Use WAL mode for better concurrency</li>
 *   <li>Reduce transaction scope</li>
 *   <li>Consider using PostgreSQL for high-concurrency workloads</li>
 * </ul>
 */
public final class SQLiteDatabaseLockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration waitTime;
    private final String operation;

    /**
     * Creates a new SQLiteDatabaseLockedException.
     * 
     * @param operation the operation that could not acquire the lock
     * @param waitTime how long the operation waited for the lock
     * @param cause the underlying exception
     */
    public SQLiteDatabaseLockedException(String operation, Duration waitTime, Throwable cause) {
        super(buildMessage(operation, waitTime), cause);
        this.operation = operation;
        this.waitTime = waitTime;
    }

    /**
     * Creates a new SQLiteDatabaseLockedException without a cause.
     * 
     * @param operation the operation that could not acquire the lock
     * @param waitTime how long the operation waited for the lock
     */
    public SQLiteDatabaseLockedException(String operation, Duration waitTime) {
        super(buildMessage(operation, waitTime));
        this.operation = operation;
        this.waitTime = waitTime;
    }

    private static String buildMessage(String operation, Duration waitTime) {
        return String.format(
            "SQLite database is locked. Operation '%s' could not acquire lock after %d ms. " +
            "Consider increasing busy timeout or reducing contention.",
            operation, waitTime.toMillis()
        );
    }

    /**
     * Returns how long the operation waited for the lock.
     * 
     * @return the wait duration
     */
    public Duration getWaitTime() {
        return waitTime;
    }

    /**
     * Returns the operation that could not acquire the lock.
     * 
     * @return the operation description
     */
    public String getOperation() {
        return operation;
    }
}
