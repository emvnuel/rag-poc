package br.edu.ifba.lightrag.storage.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Configuration for Apache AGE (A Graph Extension) PostgreSQL backend.
 * Adapted for Quarkus with CDI and managed DataSource.
 */
@ApplicationScoped
public class AgeConfig {
    
    @Inject
    DataSource dataSource;
    
    @ConfigProperty(name = "lightrag.graph.name", defaultValue = "lightrag_graph")
    String graphName;
    
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
    
    public String getGraphName() {
        return graphName;
    }
    
    /**
     * Initializes the AGE extension and creates the graph if it doesn't exist.
     * This should be called once during application startup.
     */
    public void initialize() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Load AGE extension
            stmt.execute("CREATE EXTENSION IF NOT EXISTS age");
            
            // Load the AGE extension into the current session
            stmt.execute("LOAD 'age'");
            
            // Set search path to include ag_catalog
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            // Create graph if it doesn't exist
            String createGraphSql = String.format(
                "SELECT * FROM ag_catalog.create_graph('%s')", graphName
            );
            
            // Check if graph already exists
            try {
                stmt.execute(createGraphSql);
            } catch (SQLException e) {
                // Graph might already exist, which is fine
                if (!e.getMessage().contains("already exists")) {
                    throw e;
                }
            }
            
            // Pre-create labels to avoid race conditions during concurrent operations
            createLabelIfNotExists(conn, "Entity");
            createLabelIfNotExists(conn, "RELATED_TO");
        }
    }
    
    /**
     * Creates a label (node or edge type) if it doesn't already exist.
     * This prevents "relation already exists" errors during concurrent operations.
     */
    private void createLabelIfNotExists(Connection conn, String labelName) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Check if label exists in ag_catalog.ag_label
            String checkSql = String.format(
                "SELECT EXISTS(SELECT 1 FROM ag_catalog.ag_label WHERE name = '%s' AND graph = " +
                "(SELECT graphid FROM ag_catalog.ag_graph WHERE name = '%s'))",
                labelName, graphName
            );
            
            ResultSet rs = stmt.executeQuery(checkSql);
            boolean exists = false;
            if (rs.next()) {
                exists = rs.getBoolean(1);
            }
            rs.close();
            
            // If label doesn't exist, create it with a dummy node/edge
            if (!exists) {
                String cypher;
                if ("RELATED_TO".equals(labelName)) {
                    // Create a dummy edge and then delete it to create the label
                    cypher = String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ " +
                        "CREATE (a:_TempNode {temp: true})-[r:RELATED_TO {temp: true}]->(b:_TempNode {temp: true}) " +
                        "RETURN r $$) AS (result agtype)",
                        graphName
                    );
                    stmt.execute(cypher);
                    
                    // Clean up temp nodes
                    cypher = String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ " +
                        "MATCH (n:_TempNode {temp: true}) DETACH DELETE n $$) AS (result agtype)",
                        graphName
                    );
                    stmt.execute(cypher);
                } else {
                    // Create a dummy node and then delete it to create the label
                    cypher = String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ " +
                        "CREATE (n:%s {temp: true}) RETURN n $$) AS (result agtype)",
                        graphName, labelName
                    );
                    stmt.execute(cypher);
                    
                    // Clean up temp node
                    cypher = String.format(
                        "SELECT * FROM ag_catalog.cypher('%s', $$ " +
                        "MATCH (n:%s {temp: true}) DELETE n $$) AS (result agtype)",
                        graphName, labelName
                    );
                    stmt.execute(cypher);
                }
            }
        }
    }
    
    /**
     * Drops the graph and all its data. Use with caution!
     */
    public void dropGraph() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("LOAD 'age'");
            stmt.execute("SET search_path = ag_catalog, \"$user\", public");
            
            String dropGraphSql = String.format(
                "SELECT * FROM ag_catalog.drop_graph('%s', true)", graphName
            );
            stmt.execute(dropGraphSql);
        }
    }
    
    /**
     * Close method for compatibility. DataSource is managed by Quarkus.
     */
    public void close() {
        // DataSource is managed by Quarkus, no need to close
    }
}
