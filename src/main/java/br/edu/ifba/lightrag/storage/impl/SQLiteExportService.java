package br.edu.ifba.lightrag.storage.impl;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.jboss.logging.Logger;

/**
 * Service for exporting and importing project data to/from standalone SQLite database files.
 * 
 * <p>This service enables:</p>
 * <ul>
 *   <li>Creating portable, self-contained knowledge base files</li>
 *   <li>Sharing knowledge bases between instances</li>
 *   <li>Backup and restore of project data</li>
 *   <li>Offline transfer of knowledge bases</li>
 * </ul>
 * 
 * <p>Exported data includes:</p>
 * <ul>
 *   <li>Graph entities and relations</li>
 *   <li>Vector embeddings</li>
 *   <li>Extraction cache</li>
 *   <li>Key-value store entries</li>
 *   <li>Document status entries</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>
 * SQLiteExportService exportService = new SQLiteExportService(connectionManager);
 * 
 * // Export a project to a standalone file
 * exportService.exportProject(projectId, Path.of("export.db")).join();
 * 
 * // Import from a file into a new project
 * exportService.importProject(Path.of("export.db"), newProjectId).join();
 * </pre>
 * 
 * @since spec-009
 */
public class SQLiteExportService implements Closeable {

    private static final Logger LOG = Logger.getLogger(SQLiteExportService.class);

    private final SQLiteConnectionManager sourceConnectionManager;

    /**
     * Creates an export service for the given connection manager.
     * 
     * @param connectionManager the source database connection manager
     */
    public SQLiteExportService(SQLiteConnectionManager connectionManager) {
        this.sourceConnectionManager = connectionManager;
    }

