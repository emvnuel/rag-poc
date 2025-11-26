package br.edu.ifba.lightrag.storage.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Configuration for Apache AGE (A Graph Extension) PostgreSQL backend.
 * Adapted for Quarkus with CDI and managed DataSource.
 * 
 * <p>Note: This system uses per-project graph isolation. Individual project graphs 
 * are created on-demand by {@link AgeGraphStorage} with names like "graph_&lt;project_uuid&gt;".
 * The old shared graph approach (lightrag.graph.name config) has been removed.</p>
 * 
 * <p>Connection health is validated before returning connections to detect stale
 * or broken connections early and enable retry mechanisms.</p>
 */
@ApplicationScoped
public class AgeConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(AgeConfig.class);
    
    /** Validation query to check connection health */
    private static final String VALIDATION_QUERY = "SELECT 1";
    
    /** Validation timeout in seconds */
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;
    
    @Inject
    DataSource dataSource;
    
    /**
     * Default constructor for CDI.
     */
    public AgeConfig() {
    }
    
    public DataSource getDataSource() {
        return dataSource;
    }
    
    /**
     * Gets a validated connection from the data source.
     * 
     * <p>This method validates the connection health before returning it,
     * which helps detect stale or broken connections early. If validation
     * fails, the connection is closed and the exception is propagated to
     * allow retry mechanisms to kick in.</p>
     * 
     * @return a healthy, validated connection
     * @throws SQLException if connection cannot be obtained or fails validation
     */
    public Connection getConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        
        try {
            validateConnection(connection);
            return connection;
        } catch (SQLException e) {
            // Close the invalid connection
            closeQuietly(connection);
            throw new SQLException("Connection validation failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Gets a connection without validation (for cases where validation is not needed).
     * 
     * <p>Use this method when you need raw connection access without overhead,
     * or when connection validation is handled elsewhere.</p>
     * 
     * @return a connection from the data source (may not be validated)
     * @throws SQLException if connection cannot be obtained
     */
    public Connection getRawConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    /**
     * Validates that a connection is healthy and ready for use.
     * 
     * <p>Uses JDBC 4.0 isValid() method first (fast check), then falls back
     * to executing a simple query if isValid() is not supported or returns false.</p>
     * 
     * @param connection the connection to validate
     * @throws SQLException if connection is invalid or validation fails
     */
    public void validateConnection(Connection connection) throws SQLException {
        if (connection == null) {
            throw new SQLException("Connection is null");
        }
        
        if (connection.isClosed()) {
            throw new SQLException("Connection is closed");
        }
        
        // Try JDBC 4.0 isValid() first (most efficient)
        try {
            if (connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                logger.trace("Connection validated via isValid()");
                return;
            }
        } catch (SQLException e) {
            // isValid() might not be supported by all drivers, continue to query-based validation
            logger.trace("isValid() check failed, falling back to query validation: {}", e.getMessage());
        }
        
        // Fall back to query-based validation
        validateWithQuery(connection);
    }
    
    /**
     * Validates connection by executing a simple query.
     */
    private void validateWithQuery(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(VALIDATION_TIMEOUT_SECONDS);
            stmt.execute(VALIDATION_QUERY);
            logger.trace("Connection validated via query");
        } catch (SQLException e) {
            logger.debug("Connection query validation failed: {}", e.getMessage());
            throw new SQLException("Connection validation query failed", e);
        }
    }
    
    /**
     * Closes a connection quietly, logging any errors.
     */
    private void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.debug("Error closing invalid connection: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Close method for compatibility. DataSource is managed by Quarkus.
     */
    public void close() {
        // DataSource is managed by Quarkus, no need to close
    }
}
