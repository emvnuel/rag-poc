package br.edu.ifba.lightrag.storage.impl;

/**
 * Exception thrown when a graph query fails in SQLite storage.
 * 
 * <p>This exception is thrown when graph operations (entity/relation CRUD,
 * traversal, path finding) fail due to query issues or data problems.</p>
 * 
 * <p>Common causes include:</p>
 * <ul>
 *   <li>Invalid entity or relation data</li>
 *   <li>Missing required properties</li>
 *   <li>Constraint violations (duplicate entities)</li>
 *   <li>SQL syntax errors in generated queries</li>
 * </ul>
 */
public final class SQLiteGraphQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String query;
    private final String projectId;

    /**
     * Creates a new SQLiteGraphQueryException.
     * 
     * @param message the error description
     * @param query the SQL query that failed
     * @param projectId the project context
     * @param cause the underlying exception
     */
    public SQLiteGraphQueryException(String message, String query, String projectId, Throwable cause) {
        super(buildMessage(message, query, projectId), cause);
        this.query = query;
        this.projectId = projectId;
    }

    /**
     * Creates a new SQLiteGraphQueryException without a cause.
     * 
     * @param message the error description
     * @param query the SQL query that failed
     * @param projectId the project context
     */
    public SQLiteGraphQueryException(String message, String query, String projectId) {
        super(buildMessage(message, query, projectId));
        this.query = query;
        this.projectId = projectId;
    }

    /**
     * Creates a simple SQLiteGraphQueryException with just a message and cause.
     * 
     * @param message the error description
     * @param cause the underlying exception
     */
    public SQLiteGraphQueryException(String message, Throwable cause) {
        super(message, cause);
        this.query = null;
        this.projectId = null;
    }

    private static String buildMessage(String message, String query, String projectId) {
        if (query == null || query.isEmpty()) {
            return String.format("Graph query error in project '%s': %s", projectId, message);
        }
        // Truncate long queries for readability
        String displayQuery = query.length() > 200 ? query.substring(0, 200) + "..." : query;
        return String.format(
            "Graph query error in project '%s': %s. Query: %s",
            projectId, message, displayQuery
        );
    }

    /**
     * Returns the SQL query that failed.
     * 
     * @return the query, or null if not available
     */
    public String getQuery() {
        return query;
    }

    /**
     * Returns the project ID context.
     * 
     * @return the project ID, or null if not available
     */
    public String getProjectId() {
        return projectId;
    }
}