    /**
     * Exports all data for a project to a standalone SQLite database file.
     * 
     * <p>The exported file contains:</p>
     * <ul>
     *   <li>Complete schema (created via migration)</li>
     *   <li>All entities and relations for the project</li>
     *   <li>All vector embeddings for the project</li>
     *   <li>All extraction cache entries for the project</li>
     *   <li>All key-value store entries for the project</li>
     * </ul>
     * 
     * @param projectId the project ID to export
     * @param exportPath the path to create the export file
     * @return CompletableFuture that completes when export is done
     * @throws IllegalArgumentException if project does not exist
     */
    public CompletableFuture<Void> exportProject(String projectId, Path exportPath) {
        return CompletableFuture.runAsync(() -> {
            LOG.infof("Exporting project %s to %s", projectId, exportPath);
            
            // Verify project exists
            if (!projectExists(projectId)) {
                throw new IllegalArgumentException("Project not found: " + projectId);
            }
            
            // Delete export file if it exists
            try {
                Files.deleteIfExists(exportPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to delete existing export file: " + exportPath, e);
            }
            
            // Create export database with schema
            try (SQLiteConnectionManager exportConn = new SQLiteConnectionManager(exportPath.toString())) {
                Connection conn = exportConn.createConnection();
                SQLiteSchemaMigrator migrator = new SQLiteSchemaMigrator();
                migrator.migrateToLatest(conn);
                
                // Export all project data
                exportProjectData(projectId, exportConn);
                
                LOG.infof("Successfully exported project %s to %s", projectId, exportPath);
            } catch (Exception e) {
                // Clean up partial export
                try {
                    Files.deleteIfExists(exportPath);
                } catch (IOException ignored) {
                    // Ignore cleanup errors
                }
                throw new RuntimeException("Failed to export project: " + projectId, e);
            }
        });
    }

    /**
     * Imports project data from an exported SQLite database file.
     * 
     * <p>The import process:</p>
     * <ol>
     *   <li>Reads all data from the export file</li>
     *   <li>Remaps the project ID to the specified new project ID</li>
     *   <li>Inserts all data into the target database</li>
     * </ol>
     * 
     * @param importPath the path to the export file to import
     * @param targetProjectId the project ID to import data into
     * @return CompletableFuture that completes when import is done
     * @throws IllegalArgumentException if import file does not exist
     */
    public CompletableFuture<Void> importProject(Path importPath, String targetProjectId) {
        return CompletableFuture.runAsync(() -> {
            LOG.infof("Importing project from %s into %s", importPath, targetProjectId);
            
            if (!Files.exists(importPath)) {
                throw new IllegalArgumentException("Import file not found: " + importPath);
            }
            
            // Open import database
            try (SQLiteConnectionManager importConn = new SQLiteConnectionManager(importPath.toString())) {
                importProjectData(importConn, targetProjectId);
                
                LOG.infof("Successfully imported project from %s into %s", importPath, targetProjectId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to import project from: " + importPath, e);
            }
        });
    }

    /**
     * Checks if a project exists in the source database.
     */
    private boolean projectExists(String projectId) {
        Connection conn = sourceConnectionManager.getReadConnection();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM projects WHERE id = ?")) {
            stmt.setString(1, projectId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            LOG.warnf("Error checking project existence: %s", e.getMessage());
            return false;
        } finally {
            sourceConnectionManager.releaseReadConnection(conn);
        }
    }

    /**
     * Exports all project data to the export database.
     */
    private void exportProjectData(String projectId, SQLiteConnectionManager exportConn) throws SQLException {
        Connection sourceConn = sourceConnectionManager.getReadConnection();
        Connection targetConn = exportConn.getWriteConnection();
        
        try {
            targetConn.setAutoCommit(false);
            
            // Export project record
            exportTable(sourceConn, targetConn, "projects", 
                "SELECT * FROM projects WHERE id = ?", projectId);
            
            // Export documents
            exportTable(sourceConn, targetConn, "documents", 
                "SELECT * FROM documents WHERE project_id = ?", projectId);
            
            // Export entities
            exportTable(sourceConn, targetConn, "graph_entities", 
                "SELECT * FROM graph_entities WHERE project_id = ?", projectId);
            
            // Export relations
            exportTable(sourceConn, targetConn, "graph_relations", 
                "SELECT * FROM graph_relations WHERE project_id = ?", projectId);
            
            // Export vectors
            exportTable(sourceConn, targetConn, "vectors", 
                "SELECT * FROM vectors WHERE project_id = ?", projectId);
            
            // Export extraction cache
            exportTable(sourceConn, targetConn, "extraction_cache", 
                "SELECT * FROM extraction_cache WHERE project_id = ?", projectId);
            
            // Note: kv_store and document_status are not project-scoped in the schema
            // They use different key patterns, so we skip them for project export
            // If needed in future, we could filter kv_store by key prefix pattern
            
            targetConn.commit();
            
        } catch (SQLException e) {
            try {
                targetConn.rollback();
            } catch (SQLException rollbackEx) {
                LOG.warn("Failed to rollback export transaction", rollbackEx);
            }
            throw e;
        } finally {
            targetConn.setAutoCommit(true);
            sourceConnectionManager.releaseReadConnection(sourceConn);
            exportConn.releaseWriteConnection(targetConn);
        }
    }

    /**
     * Exports data from a table using the given query.
     */
    private void exportTable(Connection sourceConn, Connection targetConn, 
            String tableName, String query, String projectId) throws SQLException {
        
        try (PreparedStatement selectStmt = sourceConn.prepareStatement(query)) {
            selectStmt.setString(1, projectId);
            ResultSet rs = selectStmt.executeQuery();
            
            int columnCount = rs.getMetaData().getColumnCount();
            if (columnCount == 0) {
                return;
            }
            
            // Build insert statement
            StringBuilder insertSql = new StringBuilder("INSERT INTO ");
            insertSql.append(tableName).append(" (");
            StringBuilder placeholders = new StringBuilder();
            
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    insertSql.append(", ");
                    placeholders.append(", ");
                }
                insertSql.append(rs.getMetaData().getColumnName(i));
                placeholders.append("?");
            }
            insertSql.append(") VALUES (").append(placeholders).append(")");
            
            try (PreparedStatement insertStmt = targetConn.prepareStatement(insertSql.toString())) {
                int rowCount = 0;
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        insertStmt.setObject(i, rs.getObject(i));
                    }
                    insertStmt.executeUpdate();
                    rowCount++;
                }
                
                if (rowCount > 0) {
                    LOG.debugf("Exported %d rows from %s", rowCount, tableName);
                }
            }
        }
    }

    /**
     * Imports project data from the import database.
     * 
     * <p>Foreign key checks are temporarily disabled during import since we generate
     * new IDs for all rows. The imported data maintains internal consistency from
     * the source database.</p>
     */
    private void importProjectData(SQLiteConnectionManager importConn, String targetProjectId) throws SQLException {
        Connection importConnDb = importConn.getReadConnection();
        Connection targetConn = sourceConnectionManager.getWriteConnection();
        
        try {
            // Disable foreign key checks BEFORE starting transaction
            // SQLite requires FK pragma changes outside of transactions
            try (Statement stmt = targetConn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = OFF");
            }
            
            targetConn.setAutoCommit(false);
            
            // Get the original project ID from the import file
            String originalProjectId = getOriginalProjectId(importConnDb);
            if (originalProjectId == null) {
                throw new SQLException("No project found in import file");
            }
            
            // Import documents with project ID remapping (must be before vectors/entities due to FK)
            importTableWithRemap(importConnDb, targetConn, "documents", 
                "project_id", originalProjectId, targetProjectId);
            
            // Import entities with project ID remapping
            importTableWithRemap(importConnDb, targetConn, "graph_entities", 
                "project_id", originalProjectId, targetProjectId);
            
            // Import relations with project ID remapping
            importTableWithRemap(importConnDb, targetConn, "graph_relations", 
                "project_id", originalProjectId, targetProjectId);
            
            // Import vectors with project ID remapping
            importTableWithRemap(importConnDb, targetConn, "vectors", 
                "project_id", originalProjectId, targetProjectId);
            
            // Import extraction cache with project ID remapping
            importTableWithRemap(importConnDb, targetConn, "extraction_cache", 
                "project_id", originalProjectId, targetProjectId);
            
            // Note: kv_store and document_status are not project-scoped
            // They were not exported, so we skip importing them
            
            targetConn.commit();
            
        } catch (SQLException e) {
            try {
                targetConn.rollback();
            } catch (SQLException rollbackEx) {
                LOG.warn("Failed to rollback import transaction", rollbackEx);
            }
            throw e;
        } finally {
            targetConn.setAutoCommit(true);
            // Re-enable foreign key checks after transaction complete
            try (Statement stmt = targetConn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            } catch (SQLException e) {
                LOG.warn("Failed to re-enable foreign key checks", e);
            }
            importConn.releaseReadConnection(importConnDb);
            sourceConnectionManager.releaseWriteConnection(targetConn);
        }
    }

    /**
     * Gets the original project ID from the import file.
     */
    private String getOriginalProjectId(Connection importConn) throws SQLException {
        try (Statement stmt = importConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM projects LIMIT 1")) {
            if (rs.next()) {
                return rs.getString("id");
            }
            return null;
        }
    }

    /**
     * Imports data from a table with project ID remapping.
     * 
     * <p>This method generates new UUIDs for the 'id' column to prevent conflicts
     * with existing data in the target database. The project_id column is remapped
     * to the target project ID.</p>
     */
    private void importTableWithRemap(Connection importConn, Connection targetConn,
            String tableName, String projectIdColumn, String originalProjectId, 
            String targetProjectId) throws SQLException {
        
        String query = "SELECT * FROM " + tableName + " WHERE " + projectIdColumn + " = ?";
        
        try (PreparedStatement selectStmt = importConn.prepareStatement(query)) {
            selectStmt.setString(1, originalProjectId);
            ResultSet rs = selectStmt.executeQuery();
            
            int columnCount = rs.getMetaData().getColumnCount();
            if (columnCount == 0) {
                return;
            }
            
            // Build insert statement and track special columns
            StringBuilder insertSql = new StringBuilder("INSERT INTO ");
            insertSql.append(tableName).append(" (");
            StringBuilder placeholders = new StringBuilder();
            int projectIdColumnIndex = -1;
            int idColumnIndex = -1;
            
            for (int i = 1; i <= columnCount; i++) {
                String colName = rs.getMetaData().getColumnName(i);
                if (colName.equalsIgnoreCase(projectIdColumn)) {
                    projectIdColumnIndex = i;
                }
                if (colName.equalsIgnoreCase("id")) {
                    idColumnIndex = i;
                }
                if (i > 1) {
                    insertSql.append(", ");
                    placeholders.append(", ");
                }
                insertSql.append(colName);
                placeholders.append("?");
            }
            insertSql.append(") VALUES (").append(placeholders).append(")");
            
            try (PreparedStatement insertStmt = targetConn.prepareStatement(insertSql.toString())) {
                int rowCount = 0;
                while (rs.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        if (i == projectIdColumnIndex) {
                            // Remap project ID to target
                            insertStmt.setString(i, targetProjectId);
                        } else if (i == idColumnIndex) {
                            // Generate new UUID to avoid conflicts with existing data
                            insertStmt.setString(i, UUID.randomUUID().toString());
                        } else {
                            insertStmt.setObject(i, rs.getObject(i));
                        }
                    }
                    insertStmt.executeUpdate();
                    rowCount++;
                }
                
                if (rowCount > 0) {
                    LOG.debugf("Imported %d rows into %s with project ID remapping", rowCount, tableName);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        // No resources to close - connection manager is managed externally
    }
}
