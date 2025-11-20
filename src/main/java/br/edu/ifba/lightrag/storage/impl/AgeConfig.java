package br.edu.ifba.lightrag.storage.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Configuration for Apache AGE (A Graph Extension) PostgreSQL backend.
 * Adapted for Quarkus with CDI and managed DataSource.
 * 
 * <p>Note: This system uses per-project graph isolation. Individual project graphs 
 * are created on-demand by {@link AgeGraphStorage} with names like "graph_&lt;project_uuid&gt;".
 * The old shared graph approach (lightrag.graph.name config) has been removed.</p>
 */
@ApplicationScoped
public class AgeConfig {
    
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
    
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    /**
     * Close method for compatibility. DataSource is managed by Quarkus.
     */
    public void close() {
        // DataSource is managed by Quarkus, no need to close
    }
}
